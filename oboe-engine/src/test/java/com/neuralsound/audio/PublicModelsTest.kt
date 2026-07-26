package com.neuralsound.audio

import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublicModelsTest {
    @Test
    fun `track id rejects blank values`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackId(" ")
        }
    }

    @Test
    fun `mixer request rejects duplicate track ids`() {
        val trackId = TrackId("vocal")
        val tracks = listOf(
            MixerTrack(trackId, Uri.EMPTY),
            MixerTrack(trackId, Uri.EMPTY),
        )

        assertThrows(IllegalArgumentException::class.java) {
            MixerRequest(tracks)
        }
    }

    @Test
    fun `playback range clamps to the media duration`() {
        val range = PlaybackRange(startMs = 1_000L, endMs = 8_000L)

        assertEquals(
            PlaybackRange(startMs = 1_000L, endMs = 5_000L),
            range.clampTo(5_000L),
        )
    }

    @Test
    fun `track mix rejects volume outside the normalized range`() {
        assertThrows(IllegalArgumentException::class.java) {
            TrackMix(volume = 1.1f)
        }
    }

    @Test
    fun `recording result requires a file and valid sample rate`() {
        val incomplete = RecordingResult(
            file = null,
            durationMs = 0L,
            sampleRate = 0,
            acceptedFrames = 0L,
            writtenFrames = 0L,
        )
        val complete = incomplete.copy(
            file = File("take.wav"),
            sampleRate = 44_100,
        )

        assertEquals(false, incomplete.isSuccess)
        assertEquals(true, complete.isSuccess)
    }
}
