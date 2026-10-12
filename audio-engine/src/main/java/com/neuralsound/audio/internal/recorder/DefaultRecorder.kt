package com.neuralsound.audio.internal.recorder

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.internal.legacy.FramePreciseRecorderSession
import com.neuralsound.audio.internal.legacy.FrameRecordingRequest
import com.neuralsound.audio.internal.legacy.RecorderError
import com.neuralsound.audio.internal.legacy.RecorderOperationResult
import com.neuralsound.audio.internal.legacy.RecordingResult
import com.neuralsound.audio.internal.legacy.RecorderStatus as LegacyStatus
import com.neuralsound.audio.recorder.MicMeter
import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.recorder.RecorderEvent
import com.neuralsound.audio.recorder.RecorderHealth
import com.neuralsound.audio.recorder.RecorderState
import com.neuralsound.audio.recorder.RecorderStatus
import com.neuralsound.audio.recorder.Take
import com.neuralsound.audio.recorder.TakeMode
import com.neuralsound.audio.recorder.TakeRequest
import com.neuralsound.audio.recorder.TakeResult
import com.neuralsound.audio.recorder.WaveformBuckets
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Recorder] over the native frame-precise recorder session.
 *
 * Meter levels and waveform buckets are read from native telemetry by one internal ticker
 * and pushed to flows, so apps collect instead of polling. A paused take is closed as a
 * segment; resuming punches in the next segment at the following frame (crossfaded natively).
 */
