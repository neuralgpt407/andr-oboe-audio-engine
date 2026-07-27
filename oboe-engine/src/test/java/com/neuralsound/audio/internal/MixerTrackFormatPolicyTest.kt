package com.neuralsound.audio.internal

import com.neuralsound.audio.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MixerTrackFormatPolicyTest {
    @Test
    fun acceptsMonoAndStereoTracksAtTheSameRate() {
        val problem = MixerTrackFormatPolicy.findProblem(
            listOf(
                TrackId("vocal") to DecodedAudioFormat(44_100, 1),
                TrackId("instrumental") to DecodedAudioFormat(44_100, 2),
            )
        )

        assertNull(problem)
    }

    @Test
    fun rejectsTrackWhoseRateDiffersFromTheMixerRate() {
        val problem = MixerTrackFormatPolicy.findProblem(
            listOf(
                TrackId("vocal") to DecodedAudioFormat(44_100, 2),
                TrackId("instrumental") to DecodedAudioFormat(48_000, 2),
            )
        )

        assertEquals(TrackId("instrumental"), problem?.trackId)
        assertEquals(44_100, problem?.requiredSampleRate)
    }

    @Test
    fun rejectsMissingMetadataAndMultichannelAudio() {
        val missingMetadata = MixerTrackFormatPolicy.findProblem(
            listOf(TrackId("unknown") to DecodedAudioFormat(null, null))
        )
        val multichannel = MixerTrackFormatPolicy.findProblem(
            listOf(TrackId("surround") to DecodedAudioFormat(48_000, 6))
        )

        assertEquals(TrackId("unknown"), missingMetadata?.trackId)
        assertEquals(TrackId("surround"), multichannel?.trackId)
    }
}
