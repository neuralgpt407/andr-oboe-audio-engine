package com.neuralsound.audio.internal.player

import android.net.Uri
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.internal.MixerCompletion
import com.neuralsound.audio.player.CompletionReason
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerEvent
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** S1-AS7 / FR-9: one Completed event per natural end, ENDED status, none while looping. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CompletionEventTest {
    private val track = Track(TrackId("mix"), Uri.parse("content://media/mix"))

    @Test
    fun endOfMediaEmitsOneCompletedEventAndEndsThePlayer() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val player = DefaultPlayer(controller, PlayerOptions(), true, dispatcher, dispatcher)
        val completions = mutableListOf<PlayerEvent.Completed>()
        val collector = launch { player.events.filterIsInstance<PlayerEvent.Completed>().toList(completions) }

        player.load(PlayerSource(tracks = listOf(track), autoPlay = true))
        advanceUntilIdle()
        controller.finishAt(10_000L, MixerCompletion.END_OF_MEDIA)
        advanceUntilIdle()

        assertEquals(listOf(PlayerEvent.Completed(CompletionReason.END_OF_MEDIA, 10_000L)), completions)
        assertEquals(PlayerStatus.ENDED, player.state.value.status)

        player.play()
        advanceUntilIdle()
        assertEquals(PlayerStatus.PLAYING, player.state.value.status)
        controller.finishAt(10_000L, MixerCompletion.END_OF_MEDIA)
        advanceUntilIdle()
        assertEquals(2, completions.size)

        collector.cancel()
        player.release()
    }

    @Test
    fun nonLoopingRangeEndReportsEndOfRange() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val player = DefaultPlayer(controller, PlayerOptions(), true, dispatcher, dispatcher)
        val completions = mutableListOf<PlayerEvent.Completed>()
        val collector = launch { player.events.filterIsInstance<PlayerEvent.Completed>().toList(completions) }

        player.load(PlayerSource(tracks = listOf(track), range = PlaybackRange(10_000L, 20_000L), autoPlay = true))
        advanceUntilIdle()
        controller.finishAt(20_000L, MixerCompletion.END_OF_RANGE)
        advanceUntilIdle()

        assertEquals(listOf(PlayerEvent.Completed(CompletionReason.END_OF_RANGE, 20_000L)), completions)
        assertEquals(PlayerStatus.ENDED, player.state.value.status)
        collector.cancel()
        player.release()
    }

    @Test
    fun completionAfterReleaseIsIgnored() = runTest {
        val controller = DefaultPlayerTest.FakeMixerController()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val player = DefaultPlayer(controller, PlayerOptions(), true, dispatcher, dispatcher)
        player.load(PlayerSource(tracks = listOf(track)))
        player.release()

        controller.finishAt(10_000L, MixerCompletion.END_OF_MEDIA)
        advanceUntilIdle()

        assertEquals(PlayerStatus.RELEASED, player.state.value.status)
    }
}
