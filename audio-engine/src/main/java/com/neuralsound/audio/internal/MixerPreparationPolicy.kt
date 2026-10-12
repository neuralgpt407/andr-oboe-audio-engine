package com.neuralsound.audio.internal

import com.neuralsound.audio.internal.legacy.AudioFailure
import com.neuralsound.audio.TrackFailureReason
import com.neuralsound.audio.TrackId

internal sealed interface MixerPreparationResult {
    data object Success : MixerPreparationResult

    data class Failure(
        val message: String,
        val failure: AudioFailure? = null,
    ) : MixerPreparationResult
}

internal sealed interface MixerAppendResult {
    data object Success : MixerAppendResult
    data class Failure(val failure: AudioFailure) : MixerAppendResult
}

internal enum class NativeMixerFailureCode(val value: Int, val reason: TrackFailureReason?) {
    NONE(0, null),
    SOURCE_UNAVAILABLE(1, TrackFailureReason.UNREADABLE_SOURCE),
    INVALID_FORMAT(2, TrackFailureReason.CORRUPT_MEDIA),
    DECODER_FAILURE(3, TrackFailureReason.DECODE_FAILURE),
    RESAMPLER_FAILURE(4, TrackFailureReason.DECODE_FAILURE),
    NO_AUDIO_TRACK(5, TrackFailureReason.NO_AUDIO_TRACK),
    UNSUPPORTED_CODEC(6, TrackFailureReason.UNSUPPORTED_CODEC),
}

internal data class NativeMixerFailureSnapshot(
    val code: Int,
    val trackIndex: Int,
) {
    val isFailure: Boolean
        get() = code != NativeMixerFailureCode.NONE.value

    companion object {
        fun decode(encoded: Long): NativeMixerFailureSnapshot {
            return NativeMixerFailureSnapshot(
                code = (encoded ushr Int.SIZE_BITS).toInt(),
                trackIndex = encoded.toInt(),
            )
        }
    }
}

internal data class MixerTrackFailureContext(
    val id: TrackId,
    val format: DecodedAudioFormat,
)

internal object MixerNativeFailurePolicy {
    /** Maps a native failure snapshot to a typed per-track failure without any media URI. */
    fun toPublicFailure(
        failure: NativeMixerFailureSnapshot,
        tracks: List<MixerTrackFailureContext>,
        fallbackOperation: String = "prepare",
    ): AudioFailure {
        val track = tracks.getOrNull(failure.trackIndex)
            ?: return AudioFailure.NativeOperationFailed(operation = fallbackOperation)
        val reason = NativeMixerFailureCode.entries.firstOrNull { it.value == failure.code }?.reason
            ?: return AudioFailure.NativeOperationFailed(operation = fallbackOperation)
        return AudioFailure.TrackFailed(track.id, reason)
    }
}

internal object MixerPreparationPolicy {
    fun result(
        initialized: Boolean,
        offsetsApplied: Boolean,
        seekCompleted: Boolean,
    ): MixerPreparationResult {
        return when {
            !initialized -> MixerPreparationResult.Failure("native mixer was not initialized")
            !offsetsApplied -> MixerPreparationResult.Failure("track offsets were not applied")
            !seekCompleted -> MixerPreparationResult.Failure("requested seek did not complete")
            else -> MixerPreparationResult.Success
        }
    }
}

internal object TrackReadOffsetPolicy {
    fun sourcePositionMs(timelinePositionMs: Long, offsetMs: Long): Long? {
        val sourcePositionMs = when {
            offsetMs > 0L && timelinePositionMs > Long.MAX_VALUE - offsetMs -> Long.MAX_VALUE
            offsetMs < 0L && timelinePositionMs < Long.MIN_VALUE - offsetMs -> Long.MIN_VALUE
            else -> timelinePositionMs + offsetMs
        }
        return sourcePositionMs.takeIf { it >= 0L }
    }
}