internal class DefaultRecorder(
    private val session: FramePreciseRecorderSession,
    private val hasRecordPermission: () -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val tickMs: Long = WaveformBuckets.BUCKET_MS.toLong(),
) : Recorder {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val operations = Mutex()
    private val released = AtomicBoolean(false)

    private val _state = MutableStateFlow(
        RecorderState(status = if (session.status.value == LegacyStatus.UNAVAILABLE) RecorderStatus.UNAVAILABLE else RecorderStatus.IDLE),
    )
    override val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<RecorderEvent> = _events.asSharedFlow()

    private val _meter = MutableStateFlow(MicMeter())
    override val meter: StateFlow<MicMeter> = _meter.asStateFlow()

    private var ticker: Job? = null
    @Volatile private var activeTake: DefaultTake? = null
    private var overflowCount = 0L

    init {
        scope.launch {
            session.currentFailure.collect { failure ->
                if (failure != null) onNativeFailure(failure.error)
            }
        }
    }

    override suspend fun openMic(): AudioResult<Unit> = operations.withLock {
        guard()?.let { return@withLock it }
        if (!hasRecordPermission()) return@withLock AudioResult.Err(AudioError.PermissionDenied)
        if (_state.value.isMicOpen) return@withLock AudioResult.Done
        val result = withContext(ioDispatcher) { session.startMicSession() }
        result.toAudioResult().also { outcome ->
            if (outcome.isOk) {
                publish(RecorderStatus.MIC_OPEN)
                startTicker()
            } else {
                _state.update { it.copy(error = outcome.errorOrNull()) }
            }
        }
    }

    override suspend fun closeMic() {
        operations.withLock {
            if (released.get() || !_state.value.isMicOpen) return@withLock
            activeTake?.let { finishSegmentLocked(it); finishTakeLocked(it) }
            stopTicker()
            withContext(ioDispatcher) { session.releaseMicSession() }
            publish(RecorderStatus.IDLE)
        }
    }

    override suspend fun record(request: TakeRequest): AudioResult<Take> = operations.withLock {
        guard()?.let { return@withLock it }
        if (activeTake != null) return@withLock AudioResult.Err(AudioError.InvalidState)
        if (!_state.value.isMicOpen) {
            if (!hasRecordPermission()) return@withLock AudioResult.Err(AudioError.PermissionDenied)
            val opened = withContext(ioDispatcher) { session.startMicSession() }.toAudioResult()
            if (!opened.isOk) return@withLock AudioResult.Err(opened.errorOrNull()!!)
            startTicker()
        }
        val prepared = withContext(ioDispatcher) { prepareFile(request) }
        if (!prepared) return@withLock AudioResult.Err(AudioError.FileError)
        val take = DefaultTake(request)
        val started = startSegmentLocked(take, request.startFrame)
        if (!started.isOk) return@withLock AudioResult.Err(started.errorOrNull()!!)
        activeTake = take
        publish(RecorderStatus.RECORDING)
        AudioResult.Ok(take)
    }

    override suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        operations.withLock {
            activeTake?.let { finishSegmentLocked(it); finishTakeLocked(it) }
            stopTicker()
            withContext(ioDispatcher) { session.close() }
            _state.update { it.copy(status = RecorderStatus.RELEASED, health = RecorderHealth()) }
        }
        scope.cancel()
    }

    // -----------------------------------------------------------------------------------
    // Take segments
    // -----------------------------------------------------------------------------------

    private inner class DefaultTake(val request: TakeRequest) : Take {
        override val file: File = request.file
        val waveformFlow = MutableSharedFlow<WaveformBuckets>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        override val waveform: Flow<WaveformBuckets> = waveformFlow.asSharedFlow()
        val written = MutableStateFlow(0L)
        override val writtenFrames: StateFlow<Long> = written.asStateFlow()

        /** Frames committed by finished segments; the live segment adds on top. */
        var committedFrames = 0L
        var committedBuckets = 0
        var consumedBuckets = 0
        var segmentOpen = false
        var finished = false

        @Volatile var pauseRequested = false

        /** Validates now; the native segment change happens off the caller's thread. */
        override fun pause(): AudioResult<Unit> {
            if (finished || pauseRequested) return AudioResult.Err(AudioError.InvalidState)
            pauseRequested = true
            scope.launch {
                operations.withLock {
                    if (finished || !segmentOpen) return@withLock
                    val result = finishSegmentLocked(this@DefaultTake)
                    publish(RecorderStatus.PAUSED)
                    result.errorOrNull()?.let { _events.tryEmit(RecorderEvent.Failed(it)) }
                }
            }
            return AudioResult.Done
        }

        override fun resume(): AudioResult<Unit> {
            if (finished || !pauseRequested) return AudioResult.Err(AudioError.InvalidState)
            pauseRequested = false
            scope.launch {
                operations.withLock {
                    if (finished || segmentOpen) return@withLock
                    val started = startSegmentLocked(this@DefaultTake, request.startFrame + committedFrames)
                    if (started.isOk) publish(RecorderStatus.RECORDING)
                    else started.errorOrNull()?.let { _events.tryEmit(RecorderEvent.Failed(it)) }
                }
            }
            return AudioResult.Done
        }

        override suspend fun stop(): AudioResult<TakeResult> = operations.withLock {
            if (finished) return@withLock AudioResult.Err(AudioError.InvalidState)
            val segment = if (segmentOpen) finishSegmentLocked(this) else null
            val result = finishTakeLocked(this)
            when {
                segment != null && !segment.isOk -> AudioResult.Err(segment.errorOrNull()!!)
                else -> AudioResult.Ok(result)
            }
        }
    }

    private suspend fun startSegmentLocked(take: DefaultTake, frame: Long): AudioResult<Unit> {
        val result = withContext(ioDispatcher) {
            session.startWriting(FrameRecordingRequest(take.request.file, frame))
        }.toAudioResult()
        if (result.isOk) {
            take.segmentOpen = true
            take.consumedBuckets = 0
        }
        return result
    }

    private suspend fun finishSegmentLocked(take: DefaultTake): AudioResult<Unit> {
        drainTelemetry(take)
        val result: RecordingResult = withContext(ioDispatcher) { session.stopWriting() }
        take.segmentOpen = false
        take.committedFrames += result.writtenFrames
        take.committedBuckets += take.consumedBuckets
        take.written.value = take.committedFrames
        return if (result.error == null) AudioResult.Done else AudioResult.Err(result.error.toAudioError())
    }

    private suspend fun finishTakeLocked(take: DefaultTake): TakeResult {
        take.finished = true
        activeTake = null
        val fileFrames = withContext(ioDispatcher) { wavFrames(take.file) }
        val result = TakeResult(
            file = take.file,
            startFrame = take.request.startFrame,
            frames = take.committedFrames,
            fileDurationMs = Recorder.msForFrames(fileFrames),
        )
        if (!released.get() && _state.value.status != RecorderStatus.FAILED) publish(RecorderStatus.MIC_OPEN)
        _events.tryEmit(RecorderEvent.TakeFinished(result))
        return result
    }

    private fun prepareFile(request: TakeRequest): Boolean = when (request.mode) {
        TakeMode.NEW -> request.file.parentFile?.let { it.exists() || it.mkdirs() } != false &&
            (!request.file.exists() || request.file.delete())
        TakeMode.PUNCH_IN -> request.file.isFile
    }

    // -----------------------------------------------------------------------------------
    // Telemetry ticker
    // -----------------------------------------------------------------------------------

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val peak = session.micAmplitude
                val take = activeTake
                var rms = _meter.value.rms
                if (take != null && take.segmentOpen) rms = drainTelemetry(take) ?: rms
                _meter.value = MicMeter(peak = peak, rms = rms, clipping = peak >= CLIP_THRESHOLD)
                _state.update { it.copy(health = health()) }
                delay(tickMs)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
        _meter.value = MicMeter()
    }

    /** Pushes new waveform buckets for the live segment; returns the latest RMS, if any. */
    private fun drainTelemetry(take: DefaultTake): Float? = synchronized(take) {
        val telemetry = session.telemetry(take.consumedBuckets) ?: return@synchronized null
        val buckets = telemetry.completedBucketRms
        if (buckets.isNotEmpty()) {
            take.waveformFlow.tryEmit(WaveformBuckets(take.committedBuckets + telemetry.firstBucketIndex, buckets.copyOf()))
            take.consumedBuckets = telemetry.firstBucketIndex + buckets.size
        }
        take.written.value = take.committedFrames + telemetry.writtenFrames
        if (telemetry.partialBucketFrames > 0) telemetry.partialBucketRms else buckets.lastOrNull()
    }

    private fun health(): RecorderHealth {
        val status = session.status.value
        return RecorderHealth(
            streamOpen = status == LegacyStatus.MIC_SESSION_ACTIVE || status == LegacyStatus.WRITING,
            writerAlive = status == LegacyStatus.WRITING,
            deviceSampleRate = null,
            overflowCount = overflowCount,
        )
    }

    // -----------------------------------------------------------------------------------
    // Failures
    // -----------------------------------------------------------------------------------

    private suspend fun onNativeFailure(error: RecorderError) {
        if (error == RecorderError.WriterOverflow) overflowCount++
        val publicError = error.toAudioError()
        operations.withLock {
            val take = activeTake
            if (error == RecorderError.StreamDisconnected) {
                // Finalize what was written so the partial take stays a valid WAV.
                val partial = take?.let {
                    if (it.segmentOpen) finishSegmentLocked(it)
                    finishTakeLocked(it)
                }
                stopTicker()
                _events.tryEmit(RecorderEvent.InputLost(partial))
            } else {
                take?.let { if (it.segmentOpen) finishSegmentLocked(it); finishTakeLocked(it) }
                _events.tryEmit(RecorderEvent.Failed(publicError))
            }
            _state.update { it.copy(status = RecorderStatus.FAILED, error = publicError, health = health()) }
        }
    }

    private fun guard(): AudioResult.Err? = when {
        released.get() -> AudioResult.Err(AudioError.Released)
        session.status.value == LegacyStatus.UNAVAILABLE -> AudioResult.Err(AudioError.Unavailable)
        else -> null
    }

    private fun publish(status: RecorderStatus) {
        _state.update { it.copy(status = status, error = null, health = health()) }
    }

    private fun RecorderOperationResult.toAudioResult(): AudioResult<Unit> =
        error?.let { AudioResult.Err(it.toAudioError()) } ?: AudioResult.Done

    private fun RecorderError.toAudioError(): AudioError = when (this) {
        RecorderError.NativeUnavailable -> AudioError.Unavailable
        RecorderError.MicSessionOpenFailed -> AudioError.MicUnavailable
        RecorderError.StreamDisconnected -> AudioError.Disconnected
        RecorderError.WriterOverflow -> AudioError.WriterOverflow
        RecorderError.WriterFileError, RecorderError.InvalidOutput -> AudioError.FileError
        RecorderError.Released -> AudioError.Released
        RecorderError.InvalidState -> AudioError.InvalidState
    }

    private companion object {
        const val CLIP_THRESHOLD = 0.98f
        const val WAV_HEADER_BYTES = 44L

        fun wavFrames(file: File): Long =
            if (file.isFile) ((file.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)) / 2L else 0L
    }
}
