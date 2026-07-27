package com.neuralsound.audio.internal

import com.neuralsound.audio.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixerTrackFormatPolicyTest {
    @Test
    fun acceptsPositiveChannelMetadataAcrossCanonicalAnd48kSources() {
        val problem = MixerTrackFormatPolicy.findProblem(
            listOf(
                TrackId("vocal") to DecodedAudioFormat(44_100, 1),
                TrackId("instrumental") to DecodedAudioFormat(48_000, 2),
                TrackId("surround") to DecodedAudioFormat(48_000, 6),
            )
        )

        assertNull(problem)
    }

    @Test
    fun rejectsMissingOrNonPositiveMetadataAgainstCanonicalClock() {
        val missingMetadata = MixerTrackFormatPolicy.findProblem(
            listOf(TrackId("unknown") to DecodedAudioFormat(null, null))
        )
        val invalidRate = MixerTrackFormatPolicy.findProblem(
            listOf(TrackId("invalid-rate") to DecodedAudioFormat(0, 2))
        )
        val invalidChannels = MixerTrackFormatPolicy.findProblem(
            listOf(TrackId("invalid-channels") to DecodedAudioFormat(48_000, 0))
        )

        assertEquals(TrackId("unknown"), missingMetadata?.trackId)
        assertEquals(44_100, missingMetadata?.requiredSampleRate)
        assertEquals(TrackId("invalid-rate"), invalidRate?.trackId)
        assertEquals(TrackId("invalid-channels"), invalidChannels?.trackId)
    }
}
