package com.neuralsound.audio.internal

import com.neuralsound.audio.TrackFailureReason
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.internal.legacy.AudioFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-17 / S3-AS3: native failures name the failing track and a reason, never its URI. */
class MixerNativeFailurePolicyTest {
    private val original = TrackId("original")
    private val vocal = TrackId("vocal")
    private val tracks = listOf(
        MixerTrackFailureContext(id = original, format = DecodedAudioFormat(48_000, 6)),
        MixerTrackFailureContext(id = vocal, format = DecodedAudioFormat(44_100, 1)),
    )

    @Test
    fun packedNativeFailureKeepsCodeAndTrackIndexTogether() {
        val encoded = (NativeMixerFailureCode.RESAMPLER_FAILURE.value.toLong() shl Int.SIZE_BITS) or 7L

        assertEquals(
            NativeMixerFailureSnapshot(code = NativeMixerFailureCode.RESAMPLER_FAILURE.value, trackIndex = 7),
            NativeMixerFailureSnapshot.decode(encoded),
        )
        assertEquals(
            NativeMixerFailureSnapshot(code = 0, trackIndex = -1),
            NativeMixerFailureSnapshot.decode(0x00000000FFFFFFFFL),
        )
    }

    @Test
    fun everyNativeKindMapsToATypedReasonForTheFailingTrack() {
        val expected = mapOf(
            NativeMixerFailureCode.SOURCE_UNAVAILABLE to TrackFailureReason.UNREADABLE_SOURCE,
            NativeMixerFailureCode.INVALID_FORMAT to TrackFailureReason.CORRUPT_MEDIA,
            NativeMixerFailureCode.DECODER_FAILURE to TrackFailureReason.DECODE_FAILURE,
            NativeMixerFailureCode.RESAMPLER_FAILURE to TrackFailureReason.DECODE_FAILURE,
            NativeMixerFailureCode.NO_AUDIO_TRACK to TrackFailureReason.NO_AUDIO_TRACK,
            NativeMixerFailureCode.UNSUPPORTED_CODEC to TrackFailureReason.UNSUPPORTED_CODEC,
        )
        expected.forEach { (code, reason) ->
            assertEquals(
                code.name,
                AudioFailure.TrackFailed(vocal, reason),
                MixerNativeFailurePolicy.toPublicFailure(NativeMixerFailureSnapshot(code.value, 1), tracks),
            )
        }
    }

    @Test
    fun failuresWithoutATrackOrKindStayNativeOperationFailures() {
        assertEquals(
            AudioFailure.NativeOperationFailed("prepare"),
            MixerNativeFailurePolicy.toPublicFailure(NativeMixerFailureSnapshot(NativeMixerFailureCode.DECODER_FAILURE.value, 9), tracks),
        )
        assertEquals(
            AudioFailure.NativeOperationFailed("appendTrack"),
            MixerNativeFailurePolicy.toPublicFailure(NativeMixerFailureSnapshot(0, 0), tracks, "appendTrack"),
        )
    }

    @Test
    fun nativeCodesMatchTheDecoderEnum() {
        // engine/NativeAudioDecoder.h NativeAudioDecoderFailure values.
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), NativeMixerFailureCode.entries.map { it.value })
    }
}
