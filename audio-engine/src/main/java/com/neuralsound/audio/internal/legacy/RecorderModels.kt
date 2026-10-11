package com.neuralsound.audio.internal.legacy

import com.neuralsound.audio.TrackId

import java.io.File

internal data class RecordingRequest(
    val outputFile: File,
    val startOffsetMs: Long = 0L,
) {
    init {
        require(startOffsetMs >= 0L) { "Recording start offset must not be negative" }
        require(outputFile.path.isNotBlank()) { "Recording output path must not be blank" }
    }
}

internal data class FrameRecordingRequest(
    val outputFile: File,
    val startOffsetFrames: Long = 0L,
) {
    init {
        require(startOffsetFrames >= 0L) { "Recording start frame must not be negative" }
        require(outputFile.path.isNotBlank()) { "Recording output path must not be blank" }
    }
}

internal enum class RecorderStatus {
    UNAVAILABLE,
    IDLE,
    MIC_SESSION_ACTIVE,
    WRITING,
    FAILED,
    RELEASED,
}

internal sealed interface RecorderError {
    data object NativeUnavailable : RecorderError
    data object MicSessionOpenFailed : RecorderError
    data object StreamDisconnected : RecorderError
    data object WriterOverflow : RecorderError
    data object WriterFileError : RecorderError
    data object Released : RecorderError
    data object InvalidOutput : RecorderError
    data object InvalidState : RecorderError
}

internal data class RecorderFailure(
    val error: RecorderError,
    val message: String? = null,
)

internal data class RecorderState(
    val status: RecorderStatus,
    val currentFailure: RecorderFailure? = null,
) {
    init {
        require(status != RecorderStatus.FAILED || currentFailure != null) {
            "A failed recorder state must include its typed failure"
        }
    }
}

internal data class RecorderOperationResult(
    val error: RecorderError? = null,
    val message: String? = null,
) {
    val isSuccess: Boolean
        get() = error == null

    companion object {
        val Success = RecorderOperationResult()

        fun failure(error: RecorderError, message: String? = null): RecorderOperationResult {
            return RecorderOperationResult(error = error, message = message)
        }
    }
}

internal data class RecordingResult(
    val file: File?,
    val durationMs: Long,
    val sampleRate: Int,
    val acceptedFrames: Long,
    val writtenFrames: Long,
    val error: RecorderError? = null,
    val message: String? = null,
) {
    val isSuccess: Boolean
        get() = error == null &&
            file != null &&
            sampleRate > 0 &&
            acceptedFrames == writtenFrames
}

internal data class RecorderTelemetry(
    val takeId: Long,
    val sampleRate: Int,
    val acceptedFrames: Long,
    val writtenFrames: Long,
    val rawPeak: Float,
    val firstBucketIndex: Int,
    val completedBucketRms: FloatArray,
    val partialBucketRms: Float,
    val partialBucketFrames: Int,
)
