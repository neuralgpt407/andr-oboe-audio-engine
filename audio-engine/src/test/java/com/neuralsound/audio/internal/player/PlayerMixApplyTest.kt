package com.neuralsound.audio.internal.player

import android.net.Uri
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.player.ChannelGain
import com.neuralsound.audio.player.MixApplyResult
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerMix
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackMix
import com.neuralsound.audio.player.TrackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerMixApplyTest {
    private fun id(name: String) = TrackId(name)
    private fun track(name: String, settings: TrackSettings = TrackSettings()) =
        Track(id(name), Uri.parse("content://media/$name"), settings)

    private fun TestScope.player(controller: DefaultPlayerTest.FakeMixerController): DefaultPlayer {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DefaultPlayer(controller, PlayerOptions(), nativeAvailable = true, dispatcher, dispatcher)
    }

    private fun DefaultPlayerTest.FakeMixerController.audible(name: String): Boolean =
        !snapshotTracks().getValue(id(name)).mix.muted

    private val four = listOf(track("a"), track("b"), track("c"), track("d"))

    @Test
    fun mixRoundTripsThroughTextAndAppliesToAnotherPlayer() = runTest {
        val c1 = DefaultPlayerTest.FakeMixerController()
        val p1 = player(c1)
        p1.load(PlayerSource(tracks = listOf(track("a"), track("b"))))
        p1.updateTrack(id("a")) {
            it.copy(volume = 0.4f, balance = ChannelGain.balance(-0.5f), offsetMs = 120L, boostDb = 6)
        }
        p1.updateTrack(id("b")) { it.copy(muted = true) }
        p1.setSolo(id("a"), true)
        p1.setPitchTempo(PitchTempo(semitones = 3, tempo = 1.25f))
        p1.setRange(PlaybackRange(1_000L, 9_000L), loop = true)
        advanceUntilIdle()

        val mix = p1.mix.value
        assertEquals(listOf(id("a"), id("b")), mix.tracks.keys.toList())
        assertTrue(mix.tracks.getValue(id("a")).solo)
        assertTrue(mix.tracks.getValue(id("b")).muted)
        assertEquals(PitchTempo(3, 1.25f), mix.pitchTempo)
        assertEquals(PlaybackRange(1_000L, 9_000L), mix.range)
        assertTrue(mix.loop)

        val parsed = (PlayerMix.fromText(mix.toText()) as AudioResult.Ok).value
        assertEquals(mix, parsed)

        val c2 = DefaultPlayerTest.FakeMixerController()
        val p2 = player(c2)
        p2.load(PlayerSource(tracks = listOf(track("a"), track("b"))))
        c2.calls.clear()
        val result = p2.applyMix(parsed)
        advanceUntilIdle()

        assertEquals(AudioResult.Ok(MixApplyResult(setOf(id("a"), id("b")), emptySet())), result)
        assertEquals(mix, p2.mix.value)
        assertTrue(c2.calls.contains("volume:a=0.4"))
        assertTrue(c2.calls.contains("offset:a=120"))
        assertTrue(c2.calls.contains("boost:a=6"))
        assertTrue(c2.calls.contains("tempo:1.25"))
        assertTrue(c2.calls.contains("pitch:3"))
        assertTrue(c2.audible("a"))
        assertFalse(c2.audible("b"))
        p1.release()
        p2.release()
    }

    @Test
    fun absentTracksWaitAndStartWithTheirMixWhenAppended() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(track("original"))))
        val stems = (1..6).map { id("stem$it") }
        val mix = PlayerMix(
            tracks = stems.associateWith {
                TrackMix(volume = 0.5f, muted = true, balance = ChannelGain.Left, offsetMs = 40L, boostDb = 3)
            },
        )

        val result = player.applyMix(mix) as AudioResult.Ok
        assertEquals(emptySet<TrackId>(), result.value.applied)
        assertEquals(stems.toSet(), result.value.pending)
        assertEquals(listOf(id("original")), player.mix.value.tracks.keys.toList())

        stems.forEach { stem ->
            assertEquals(AudioResult.Done, player.appendTrack(track(stem.value, TrackSettings(volume = 1f))))
            val state = controller.snapshotTracks().getValue(stem)
            assertEquals(0.5f, state.mix.volume)
            assertTrue(state.mix.muted)
            assertEquals(40L, state.offsetMs)
            assertEquals(3, controller.boosts[stem])
            assertEquals(0.5f, player.mix.value.tracks.getValue(stem).volume)
        }
        // Pending entries are consumed: re-appending with new settings keeps those settings.
        assertEquals(AudioResult.Done, player.appendTrack(track("stem1", TrackSettings(volume = 0.9f))))
        assertEquals(0.9f, controller.snapshotTracks().getValue(id("stem1")).mix.volume)
        player.release()
    }

    @Test
    fun pendingSoloAppliesWhenTheTrackArrives() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(track("original"))))
        player.applyMix(PlayerMix(tracks = mapOf(id("vocals") to TrackMix(solo = true))))
        player.appendTrack(track("vocals"))
        advanceUntilIdle()

        assertTrue(controller.audible("vocals"))
        assertFalse(controller.audible("original"))
        assertFalse(player.state.value.tracks.getValue(id("original")).settings.muted)
        player.release()
    }

    @Test
    fun soloSilencesOthersWithoutChangingUserMute() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = four))
        player.updateTrack(id("c")) { it.copy(muted = true) }
        advanceUntilIdle()

        player.setSolo(id("b"), true)
        advanceUntilIdle()
        assertFalse(controller.audible("a"))
        assertTrue(controller.audible("b"))
        assertFalse(controller.audible("c"))
        assertFalse(controller.audible("d"))
        // User mutes are what the state reports.
        assertEquals(listOf(false, false, true, false), player.state.value.tracks.values.map { it.settings.muted })
        assertTrue(player.mix.value.tracks.getValue(id("b")).solo)

        player.setSolo(id("d"), true)
        advanceUntilIdle()
        assertFalse(controller.audible("a"))
        assertTrue(controller.audible("b"))
        assertFalse(controller.audible("c"))
        assertTrue(controller.audible("d"))

        player.setSolo(id("b"), false)
        player.setSolo(id("d"), false)
        advanceUntilIdle()
        assertTrue(controller.audible("a"))
        assertTrue(controller.audible("b"))
        assertFalse(controller.audible("c")) // user mute restored
        assertTrue(controller.audible("d"))
        assertEquals(listOf(false, false, true, false), player.state.value.tracks.values.map { it.settings.muted })

        // A user-muted soloed track stays silent.
        player.setSolo(id("c"), true)
        advanceUntilIdle()
        assertFalse(controller.audible("c"))
        assertFalse(controller.audible("a"))
        player.release()
    }

    @Test
    fun muteNeverTouchesVolume() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(track("a"))))
        player.updateTrack(id("a")) { it.copy(volume = 0.6f) }
        player.updateTrack(id("a")) { it.copy(muted = true) }
        player.updateTrack(id("a")) { it.copy(muted = false) }
        advanceUntilIdle()

        assertEquals(0.6f, player.state.value.tracks.getValue(id("a")).settings.volume)
        assertEquals(0.6f, player.mix.value.tracks.getValue(id("a")).volume)
        assertEquals(0.6f, controller.snapshotTracks().getValue(id("a")).mix.volume)
        assertEquals(listOf("volume:a=0.6"), controller.calls.filter { it.startsWith("volume:") })
        player.release()
    }

    @Test
    fun outOfRangeValuesAreClampedAndReported() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(track("a"))))
        player.applyMix(
            PlayerMix(
                tracks = mapOf(
                    id("a") to TrackMix(volume = 3f, offsetMs = 99_999L, boostDb = 40),
                    id("later") to TrackMix(volume = -1f, boostDb = -5),
                ),
                pitchTempo = PitchTempo(semitones = 30, tempo = 9f),
            ),
        )
        advanceUntilIdle()

        val a = player.mix.value.tracks.getValue(id("a"))
        assertEquals(1f, a.volume)
        assertEquals(TrackSettings.MAX_OFFSET_MS, a.offsetMs)
        assertEquals(TrackSettings.MAX_BOOST_DB, a.boostDb)
        assertEquals(PitchTempo(10, 2f), player.mix.value.pitchTempo)

        player.appendTrack(track("later"))
        val later = player.mix.value.tracks.getValue(id("later"))
        assertEquals(0f, later.volume)
        assertEquals(0, later.boostDb)
        player.release()
    }

    @Test
    fun mixAppliedBeforeLoadIsPendingThenAppliedByLoad() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        val result = player.applyMix(
            PlayerMix(
                tracks = mapOf(id("a") to TrackMix(volume = 0.3f, solo = true), id("x") to TrackMix(muted = true)),
                pitchTempo = PitchTempo(2, 1.5f),
                range = PlaybackRange(500L, 5_000L),
                loop = true,
            ),
        ) as AudioResult.Ok
        assertEquals(setOf(id("a"), id("x")), result.value.pending)
        assertTrue(result.value.applied.isEmpty())

        player.load(PlayerSource(tracks = listOf(track("a"), track("b"))))
        advanceUntilIdle()

        assertEquals(0.3f, player.state.value.tracks.getValue(id("a")).settings.volume)
        assertEquals(PitchTempo(2, 1.5f), player.state.value.pitchTempo)
        assertEquals(PlaybackRange(500L, 5_000L), player.state.value.range)
        assertTrue(player.state.value.loop)
        assertTrue(controller.audible("a"))
        assertFalse(controller.audible("b")) // silenced by a's solo
        assertTrue(player.mix.value.tracks.getValue(id("a")).solo)

        // x stayed pending; a plain second load starts clean.
        player.load(PlayerSource(tracks = listOf(track("a"), track("b"))))
        advanceUntilIdle()
        assertFalse(player.mix.value.tracks.getValue(id("a")).solo)
        assertTrue(controller.audible("b"))
        player.release()
    }

    @Test
    fun concurrentCommandsAroundLoadApplyInIssueOrder() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        var applyResult: AudioResult<MixApplyResult>? = null

        launch { player.load(PlayerSource(tracks = listOf(track("a"), track("b")))) }
        launch {
            applyResult = player.applyMix(
                PlayerMix(tracks = mapOf(id("a") to TrackMix(volume = 0.2f), id("b") to TrackMix(volume = 0.4f))),
            )
        }
        launch { player.updateTrack(id("a")) { it.copy(volume = 0.8f) } }
        launch { player.setSolo(id("b"), true) }
        launch { player.updateTrack(id("b")) { it.copy(volume = 0.7f) } }
        advanceUntilIdle()

        // Commands run strictly in issue order: load, applyMix, then the later updates win.
        assertTrue(applyResult is AudioResult.Ok)
        val mix = player.mix.value
        assertEquals(0.8f, mix.tracks.getValue(id("a")).volume)
        assertEquals(0.7f, mix.tracks.getValue(id("b")).volume)
        assertTrue(mix.tracks.getValue(id("b")).solo)
        assertTrue(controller.audible("b"))
        assertFalse(controller.audible("a"))
        player.release()
    }

    @Test
    fun pitchTempoCoalescingNeverCrossesApplyMix() = runTest {
        val a = PitchTempo(3, 1.1f)
        val b = PitchTempo(-2, 0.9f)
        val c = PitchTempo(5, 1.3f)
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(track("a"))))
        controller.calls.clear()

        player.setPitchTempo(a)
        launch { player.applyMix(PlayerMix(pitchTempo = b)) }
        testScheduler.runCurrent()
        player.setPitchTempo(c)
        advanceUntilIdle()

        assertEquals(c, player.state.value.pitchTempo)
        assertEquals(c, player.mix.value.pitchTempo)
        assertEquals("tempo:1.3", controller.calls.last { it.startsWith("tempo:") })
        assertEquals("pitch:5", controller.calls.last { it.startsWith("pitch:") })

        launch { player.applyMix(PlayerMix(pitchTempo = b)) }
        testScheduler.runCurrent()
        player.setPitchTempo(a)
        player.setPitchTempo(c)
        advanceUntilIdle()
        assertEquals(c, player.state.value.pitchTempo)
        assertEquals(c, player.mix.value.pitchTempo)
        assertEquals("pitch:5", controller.calls.last { it.startsWith("pitch:") })
        player.release()
    }

    @Test
    fun applyMixAfterReleaseFailsWithReleased() = runTest {
        val player = player(DefaultPlayerTest.FakeMixerController())
        player.load(PlayerSource(tracks = listOf(track("a"))))
        player.release()
        assertEquals(AudioResult.Err(AudioError.Released), player.applyMix(PlayerMix()))
        player.setSolo(id("a"), true) // ignored, no crash
        advanceUntilIdle()
    }
}
