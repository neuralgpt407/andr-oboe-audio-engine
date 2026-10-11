package com.neuralsound.audio.internal

import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.TrackId

sealed interface MixerPreparationResult {
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

internal enum class NativeMixerFailureCode(val value: Int) {
    NONE(0),
    SOURCE_UNAVAILABLE(1),
    INVALID_FORMAT(2),
    DECODER_FAILURE(3),
    RESAMPLER_FAILURE(4),
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
    val uriString: String,
    val format: DecodedAudioFormat,
)

internal object MixerNativeFailurePolicy {
    fun toPublicFailure(
        failure: NativeMixerFailureSnapshot,
        tracks: List<MixerTrackFailureContext>,
        fallbackOperation: String = "prepare",
    ): AudioFailure {
        val track = tracks.getOrNull(failure.trackIndex)
            ?: return AudioFailure.NativeOperationFailed(
                operation = fallbackOperation,
                detail = "Native mixer failed without a track context",
            )
        return when (NativeMixerFailureCode.entries.firstOrNull { it.value == failure.code }) {
            NativeMixerFailureCode.SOURCE_UNAVAILABLE -> AudioFailure.SourceUnavailable(
                track.uriString
            )

            NativeMixerFailureCode.INVALID_FORMAT -> {
                AudioFailure.UnsupportedTrackFormat(
                    trackId = track.id,
                    sampleRate = track.format.sampleRate,
                    channelCount = track.format.channelCount,
                    requiredSampleRate = MixerTrackFormatPolicy.CANONICAL_SAMPLE_RATE,
                )
            }

            NativeMixerFailureCode.DECODER_FAILURE -> AudioFailure.NativeOperationFailed(
                operation = "decodeTrack",
                detail = "Track ${track.id} could not be decoded",
            )

            NativeMixerFailureCode.RESAMPLER_FAILURE -> AudioFailure.NativeOperationFailed(
                operation = "normalizeTrack",
                detail = "Track ${track.id} could not be normalized to " +
                    "${MixerTrackFormatPolicy.CANONICAL_SAMPLE_RATE} Hz",
            )

            NativeMixerFailureCode.NONE,
            null -> AudioFailure.NativeOperationFailed(
                operation = fallbackOperation,
                detail = "Native mixer initialization failed",
            )
        }
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
