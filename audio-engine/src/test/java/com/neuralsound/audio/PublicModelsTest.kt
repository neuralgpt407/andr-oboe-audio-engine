package com.neuralsound.audio

import android.net.Uri
import com.neuralsound.audio.player.ChannelGain
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackSettings
import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.recorder.TakeRequest
import com.neuralsound.audio.recorder.WaveformBuckets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PublicModelsTest {
    @Test
    fun pitchTempoClampsToTheNativeRanges() {
        val clamped = PitchTempo(semitones = 14, tempo = 2.5f).clamped()

        assertEquals(PitchTempo.MAX_SEMITONES, clamped.semitones)
        assertEquals(PitchTempo.MAX_TEMPO, clamped.tempo)
        assertEquals(PitchTempo(semitones = -10, tempo = 0.5f), PitchTempo(-30, 0.1f).clamped())
        assertEquals(2f, PitchTempo.MAX_TEMPO)
        assertEquals(0.5f, PitchTempo.MIN_TEMPO)
    }

    @Test
    fun pitchTempoRejectsNonPositiveTempo() {
        assertThrows(IllegalArgumentException::class.java) { PitchTempo(tempo = 0f) }
        assertThrows(IllegalArgumentException::class.java) { PitchTempo(tempo = Float.NaN) }
    }

    @Test
    fun trackSettingsClampVolumeOffsetAndBoost() {
        val clamped = TrackSettings(volume = 1.4f, offsetMs = -20_000L, boostDb = 25).clamped()

        assertEquals(1f, clamped.volume)
        assertEquals(TrackSettings.MIN_OFFSET_MS, clamped.offsetMs)
        assertEquals(TrackSettings.MAX_BOOST_DB, clamped.boostDb)
        assertEquals(0, TrackSettings(boostDb = -3).clamped().boostDb)
    }

    @Test
    fun trackSettingsCoverTheKaraokeManualSyncRange() {
        assertTrue(TrackSettings.MIN_OFFSET_MS <= -2_000L)
        assertTrue(TrackSettings.MAX_OFFSET_MS >= 2_000L)
    }

    @Test
    fun balanceAttenuatesTheOppositeChannel() {
        assertEquals(ChannelGain.Center, ChannelGain.balance(0f))
        assertEquals(ChannelGain(left = 1f, right = 0.5f), ChannelGain.balance(-0.5f))
        assertEquals(ChannelGain(left = 0.75f, right = 1f), ChannelGain.balance(0.25f))
        assertEquals(ChannelGain.Right, ChannelGain.balance(5f))
    }

    @Test
    fun channelGainRejectsOutOfRangeValues() {
        assertThrows(IllegalArgumentException::class.java) { ChannelGain(left = 2.5f) }
        assertThrows(IllegalArgumentException::class.java) { ChannelGain(right = -0.1f) }
    }

    @Test
    fun playbackRangeValidatesAndClamps() {
        assertThrows(IllegalArgumentException::class.java) { PlaybackRange(10L, 10L) }
        assertEquals(PlaybackRange(500L, 1_000L), PlaybackRange(500L, 4_000L).clampTo(1_000L))
        assertEquals(PlaybackRange(999L, 1_000L), PlaybackRange(5_000L, 6_000L).clampTo(1_000L))
    }

    @Test
    fun playerSourceRequiresUniqueTracks() {
        val uri = Uri.parse("content://test/a")
        assertThrows(IllegalArgumentException::class.java) { PlayerSource(tracks = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            PlayerSource(tracks = listOf(Track(TrackId("a"), uri), Track(TrackId("a"), uri)))
        }
    }

    @Test
    fun audioResultHelpers() {
        val ok: AudioResult<Int> = AudioResult.Ok(3)
        val err: AudioResult<Int> = AudioResult.Err(AudioError.Released)

        assertTrue(ok.isOk)
        assertEquals(3, ok.getOrNull())
        assertNull(ok.errorOrNull())
        assertFalse(err.isOk)
        assertEquals(AudioError.Released, err.errorOrNull())
        assertTrue(AudioResult.Done.isOk)
    }

    @Test
    fun errorsNeverCarryUris() {
        val error = AudioError.TrackFailed(TrackId("vocal"), TrackFailureReason.UNREADABLE_SOURCE)
        assertFalse(error.toString().contains("content://"))
    }

    @Test
    fun recorderFrameConversionsUse44k1() {
        assertEquals(1_323_000L, Recorder.framesForMs(30_000L))
        assertEquals(30_000L, Recorder.msForFrames(1_323_000L))
    }

    @Test
    fun takeRequestDefaultsToNoSaturation() {
        assertFalse(TakeRequest(File("take.wav")).saturation)
        assertThrows(IllegalArgumentException::class.java) { TakeRequest(File("take.wav"), startFrame = -1L) }
    }

    @Test
    fun waveformBucketsCompareByContent() {
        assertEquals(WaveformBuckets(2, floatArrayOf(0.1f)), WaveformBuckets(2, floatArrayOf(0.1f)))
    }
}
