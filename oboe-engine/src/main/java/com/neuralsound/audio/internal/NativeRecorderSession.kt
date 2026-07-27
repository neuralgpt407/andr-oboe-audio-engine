package com.neuralsound.audio.internal

import com.neuralsound.audio.FramePreciseRecorderSession
import com.neuralsound.audio.FrameRecordingRequest
import com.neuralsound.audio.RecorderError
import com.neuralsound.audio.RecorderFailure
import com.neuralsound.audio.RecorderOperationResult
import com.neuralsound.audio.RecorderState
import com.neuralsound.audio.RecorderStatus
import com.neuralsound.audio.RecorderTelemetry
import com.neuralsound.audio.RecordingRequest
import com.neuralsound.audio.RecordingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.jvm.JvmName

private sealed interface RecorderStartOffset {
    val value: Long

    data class Milliseconds(override val value: Long) : RecorderStartOffset
    data class PcmFrames(override val value: Long) : RecorderStartOffset
}

internal class NativeRecorderSession internal constructor(
    private val nativeBridge: OboeRecorderNativeBridge,
    private val failureMonitorStopHook: () -> Unit = {},
    private val nativeLibraryLoader: () -> Boolean,
) : FramePreciseRecorderSession {
    constructor() : this(
        nativeBridge = OboeRecorderJniBridge,
        nativeLibraryLoader = OboeRecorderNativeLibrary::isAvailable,
    )

    private val handle = EngineHandle()
    private val released = AtomicBoolean(false)
    private val _state = MutableStateFlow(
        RecorderState(
            status = RecorderStatus.UNAVAILABLE,
            currentFailure = null,
        ),
    )
    override val state: StateFlow<RecorderState> = _state.asStateFlow()
    private val _status = MutableStateFlow(_state.value.status)
    override val status: StateFlow<RecorderStatus> = _status.asStateFlow()
    private val _currentFailure = MutableStateFlow(_state.value.currentFailure)
    override val currentFailure: StateFlow<RecorderFailure?> = _currentFailure.asStateFlow()
    private val operationLock = ReentrantLock()
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var failureMonitorJob: Job? = null

    @Volatile
    private var nativeAvailable = false

    @Volatile
    private var activeOutputPath: String = ""

    @Volatile
    private var activeTake = false

    override val micAmplitude: Float
        get() = ifUsable(0f) { currentHandle ->
            nativeBridge.getMicPeak(this, currentHandle).coerceIn(0f, 1f)
        }

    override fun telemetry(afterBucketIndex: Int): RecorderTelemetry? {
        return ifUsable(null) { currentHandle ->
            nativeBridge.getTelemetry(this, currentHandle, afterBucketIndex.coerceAtLeast(0))
        }
    }

    override val writtenDurationMs: Long
        get() = ifUsable(0L) { currentHandle ->
            nativeBridge.getWrittenDurationMs(this, currentHandle).coerceAtLeast(0L)
        }

    init {
        nativeAvailable = nativeLibraryLoader()
        if (nativeAvailable) {
            val createdHandle = runCatching { nativeBridge.create(this) }.getOrDefault(0L)
            if (createdHandle != 0L) {
                handle.set(createdHandle)
                publishStatus(RecorderStatus.IDLE)
            } else {
                nativeAvailable = false
                publishStatus(RecorderStatus.UNAVAILABLE)
            }
        }
    }

    override fun startMicSession(): RecorderOperationResult {
        return operationLock.withLock {
            val unavailable = unavailableFailureOrNull()
            if (unavailable != null) return@withLock unavailable
            if (activeTake) {
                return@withLock handle.use(
                    RecorderOperationResult.failure(RecorderError.Released)
                ) { currentHandle ->
                    nativeFailureIfPresent(
                        currentHandle = currentHandle,
                        defaultError = RecorderError.WriterFileError,
                    ) ?: RecorderOperationResult.Success
                }
            }

            handle.use(RecorderOperationResult.failure(RecorderError.Released)) { currentHandle ->
                if (nativeBridge.startMicSession(this, currentHandle)) {
                    publishStatus(RecorderStatus.MIC_SESSION_ACTIVE)
                    restartFailureMonitor()
                    RecorderOperationResult.Success
                } else {
                    nativeFailure(currentHandle, RecorderError.MicSessionOpenFailed)
                }
            }
        }
    }

    override fun startWriting(request: RecordingRequest): RecorderOperationResult {
        return startWriting(
            outputFile = request.outputFile,
            startOffset = RecorderStartOffset.Milliseconds(request.startOffsetMs),
        )
    }

    override fun startWriting(request: FrameRecordingRequest): RecorderOperationResult {
        return startWriting(
            outputFile = request.outputFile,
            startOffset = RecorderStartOffset.PcmFrames(request.startOffsetFrames),
        )
    }

    private fun startWriting(
        outputFile: File,
        startOffset: RecorderStartOffset,
    ): RecorderOperationResult {
        return operationLock.withLock {
            val unavailable = unavailableFailureOrNull()
            if (unavailable != null) return@withLock unavailable
            if (startOffset.value < 0L || outputFile.path.isBlank()) {
                return@withLock operationFailure(
                    RecorderError.InvalidOutput,
                    "Recorder output path and start offset must be valid",
                )
            }
            if (activeTake) {
                return@withLock operationFailure(
                    RecorderError.InvalidState,
                    "stopWriting must finish the active recording before a new take starts",
                )
            }

            val outputPath = outputFile.absolutePath
            handle.use(RecorderOperationResult.failure(RecorderError.Released)) { currentHandle ->
                val started = when (startOffset) {
                    is RecorderStartOffset.Milliseconds -> nativeBridge.startWriting(
                        this,
                        currentHandle,
                        outputPath,
                        startOffset.value,
                    )
                    is RecorderStartOffset.PcmFrames -> nativeBridge.startWritingAtFrame(
                        this,
                        currentHandle,
                        outputPath,
                        startOffset.value,
                    )
                }
                if (started) {
                    activeOutputPath = outputPath
                    activeTake = true
                    publishState(
                        RecorderState(
                            status = RecorderStatus.WRITING,
                            currentFailure = null,
                        ),
                    )
                    restartFailureMonitor()
                    RecorderOperationResult.Success
                } else {
                    nativeFailure(currentHandle, RecorderError.WriterFileError)
                }
            }
        }
    }

    override fun pauseWriting(): RecorderOperationResult {
        return operationLock.withLock {
            val unavailable = unavailableFailureOrNull()
            if (unavailable != null) return@withLock unavailable
            if (!activeTake || status.value != RecorderStatus.WRITING) {
                return@withLock operationFailure(
                    RecorderError.InvalidState,
                    "pauseWriting requires an active writer",
                )
            }

            handle.use(RecorderOperationResult.failure(RecorderError.Released)) { currentHandle ->
                nativeBridge.pauseWriting(this, currentHandle)
                val failure = nativeFailureIfPresent(currentHandle, RecorderError.WriterFileError)
                if (failure != null) {
                    failure
                } else {
                    publishStatus(RecorderStatus.MIC_SESSION_ACTIVE)
                    RecorderOperationResult.Success
                }
            }
        }
    }

    override fun stopWriting(): RecordingResult {
        return operationLock.withLock {
            val unavailable = unavailableFailureOrNull()
            if (unavailable != null) return@withLock failureRecordingResult(unavailable)
            if (!activeTake) {
                return@withLock failureRecordingResult(
                    operationFailure(
                        RecorderError.InvalidState,
                        "stopWriting requires an active recording",
                    )
                )
            }

            handle.use(failureRecordingResult(RecorderOperationResult.failure(RecorderError.Released))) { currentHandle ->
                val values = nativeBridge.stopWriting(this, currentHandle)
                val outputFile = activeOutputPath.takeIf(String::isNotBlank)?.let(::File)
                activeTake = false
                activeOutputPath = ""
                val failed = values.failed ||
                    values.sampleRate <= 0 ||
                    outputFile == null ||
                    values.acceptedFrames != values.writtenFrames
                if (failed) {
                    val failure = nativeFailure(currentHandle, RecorderError.WriterFileError)
                    failureRecordingResult(
                        failure,
                        file = outputFile,
                        takeDurationMs = values.durationMs,
                        sampleRate = values.sampleRate,
                        acceptedFrames = values.acceptedFrames,
                        writtenFrames = values.writtenFrames,
                    )
                } else {
                    publishStatus(RecorderStatus.MIC_SESSION_ACTIVE)
                    RecordingResult(
                        file = outputFile,
                        durationMs = values.durationMs,
                        sampleRate = values.sampleRate,
                        acceptedFrames = values.acceptedFrames,
                        writtenFrames = values.writtenFrames,
                    )
                }
            }
        }
    }

    override fun releaseMicSession(): RecorderOperationResult {
        return operationLock.withLock {
            val unavailable = unavailableFailureOrNull()
            if (unavailable != null) return@withLock unavailable

            handle.use(RecorderOperationResult.failure(RecorderError.Released)) { currentHandle ->
                nativeBridge.releaseMicSession(this, currentHandle)
                failureMonitorJob?.cancel()
                failureMonitorJob = null
                activeTake = false
                activeOutputPath = ""
                publishStatus(RecorderStatus.IDLE)
                RecorderOperationResult.Success
            }
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        operationLock.withLock {
            failureMonitorJob?.cancel()
            failureMonitorJob = null
            handle.release { currentHandle ->
                nativeBridge.release(this, currentHandle)
            }
            activeTake = false
            activeOutputPath = ""
            publishStatus(RecorderStatus.RELEASED)
        }
        monitorScope.cancel()
    }

    private fun unavailableFailureOrNull(): RecorderOperationResult? {
        if (released.get()) {
            return operationFailure(RecorderError.Released)
        }
        if (!nativeAvailable || !handle.isActive) {
            return operationFailure(
                error = RecorderError.NativeUnavailable,
                status = RecorderStatus.UNAVAILABLE,
            )
        }
        return null
    }

    private fun nativeFailureIfPresent(
        currentHandle: Long,
        defaultError: RecorderError,
    ): RecorderOperationResult? {
        return if (nativeBridge.hasFailed(this, currentHandle)) {
            nativeFailure(currentHandle, defaultError)
        } else {
            null
        }
    }

    private fun nativeFailure(
        currentHandle: Long,
        defaultError: RecorderError,
    ): RecorderOperationResult {
        val snapshot = nativeBridge.getFailureSnapshot(this, currentHandle)
        val error = mapNativeError(
            NativeRecorderFailure.fromCode(snapshot.code),
            defaultError,
        )
        return operationFailure(
            error = error,
            message = snapshot.message.trim().ifBlank { null },
            status = RecorderStatus.FAILED,
        )
    }

    private fun ensureFailureMonitor() {
        if (failureMonitorJob?.isActive == true) return
        failureMonitorJob = monitorScope.launch {
            while (isActive && !released.get()) {
                delay(NATIVE_FAILURE_POLL_INTERVAL_MS)
                val shouldStop = operationLock.withLock {
                    if (
                        released.get() ||
                        status.value !in setOf(
                            RecorderStatus.MIC_SESSION_ACTIVE,
                            RecorderStatus.WRITING,
                        )
                    ) {
                        true
                    } else {
                        handle.use(true) { currentHandle ->
                            if (nativeBridge.hasFailed(this@NativeRecorderSession, currentHandle)) {
                                nativeFailure(
                                    currentHandle = currentHandle,
                                    defaultError = if (activeTake) {
                                        RecorderError.WriterFileError
                                    } else {
                                        RecorderError.MicSessionOpenFailed
                                    },
                                )
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
                if (shouldStop) {
                    failureMonitorStopHook()
                    break
                }
            }
        }
    }

    private fun restartFailureMonitor() {
        failureMonitorJob?.cancel()
        failureMonitorJob = null
        ensureFailureMonitor()
    }

    private fun failureRecordingResult(
        failure: RecorderOperationResult,
        file: File? = activeOutputPath.takeIf(String::isNotBlank)?.let(::File),
        takeDurationMs: Long = 0L,
        sampleRate: Int = 0,
        acceptedFrames: Long = 0L,
        writtenFrames: Long = 0L,
    ): RecordingResult {
        return RecordingResult(
            file = file,
            durationMs = takeDurationMs,
            sampleRate = sampleRate,
            acceptedFrames = acceptedFrames,
            writtenFrames = writtenFrames,
            error = failure.error,
            message = failure.message,
        )
    }

    private fun operationFailure(
        error: RecorderError,
        message: String? = null,
        status: RecorderStatus? = null,
    ): RecorderOperationResult {
        publishState(
            RecorderState(
                status = status ?: _state.value.status,
                currentFailure = RecorderFailure(error = error, message = message),
            ),
        )
        return RecorderOperationResult.failure(error, message)
    }

    private fun publishStatus(status: RecorderStatus) {
        publishState(_state.value.copy(status = status))
    }

    private fun publishState(state: RecorderState) {
        if (state.status == RecorderStatus.FAILED) {
            _currentFailure.value = state.currentFailure
            _status.value = state.status
        } else {
            _status.value = state.status
            _currentFailure.value = state.currentFailure
        }
        _state.value = state
    }

    private fun <T> ifUsable(default: T, block: (Long) -> T): T {
        if (released.get() || !nativeAvailable) return default
        return handle.use(default, block)
    }

    private fun mapNativeError(
        nativeFailure: NativeRecorderFailure,
        defaultError: RecorderError,
    ): RecorderError {
        return when (nativeFailure) {
            NativeRecorderFailure.MIC_SESSION_OPEN_FAILED -> RecorderError.MicSessionOpenFailed
            NativeRecorderFailure.STREAM_DISCONNECTED -> RecorderError.StreamDisconnected
            NativeRecorderFailure.WRITER_OVERFLOW -> RecorderError.WriterOverflow
            NativeRecorderFailure.WRITER_FILE_ERROR -> RecorderError.WriterFileError
            NativeRecorderFailure.INVALID_OUTPUT -> RecorderError.InvalidOutput
            NativeRecorderFailure.NONE,
            NativeRecorderFailure.UNKNOWN -> defaultError
        }
    }

    @JvmName("nativeCreate")
    internal external fun nativeCreate(): Long

    @JvmName("nativeStartMicSession")
    internal external fun nativeStartMicSession(handle: Long): Boolean

    @JvmName("nativeStartWriting")
    internal external fun nativeStartWriting(handle: Long, outputPath: String, startOffsetMs: Long): Boolean

    @JvmName("nativeStartWritingAtFrame")
    internal external fun nativeStartWritingAtFrame(
        handle: Long,
        outputPath: String,
        startOffsetFrames: Long,
    ): Boolean

    @JvmName("nativePauseWriting")
    internal external fun nativePauseWriting(handle: Long)

    @JvmName("nativeStopWriting")
    internal external fun nativeStopWriting(handle: Long): LongArray

    @JvmName("nativeGetMicPeak")
    internal external fun nativeGetMicPeak(handle: Long): Float

    @JvmName("nativeGetTelemetry")
    internal external fun nativeGetTelemetry(
        handle: Long,
        lastConsumedBucketIndex: Int,
    ): RecorderTelemetry?

    @JvmName("nativeGetWrittenDurationMs")
    internal external fun nativeGetWrittenDurationMs(handle: Long): Long

    @JvmName("nativeGetSampleRate")
    internal external fun nativeGetSampleRate(handle: Long): Int

    @JvmName("nativeHasFailed")
    internal external fun nativeHasFailed(handle: Long): Boolean

    @JvmName("nativeGetFailureSnapshot")
    internal external fun nativeGetFailureSnapshot(handle: Long): NativeRecorderFailureSnapshot

    @JvmName("nativeReleaseMicSession")
    internal external fun nativeReleaseMicSession(handle: Long)

    @JvmName("nativeRelease")
    internal external fun nativeRelease(handle: Long)

    private companion object {
        const val NATIVE_FAILURE_POLL_INTERVAL_MS = 50L
    }
}
