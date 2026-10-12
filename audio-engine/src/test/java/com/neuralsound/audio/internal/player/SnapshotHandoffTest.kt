package com.neuralsound.audio.internal.player

import android.net.Uri
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.player.ChannelGain
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** FR-15 / FR-16 / S3-AS2: separation preview → player hand-off keeps position, range, effects and mixes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SnapshotHandoffTest {
    private fun TestScope.player(controller: DefaultPlayerTest.FakeMixerController): DefaultPlayer {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DefaultPlayer(controller, PlayerOptions(maxTracks = 10), true, dispatcher, dispatcher)
    }

    private val stems = (0 until 4).map { Track(TrackId("stem$it"), Uri.parse("content://stems/$it")) }

    @Test
    fun appendedStemsAndEveryAdjustmentSurviveTheHandOff() = runTest {
        val preview = player(DefaultPlayerTest.FakeMixerController())
        preview.load(PlayerSource(tracks = stems.take(1), autoPlay = true))
        stems.drop(1).forEach { assertEquals(AudioResult.Done, preview.appendTrack(it)) }
        preview.updateTrack(TrackId("stem1")) { it.copy(volume = 0.3f, muted = true) }
        preview.updateTrack(TrackId("stem2")) { it.copy(balance = ChannelGain.balance(-0.4f), offsetMs = 120, boostDb = 6) }
        preview.setRange(PlaybackRange(2_000, 8_000), loop = true)
        preview.setPitchTempo(PitchTempo(semitones = -2, tempo = 1.25f))
        preview.seekTo(4_200)
        advanceUntilIdle()

        val snapshot = preview.detach()!!
        assertEquals(PlayerStatus.RELEASED, preview.state.value.status)
        assertEquals(4_200L, snapshot.positionMs)
        assertTrue(snapshot.wasPlaying)

        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        assertEquals(AudioResult.Done, player.restore(snapshot))
        advanceUntilIdle()

        val state = player.state.value
        assertEquals(stems.map { it.id }, state.tracks.keys.toList())
        assertEquals(TrackSettings(volume = 0.3f, muted = true), state.tracks.getValue(TrackId("stem1")).settings)
        assertEquals(
            TrackSettings(balance = ChannelGain.balance(-0.4f), offsetMs = 120, boostDb = 6),
            state.tracks.getValue(TrackId("stem2")).settings,
        )
        assertEquals(PlaybackRange(2_000, 8_000), state.range)
        assertTrue(state.loop)
        assertEquals(PitchTempo(semitones = -2, tempo = 1.25f), state.pitchTempo)
        assertEquals(4_200L, controller.preparedStartMs)
        assertEquals(6, controller.boosts[TrackId("stem2")])
        assertTrue(controller.snapshotTracks().getValue(TrackId("stem1")).mix.muted)
        assertEquals(PlayerStatus.PLAYING, state.status)
        player.release()
    }

    @Test
    fun pausedSessionsHandOffPaused() = runTest {
        val preview = player(DefaultPlayerTest.FakeMixerController())
        preview.load(PlayerSource(tracks = stems))
        preview.seekTo(1_000)
        advanceUntilIdle()

        val snapshot = preview.detach()!!
        assertFalse(snapshot.wasPlaying)
        val player = player(DefaultPlayerTest.FakeMixerController())
        player.restore(snapshot)
        advanceUntilIdle()
        assertEquals(PlayerStatus.READY, player.state.value.status)
        assertEquals(1_000L, player.state.value.positionMs)
        player.release()
    }
}
