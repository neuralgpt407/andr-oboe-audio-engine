package com.neuralsound.audio.internal

import com.neuralsound.audio.RecorderError
import com.neuralsound.audio.RecorderOperationResult
import com.neuralsound.audio.RecorderSession
import com.neuralsound.audio.RecorderStatus
import com.neuralsound.audio.RecorderTelemetry
import com.neuralsound.audio.RecordingRequest
import com.neuralsound.audio.RecordingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.jvm.JvmName

internal class NativeRecorderSession internal constructor(
    private val nativeBridge: OboeRecorderNativeBridge,
    private val nativeLibraryLoader: () -> Boolean,
) : RecorderSession {
    constructor() : this(OboeRecorderJniBridge, OboeRecorderNativeLibrary::isAvailable)

    private val handle = EngineHandle()
    private val released = AtomicBoolean(false)
    private val _status = MutableStateFlow(RecorderStatus.UNAVAILABLE)
    override val status: StateFlow<RecorderStatus> = _status.asStateFlow()
    private val operationLock = ReentrantLock()

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
                _status.value = RecorderStatus.IDLE
            } else {
                nativeAvailable = false
                _status.value = RecorderStatus.UNAVAILABLE
            }
        }
    }

    override fun startMicSession(): RecorderOperationResult {
        return operationLock.withLock {
            val unavailable = unavailableFailureOrNull()
            if (unavailable != null) return@withLock unavailable

            handle.use(RecorderOperationResult.failure(RecorderError.Released)) { currentHandle ->
                if (nativeBridge.startMicSession(this, currentHandle)) {
                    _status.value = RecorderStatus.MIC_SESSION_ACTIVE
                    RecorderOperationResult.Success
                } else {
                    nativeFailure(currentHandle, RecorderError.MicSessionOpenFailed)
                }
            }
        }
    }

    override fun startWriting(request: RecordingRequest): RecorderOperationResult {
        return startWriting(request.outputFile, request.startOffsetMs)
    }

    private fun startWriting(outputFile: File, startOffsetMs: Long): RecorderOperationResult {
        return operationLock.withLock {
            val unavailable = unavailableFailureOrNull()
            if (unavailable != null) return@withLock unavailable
            if (startOffsetMs < 0L || outputFile.path.isBlank()) {
                return@withLock RecorderOperationResult.failure(
                    RecorderError.InvalidOutput,
                    "Recorder output path and start offset must be valid",
                )
            }

            val outputPath = outputFile.absolutePath
            handle.use(RecorderOperationResult.failure(RecorderError.Released)) { currentHandle ->
                activeTake = false
                activeOutputPath = ""
                if (nativeBridge.startWriting(this, currentHandle, outputPath, startOffsetMs)) {
                    activeOutputPath = outputPath
                    activeTake = true
                    _status.value = RecorderStatus.WRITING
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
            if (!activeTake || _status.value != RecorderStatus.WRITING) {
                return@withLock RecorderOperationResult.failure(
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
                    _status.value = RecorderStatus.MIC_SESSION_ACTIVE
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
                    RecorderOperationResult.failure(
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
                    _status.value = RecorderStatus.MIC_SESSION_ACTIVE
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
                activeTake = false
                activeOutputPath = ""
                _status.value = RecorderStatus.IDLE
                RecorderOperationResult.Success
            }
        }
    }

    override fun close() {
        if (!released.compareAndSet(false, true)) return
        operationLock.withLock {
            handle.release { currentHandle ->
                nativeBridge.release(this, currentHandle)
            }
            activeTake = false
            activeOutputPath = ""
            _status.value = RecorderStatus.RELEASED
        }
    }

    private fun unavailableFailureOrNull(): RecorderOperationResult? {
        if (released.get()) {
            return RecorderOperationResult.failure(RecorderError.Released)
        }
        if (!nativeAvailable || !handle.isActive) {
            _status.value = RecorderStatus.UNAVAILABLE
            return RecorderOperationResult.failure(RecorderError.NativeUnavailable)
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
        val message = nativeBridge.getLastError(this, currentHandle).trim()
        val error = mapNativeError(nativeBridge.getLastFailure(this, currentHandle), defaultError)
        _status.value = RecorderStatus.FAILED
        return RecorderOperationResult.failure(error, message.ifBlank { null })
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

    @JvmName("nativeGetLastErrorCode")
    internal external fun nativeGetLastErrorCode(handle: Long): Int

    @JvmName("nativeGetLastError")
    internal external fun nativeGetLastError(handle: Long): String

    @JvmName("nativeReleaseMicSession")
    internal external fun nativeReleaseMicSession(handle: Long)

    @JvmName("nativeRelease")
    internal external fun nativeRelease(handle: Long)
}
