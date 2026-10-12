package com.neuralsound.audio.internal.recorder

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.recorder.DraftProblem
import com.neuralsound.audio.recorder.DraftRequest
import com.neuralsound.audio.recorder.DraftState
import com.neuralsound.audio.recorder.DraftStatus
import com.neuralsound.audio.recorder.DraftWaveform
import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.recorder.RecorderEvent
import com.neuralsound.audio.recorder.RecordingDraft
import com.neuralsound.audio.recorder.Take
import com.neuralsound.audio.recorder.TakeMode
import com.neuralsound.audio.recorder.TakeRequest
import com.neuralsound.audio.recorder.TakeResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [RecordingDraft] state machine (ported from the OND `RecorderManager`, minus foreground and
 * telemetry polling). Every edit records into `<draft>.work`; the draft file is replaced only
 * after the working copy validated, so a failure never leaves a partial draft.
 */
internal class DefaultRecordingDraft(
    private val request: DraftRequest,
    private val recorder: Recorder,
    private val hasRecordPermission: () -> Boolean,
    dispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher = dispatcher,
    private val promoter: (File, File) -> Unit = { from, to -> AtomicFilePromoter.promote(from, to) },
    private val onClosed: (DefaultRecordingDraft) -> Unit = {},
    private val foregroundControl: MicForegroundControl? = null,
) : RecordingDraft {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Mutex()
    private val file: File = request.file
    private val workFile: File = File(file.parentFile, file.name + WORK_SUFFIX)

    private val _state = MutableStateFlow(DraftState())
    override val state: StateFlow<DraftState> = _state.asStateFlow()

    /** Last good draft: what a failed edit falls back to. */
    private var good = DraftState()
    private var edit: Edit? = null
    private var editCounter = 0
    private var foregroundActive = false

    private class Edit(
        val id: Int,
        val take: Take,
        val captureStart: Long,
        val wasEmpty: Boolean,
    ) {
        var jobs: List<Job> = emptyList()
    }

    /** Validates or creates the draft. On failure the state is FAILED with the problem set. */
    suspend fun initialize(): AudioResult<Unit> = commands.withLock {
        withContext(ioDispatcher) {
            try {
                AtomicFilePromoter.cleanupLeftovers(file)
            } catch (_: IOException) {
                return@withContext failOpen(DraftProblem.WRITER_FAILURE)
            }
            if (!file.exists()) {
                good = DraftState(status = DraftStatus.EMPTY)
                _state.value = good
                return@withContext AudioResult.Done
            }
            val info = WavInspector.inspect(file)?.takeIf { it.sampleRate == Recorder.SAMPLE_RATE }
            val levels = info?.let { PcmWaveformReader.read(file) }
            if (info == null || levels == null) return@withContext failOpen(DraftProblem.INVALID_TAKE)
            good = DraftState(
                status = if (info.frames > 0L) DraftStatus.PAUSED else DraftStatus.READY,
                cursorFrame = info.frames,
                totalFrames = info.frames,
                waveform = DraftWaveform(0L, levels),
            )
            _state.value = good
            AudioResult.Done
        }
    }

    private fun failOpen(problem: DraftProblem): AudioResult<Unit> {
        _state.value = DraftState(status = DraftStatus.FAILED, problem = problem)
        return AudioResult.Err(AudioError.FileError)
    }

    override suspend fun record(): AudioResult<Unit> = commands.withLock {
        if (good.totalFrames == 0L) startEdit(0L, TakeMode.NEW) else startEdit(good.totalFrames, TakeMode.PUNCH_IN)
    }

    override suspend fun resume(): AudioResult<Unit> = record()

    override suspend fun rerecordFrom(frame: Long): AudioResult<Unit> = commands.withLock {
        if (good.totalFrames == 0L) startEdit(0L, TakeMode.NEW)
        else startEdit(frame.coerceIn(0L, good.totalFrames), TakeMode.PUNCH_IN)
    }

    override suspend fun pause(): AudioResult<Unit> = commands.withLock { pauseLocked() }

    override suspend fun commit(destination: File): AudioResult<TakeResult> = commands.withLock {
        guardLive()?.let { return@withLock it }
        if (_state.value.status == DraftStatus.RECORDING) {
            val paused = pauseLocked()
            if (!paused.isOk) return@withLock AudioResult.Err(paused.errorOrNull()!!)
        }
        val ready = _state.value
        if (ready.status != DraftStatus.PAUSED || ready.totalFrames <= 0L) {
            _state.update { it.copy(problem = DraftProblem.EMPTY_TAKE, recoverable = true) }
            return@withLock AudioResult.Err(AudioError.InvalidState)
        }
        val frames = ready.totalFrames
        _state.update { it.copy(status = DraftStatus.COMMITTING, problem = null, recoverable = false) }
        val moved = withContext(ioDispatcher) {
            runCatching {
                if (destination.absoluteFile != file.absoluteFile) {
                    destination.absoluteFile.parentFile?.mkdirs()
                    promoter(file, destination)
                }
            }
        }
        if (moved.isFailure) {
            _state.value = good.copy(problem = DraftProblem.WRITER_FAILURE, recoverable = true)
            return@withLock AudioResult.Err(AudioError.FileError)
        }
        _state.value = DraftState(status = DraftStatus.COMMITTED, cursorFrame = frames, totalFrames = frames)
        close()
        AudioResult.Ok(TakeResult(destination, 0L, frames, Recorder.msForFrames(frames)))
    }

    override suspend fun discard() {
        commands.withLock {
            if (_state.value.status == DraftStatus.COMMITTED || _state.value.status == DraftStatus.DISCARDED) return@withLock
            edit?.let { active ->
                stopCollectors(active)
                runCatching { active.take.stop() }
                edit = null
            }
            withContext(ioDispatcher) { deleteAll() }
            good = DraftState(status = DraftStatus.DISCARDED)
            _state.value = good
            close()
        }
    }

    /** Called by the recorder when it is released; the draft file is kept but the draft is dead. */
    suspend fun onRecorderReleased() {
        commands.withLock {
            if (isTerminal(_state.value.status)) return@withLock
            edit?.let { stopCollectors(it) }
            edit = null
            withContext(ioDispatcher) { workFile.delete() }
            _state.update {
                good.copy(status = DraftStatus.FAILED, problem = null, recoverable = false)
            }
            close()
        }
    }

    // -----------------------------------------------------------------------------------

    private suspend fun startEdit(startFrame: Long, mode: TakeMode): AudioResult<Unit> {
        guardLive()?.let { return it }
        if (_state.value.status == DraftStatus.RECORDING || _state.value.status == DraftStatus.COMMITTING) {
            return AudioResult.Err(AudioError.InvalidState)
        }
        if (!hasRecordPermission()) {
            _state.value = good.copy(problem = DraftProblem.PERMISSION_DENIED, recoverable = true)
            return AudioResult.Err(AudioError.PermissionDenied)
        }
        if (!ensureForeground()) {
            _state.value = good.copy(problem = DraftProblem.FOREGROUND_SERVICE_UNAVAILABLE, recoverable = true)
            return AudioResult.Err(AudioError.InvalidState)
        }
        val wasEmpty = mode == TakeMode.NEW
        val prepared = withContext(ioDispatcher) {
            runCatching {
                workFile.delete()
                if (!wasEmpty) file.copyTo(workFile, overwrite = true)
            }.isSuccess
        }
        if (!prepared) return failStart(AudioError.FileError)

        val id = ++editCounter
        // Subscribe to events before the take starts so an immediate input loss is not missed.
        val eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            recorder.events.collect { event ->
                if (event is RecorderEvent.InputLost || event is RecorderEvent.Failed) {
                    scope.launch { handleEvent(id, event) }
                }
            }
        }
        val started = recorder.record(TakeRequest(workFile, startFrame, mode, request.saturation))
        val take = (started as? AudioResult.Ok)?.value
        if (take == null) {
            eventJob.cancelAndJoin()
            return failStart(started.errorOrNull() ?: AudioError.InvalidState)
        }
        val active = Edit(id, take, startFrame, wasEmpty)
        val levels = LiveLevels()
        active.jobs = listOf(
            eventJob,
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                take.waveform.collect { buckets ->
                    levels.put(buckets.firstIndex, buckets.rms)
                    _state.update { it.copy(liveWaveform = DraftWaveform(startFrame, levels.snapshot())) }
                }
            },
            scope.launch {
                take.writtenFrames.collect { written ->
                    _state.update {
                        val cursor = startFrame + written
                        it.copy(cursorFrame = cursor, totalFrames = maxOf(good.totalFrames, cursor))
                    }
                }
            },
            scope.launch {
                recorder.meter.collect { meter -> _state.update { it.copy(inputLevel = meter.peak.coerceIn(0f, 1f)) } }
            },
        )
        edit = active
        _state.value = good.copy(
            status = DraftStatus.RECORDING,
            cursorFrame = startFrame,
            liveWaveform = DraftWaveform(startFrame),
        )
        return AudioResult.Done
    }

    /** Promotes the opt-in mic service before the first take; it then stays up until the draft ends. */
    private suspend fun ensureForeground(): Boolean {
        val foreground = request.foreground ?: return true
        if (foregroundActive) return true
        val control = foregroundControl ?: return false
        foregroundActive = control.promote(foreground)
        return foregroundActive
    }

    private suspend fun failStart(error: AudioError): AudioResult<Unit> {
        withContext(ioDispatcher) { workFile.delete() }
        if (error == AudioError.Released) {
            _state.value = good.copy(status = DraftStatus.FAILED, problem = null, recoverable = false)
            close()
        } else {
            _state.value = good.copy(problem = problemFor(error), recoverable = true)
        }
        return AudioResult.Err(error)
    }

    private suspend fun pauseLocked(): AudioResult<Unit> {
        guardLive()?.let { return it }
        val active = edit ?: return if (_state.value.status == DraftStatus.PAUSED) AudioResult.Done
        else AudioResult.Err(AudioError.InvalidState)
        val stopped = active.take.stop()
        stopCollectors(active)
        edit = null
        val result = (stopped as? AudioResult.Ok)?.value
        if (result == null) {
            val error = stopped.errorOrNull() ?: AudioError.InvalidState
            return failEdit(problemFor(error), error)
        }
        return finalizeEdit(active, result.frames, null)
    }

    /**
     * Validates the finished working copy and promotes it. [keepProblem] marks an edit that was
     * cut short (input loss) but whose captured audio is still worth keeping.
     */
    private suspend fun finalizeEdit(active: Edit, writtenFrames: Long, keepProblem: DraftProblem?): AudioResult<Unit> {
        if (active.wasEmpty && writtenFrames <= 0L) {
            withContext(ioDispatcher) { workFile.delete() }
            _state.value = good.copy(problem = DraftProblem.EMPTY_TAKE, recoverable = true)
            return AudioResult.Err(AudioError.InvalidState)
        }
        val promoted = withContext(ioDispatcher) {
            val info = WavInspector.inspect(workFile)?.takeIf { it.sampleRate == Recorder.SAMPLE_RATE }
                ?: return@withContext Promotion.Invalid
            try {
                promoter(workFile, file)
            } catch (_: IOException) {
                return@withContext Promotion.WriteFailed
            }
            val levels = PcmWaveformReader.read(file) ?: FloatArray(0)
            Promotion.Done(info.frames, levels)
        }
        return when (promoted) {
            Promotion.Invalid -> failEdit(DraftProblem.INVALID_TAKE, AudioError.FileError)
            Promotion.WriteFailed -> failEdit(DraftProblem.WRITER_FAILURE, AudioError.FileError)
            is Promotion.Done -> {
                val cursor = (active.captureStart + writtenFrames).coerceAtMost(promoted.frames)
                good = DraftState(
                    status = DraftStatus.PAUSED,
                    cursorFrame = cursor,
                    totalFrames = promoted.frames,
                    waveform = DraftWaveform(0L, promoted.levels),
                )
                _state.value = good.copy(problem = keepProblem, recoverable = keepProblem != null)
                AudioResult.Done
            }
        }
    }

    private sealed interface Promotion {
        data object Invalid : Promotion
        data object WriteFailed : Promotion
        class Done(val frames: Long, val levels: FloatArray) : Promotion
    }

    /** Drops the working copy and falls back to the last good draft. */
    private suspend fun failEdit(problem: DraftProblem, error: AudioError): AudioResult<Unit> {
        withContext(ioDispatcher) { workFile.delete() }
        _state.value = good.copy(problem = problem, recoverable = true)
        return AudioResult.Err(error)
    }

    private suspend fun handleEvent(editId: Int, event: RecorderEvent) {
        commands.withLock {
            val active = edit?.takeIf { it.id == editId } ?: return@withLock
            // The recorder already finalized the take; just stop listening.
            stopCollectors(active)
            edit = null
            if (event is RecorderEvent.InputLost) {
                if (active.wasEmpty) {
                    // EC-3: keep what an initial take captured before the input was lost.
                    val frames = event.partial?.frames ?: 0L
                    if (frames > 0L) {
                        val kept = finalizeEdit(active, frames, DraftProblem.INPUT_LOST)
                        if (kept.isOk) return@withLock
                    } else {
                        withContext(ioDispatcher) { workFile.delete() }
                        _state.value = good.copy(problem = DraftProblem.INPUT_LOST, recoverable = true)
                    }
                    return@withLock
                }
                failEdit(DraftProblem.INPUT_LOST, AudioError.Disconnected)
            } else if (event is RecorderEvent.Failed) {
                failEdit(problemFor(event.error), event.error)
            }
            Unit
        }
    }

    private suspend fun stopCollectors(active: Edit) {
        active.jobs.forEach { it.cancelAndJoin() }
        active.jobs = emptyList()
        _state.update { it.copy(inputLevel = 0f, liveWaveform = DraftWaveform()) }
    }

    private fun guardLive(): AudioResult.Err? =
        if (isTerminal(_state.value.status)) AudioResult.Err(AudioError.InvalidState) else null

    private fun isTerminal(status: DraftStatus) =
        status == DraftStatus.COMMITTED || status == DraftStatus.DISCARDED || status == DraftStatus.FAILED

    private fun deleteAll() {
        file.delete()
        workFile.delete()
        File(file.parentFile, file.name + ".bak").delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private suspend fun close() {
        if (foregroundActive) {
            foregroundActive = false
            withContext(NonCancellable) { foregroundControl?.stop() }
        }
        scope.cancel()
        onClosed(this)
    }

    private fun problemFor(error: AudioError): DraftProblem = when (error) {
        AudioError.PermissionDenied -> DraftProblem.PERMISSION_DENIED
        AudioError.MicUnavailable -> DraftProblem.MIC_UNAVAILABLE
        AudioError.Disconnected -> DraftProblem.INPUT_LOST
        AudioError.WriterOverflow -> DraftProblem.OVERFLOW
        else -> DraftProblem.WRITER_FAILURE
    }

    /** Growable RMS list filled from incremental waveform buckets. */
    private class LiveLevels {
        private var data = FloatArray(INITIAL)
        private var count = 0

        @Synchronized
        fun put(first: Int, rms: FloatArray) {
            val end = first + rms.size
            if (end > data.size) data = data.copyOf(maxOf(end, data.size * 2))
            rms.copyInto(data, first)
            count = maxOf(count, end)
        }

        @Synchronized
        fun snapshot(): FloatArray = data.copyOf(count)

        private companion object {
            const val INITIAL = 1024
        }
    }

    private companion object {
        const val WORK_SUFFIX = ".work"
    }
}
