package com.neuralsound.audio.recorder

import com.neuralsound.audio.AudioError
import java.io.File

data class RecorderState(
    val status: RecorderStatus = RecorderStatus.IDLE,
    val health: RecorderHealth = RecorderHealth(),
    /** Set while [status] is [RecorderStatus.FAILED]. */
    val error: AudioError? = null,
) {
    val isMicOpen: Boolean
        get() = status == RecorderStatus.MIC_OPEN ||
            status == RecorderStatus.RECORDING ||
            status == RecorderStatus.PAUSED
}

enum class RecorderStatus {
    UNAVAILABLE,
    IDLE,
    MIC_OPEN,
    RECORDING,
    PAUSED,
    FAILED,
    RELEASED,
}

data class RecorderHealth(
    val streamOpen: Boolean = false,
    val writerAlive: Boolean = false,
    /** Input sample rate negotiated with the device; takes are resampled to 44.1 kHz. */
    val deviceSampleRate: Int? = null,
    val overflowCount: Long = 0L,
)

data class MicMeter(
    /** Peak absolute sample (0..1) over the last meter window. */
    val peak: Float = 0f,
    val rms: Float = 0f,
    val clipping: Boolean = false,
)

sealed interface RecorderEvent {
    data class TakeFinished(val result: TakeResult) : RecorderEvent

    /** The input device went away; the active take was finalized and stopped. */
    data class InputLost(val partial: TakeResult?) : RecorderEvent

    data class Failed(val error: AudioError) : RecorderEvent
}

data class TakeRequest(
    val file: File,
    /** Frame (at 44.1 kHz) in the file where writing starts. */
    val startFrame: Long = 0L,
    val mode: TakeMode = TakeMode.NEW,
    /** Applies soft saturation to the input; off by default. */
    val saturation: Boolean = false,
) {
    init {
        require(startFrame >= 0L) { "Start frame must not be negative" }
        require(file.path.isNotBlank()) { "Take file path must not be blank" }
    }
}

enum class TakeMode {
    /** Create or truncate the file. */
    NEW,

    /** Overwrite an existing take from [TakeRequest.startFrame], crossfading at the punch-in point. */
    PUNCH_IN,
}

data class TakeResult(
    val file: File,
    val startFrame: Long,
    /** Frames written by this take. */
    val frames: Long,
    /** Total length of the file after this take. */
    val fileDurationMs: Long,
)

/** RMS levels for consecutive 32 ms buckets starting at [firstIndex]. */
class WaveformBuckets(
    val firstIndex: Int,
    val rms: FloatArray,
) {
    override fun equals(other: Any?): Boolean =
        other is WaveformBuckets && firstIndex == other.firstIndex && rms.contentEquals(other.rms)

    override fun hashCode(): Int = 31 * firstIndex + rms.contentHashCode()

    override fun toString(): String = "WaveformBuckets(firstIndex=$firstIndex, count=${rms.size})"

    companion object {
        const val BUCKET_MS = 32
    }
}
