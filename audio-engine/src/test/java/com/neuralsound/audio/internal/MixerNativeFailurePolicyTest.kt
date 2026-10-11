package com.neuralsound.audio.internal

import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.TrackId
import org.junit.Assert.assertEquals
import org.junit.Test

class MixerNativeFailurePolicyTest {
    private val original = TrackId("original")
    private val tracks = listOf(
        MixerTrackFailureContext(
            id = original,
            uriString = "file:///original.wav",
            format = DecodedAudioFormat(48_000, 6),
        )
    )

    @Test
    fun packedNativeFailureKeepsCodeAndTrackIndexTogether() {
        val encoded =
            (NativeMixerFailureCode.RESAMPLER_FAILURE.value.toLong() shl Int.SIZE_BITS) or
                7L

        assertEquals(
            NativeMixerFailureSnapshot(
                code = NativeMixerFailureCode.RESAMPLER_FAILURE.value,
                trackIndex = 7,
            ),
            NativeMixerFailureSnapshot.decode(encoded),
        )
        assertEquals(
            NativeMixerFailureSnapshot(code = 0, trackIndex = -1),
            NativeMixerFailureSnapshot.decode(0x00000000FFFFFFFFL),
        )
    }

    @Test
    fun nativeSourceAndFormatFailuresRemainTypedAtThePublicBoundary() {
        assertEquals(
            AudioFailure.SourceUnavailable("file:///original.wav"),
            MixerNativeFailurePolicy.toPublicFailure(
                failure = NativeMixerFailureSnapshot(
                    NativeMixerFailureCode.SOURCE_UNAVAILABLE.value,
                    0,
                ),
                tracks = tracks,
            ),
        )
        assertEquals(
            AudioFailure.UnsupportedTrackFormat(
                trackId = original,
                sampleRate = 48_000,
                channelCount = 6,
                requiredSampleRate = 44_100,
            ),
            MixerNativeFailurePolicy.toPublicFailure(
                failure = NativeMixerFailureSnapshot(
                    NativeMixerFailureCode.INVALID_FORMAT.value,
                    0,
                ),
                tracks = tracks,
            ),
        )
    }

    @Test
    fun nativeDecoderAndResamplerFailuresUseDistinctPublicOperations() {
        assertEquals(
            AudioFailure.NativeOperationFailed(
                operation = "decodeTrack",
                detail = "Track original could not be decoded",
            ),
            MixerNativeFailurePolicy.toPublicFailure(
                failure = NativeMixerFailureSnapshot(
                    NativeMixerFailureCode.DECODER_FAILURE.value,
                    0,
                ),
                tracks = tracks,
            ),
        )
        assertEquals(
            AudioFailure.NativeOperationFailed(
                operation = "normalizeTrack",
                detail = "Track original could not be normalized to 44100 Hz",
            ),
            MixerNativeFailurePolicy.toPublicFailure(
                failure = NativeMixerFailureSnapshot(
                    NativeMixerFailureCode.RESAMPLER_FAILURE.value,
                    0,
                ),
                tracks = tracks,
            ),
        )
    }
}
