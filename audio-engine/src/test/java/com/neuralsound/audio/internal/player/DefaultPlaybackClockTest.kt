package com.neuralsound.audio.internal.player

import android.net.Uri
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.player.ClockReading
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/** Clock behaviour driven directly with a fake time source; no Android classes needed. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPlaybackClockTest {
    private val nanosPerMs = 1_000_000.0

    private fun assertSmoothAt(tempo: Float) = runTest {
        var now = 0L
        val clock = DefaultPlaybackClock(backgroundScope, MutableStateFlow(false), { 0L }, nanoTime = { now })
        clock.onLoadStarted()
        clock.onLoaded(0L, playing = false, tempo = tempo)
        clock.onPlaying(true)

        val frameNanos = 16_666_667L
        val tickNanos = 40_000_000L
        var nextTick = tickNanos
        var firstTickSeen = false
        var previous = -1L
        var frame = 0L
        while (frame * frameNanos <= 10_000_000_000L) {
            val t = frame * frameNanos
            while (nextTick <= t) {
                now = nextTick
                clock.onPosition((nextTick / nanosPerMs * tempo).toLong())
                firstTickSeen = true
                nextTick += tickNanos
            }
            now = t
            // Device priming: the clock holds the start position until the engine moves.
            if (firstTickSeen) {
                val position = clock.positionAt(t)
                val truth = t / nanosPerMs * tempo
                assertTrue("went backwards at frame $frame: $position < $previous", position >= previous)
                assertTrue("frame $frame: $position vs $truth", abs(position - truth) <= 20.0)
                previous = position
            }
            frame++
        }
        assertTrue(previous > 9_000L)
    }

    @Test
    fun positionAtIsSmoothAndAccurateAtUnityTempo() = assertSmoothAt(1.0f)

    @Test
    fun positionAtIsSmoothAndAccurateAtFasterTempo() = assertSmoothAt(1.25f)

    @Test
    fun positionsEmitsOnIntervalWhileAdvancingAndOnEventsButNotWhilePaused() = runTest {
        val syncing = MutableStateFlow(false)
        var enginePos = 0L
        val nanoTime = { testScheduler.currentTime * 1_000_000L }
        val clock = DefaultPlaybackClock(backgroundScope, syncing, { enginePos }, nanoTime = nanoTime)
        clock.onLoadStarted()
        clock.onLoaded(0L, playing = false, tempo = 1f)
        clock.onPlaying(true)

        var playing = true
        backgroundScope.launch {
            while (true) {
                delay(40)
                if (playing && !syncing.value) {
                    enginePos += 40
                    clock.onPosition(enginePos)
                }
            }
        }
        val seen = mutableListOf<Pair<Long, ClockReading>>()
        backgroundScope.launch { clock.positions(250).collect { seen += testScheduler.currentTime to it } }

        advanceTimeBy(2_500)
        runCurrent()
        enginePos = 90_000L
        syncing.value = true
        clock.beginSeek(90_000L)
        advanceTimeBy(100)
        syncing.value = false
        advanceTimeBy(2_400)
        runCurrent()

        val intervalReadings = seen.count { it.second.isAdvancing }
        assertTrue("interval emissions: $intervalReadings", intervalReadings in 18..22)
        assertTrue("immediate emission at the seek", seen.any { it.second.seeking && it.second.seekTargetMs == 90_000L })
        assertTrue("immediate emission when the seek ends", seen.any { !it.second.seeking && it.second.positionMs >= 90_000L })
        assertTrue("immediate emission for the initial reading", seen.first().first == 0L)

        // Pause: one immediate reading, then silence.
        val countBeforePause = seen.size
        playing = false
        clock.onPlaying(false)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(countBeforePause + 1, seen.size)
        assertFalse(seen.last().second.isPlaying)
    }

    @Test
    fun jumpsAgainstExpectationCountAsOneDiscontinuity() = runTest {
        var now = 0L
        val clock = DefaultPlaybackClock(backgroundScope, MutableStateFlow(false), { 0L }, nanoTime = { now })
        clock.onLoadStarted()
        clock.onLoaded(0L, playing = false, tempo = 1f)
        clock.onPlaying(true)
        val before = clock.reading.value.discontinuity
        now = 40_000_000L
        clock.onPosition(40L)
        now = 80_000_000L
        clock.onPosition(80L)
        assertEquals(before, clock.reading.value.discontinuity)
        // Loop wrap: the position jumps back far more than 150 ms.
        now = 400_000_000L
        clock.onPosition(5L)
        assertEquals(before + 1, clock.reading.value.discontinuity)
        assertEquals(5L, clock.positionAt(now))
    }
}

/** Seek, discontinuity and clamping behaviour through [DefaultPlayer] with a fake controller. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultPlaybackClockPlayerTest {
    private val track = Track(TrackId("original"), Uri.parse("content://media/original-48000"))

    private fun TestScope.player(controller: DefaultPlayerTest.FakeMixerController): DefaultPlayer {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DefaultPlayer(
            controller, PlayerOptions(), nativeAvailable = true, dispatcher, dispatcher,
            nanoTime = { testScheduler.currentTime * 1_000_000L },
        )
    }

    @Test
    fun seekShowsTheTargetImmediatelyAndNeverRevertsUntilSyncEnds() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController().apply { holdSyncOnSeek = true }
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(track), autoPlay = true))
        runCurrent()
        controller.totalDuration.value = 120_000L
        controller.currentPosition.value = 30_000L
        advanceTimeBy(100)
        runCurrent()
        assertEquals(30_000L, player.state.value.positionMs)

        val seen = mutableListOf<Long>()
        backgroundScope.launch { player.state.collect { seen += it.positionMs } }
        runCurrent()
        seen.clear()

        player.seekTo(90_000L)
        runCurrent()
        assertEquals(90_000L, player.state.value.positionMs)
        val seeking = player.clock.reading.value
        assertTrue(seeking.seeking)
        assertEquals(90_000L, seeking.seekTargetMs)
        assertEquals(90_000L, player.clock.positionAt(testScheduler.currentTime * 1_000_000L))

        // A stale pre-seek emission from the controller must not reach the state.
        controller.currentPosition.value = 30_040L
        advanceTimeBy(40)
        runCurrent()
        assertEquals(90_000L, player.state.value.positionMs)

        controller.finishSeek()
        advanceTimeBy(100)
        runCurrent()
        assertFalse(player.clock.reading.value.seeking)
        assertNull(player.clock.reading.value.seekTargetMs)
        assertEquals(90_000L, player.state.value.positionMs)
        assertTrue("states: $seen", seen.all { it >= 90_000L - 50 })
        player.release()
    }

    @Test
    fun pauseResumeSeekAndLoadEachCountOnceAndStampTheCaptureTime() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        val clock = player.clock

        fun discontinuity() = clock.reading.value.discontinuity
        var last = discontinuity()
        fun step(label: String, action: () -> Unit) {
            action()
            runCurrent()
            assertEquals("$label should count once", last + 1, discontinuity())
            last = discontinuity()
        }

        advanceTimeBy(1_000)
        step("load") { launch { player.load(PlayerSource(tracks = listOf(track))) } }
        advanceTimeBy(500)
        step("play") { player.play() }
        assertEquals(1_500_000_000L, clock.reading.value.capturedAtNanos)
        advanceTimeBy(500)
        step("pause") { player.pause() }
        assertEquals(2_000_000_000L, clock.reading.value.capturedAtNanos)
        step("resume") { player.play() }
        step("seek") { player.seekTo(5_000L) }
        assertFalse(clock.reading.value.seeking)
        step("pause again") { player.pause() }
        step("second load") { launch { player.load(PlayerSource(tracks = listOf(track), startAtMs = 2_000L)) } }
        player.release()
    }

    @Test
    fun seekTargetsAreClampedToTheDurationAndRange() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController().apply { holdSyncOnSeek = true }
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(track)))
        runCurrent()

        player.seekTo(999_999L)
        runCurrent()
        assertEquals(10_000L, player.state.value.positionMs)
        assertEquals(10_000L, player.clock.reading.value.seekTargetMs)
        controller.finishSeek()
        advanceTimeBy(100)
        runCurrent()

        player.seekTo(-500L)
        runCurrent()
        assertEquals(0L, player.state.value.positionMs)
        assertEquals(0L, player.clock.reading.value.seekTargetMs)
        controller.finishSeek()
        advanceTimeBy(100)
        runCurrent()

        player.setRange(PlaybackRange(1_000L, 4_000L), loop = false)
        runCurrent()
        // Position 0 is outside the range: the controller jumps to the range start.
        assertEquals(1_000L, player.state.value.positionMs)
        assertEquals(1_000L, player.clock.reading.value.seekTargetMs)
        controller.finishSeek()
        advanceTimeBy(100)
        runCurrent()

        player.seekTo(9_000L)
        runCurrent()
        assertEquals(4_000L, player.state.value.positionMs)
        assertEquals(4_000L, player.clock.reading.value.seekTargetMs)
        player.release()
    }

    @Test
    fun clockUsesThePlayersTempo() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(track), pitchTempo = PitchTempo(tempo = 1.5f)))
        runCurrent()
        assertEquals(1.5f, player.clock.reading.value.rate)
        player.setPitchTempo(PitchTempo(tempo = 0.75f))
        runCurrent()
        assertEquals(0.75f, player.clock.reading.value.rate)
        player.release()
    }
}
