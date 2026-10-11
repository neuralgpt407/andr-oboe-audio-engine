package com.neuralsound.audio.internal.player

import android.net.Uri
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.player.FocusPolicy
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrackLimitTest {
    private fun tracks(count: Int) = (0 until count).map { Track(TrackId("stem$it"), Uri.parse("content://stems/$it")) }

    private fun TestScope.player(options: PlayerOptions): Pair<DefaultPlayer, DefaultPlayerTest.FakeMixerController> {
        val controller = DefaultPlayerTest.FakeMixerController()
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DefaultPlayer(controller, options, nativeAvailable = true, dispatcher, dispatcher) to controller
    }

    @Test
    fun tenStemsLoadWhenTheLimitIsTenAndTheEleventhAppendIsRejected() = runTest {
        val (player, controller) = player(PlayerOptions(maxTracks = 10))

        assertEquals(AudioResult.Done, player.load(PlayerSource(tracks = tracks(10))))
        player.play()

        val result = player.appendTrack(Track(TrackId("stem10"), Uri.parse("content://stems/10")))

        assertEquals(AudioResult.Err(AudioError.TrackLimitExceeded(10)), result)
        assertEquals(10, player.state.value.tracks.size)
        assertEquals(PlayerStatus.PLAYING, player.state.value.status)
        assertEquals(10, controller.snapshotTracks().size)
        player.release()
    }

    @Test
    fun reAppendingAKnownTrackIsNotCountedAgainstTheLimit() = runTest {
        val (player, _) = player(PlayerOptions(maxTracks = 2))
        val loaded = tracks(2)
        player.load(PlayerSource(tracks = loaded))

        assertEquals(AudioResult.Done, player.appendTrack(loaded[1]))
        player.release()
    }

    @Test
    fun loadingMoreTracksThanTheDefaultLimitFails() = runTest {
        val (player, _) = player(PlayerOptions())
        assertEquals(
            AudioResult.Err(AudioError.TrackLimitExceeded(PlayerOptions.DEFAULT_MAX_TRACKS)),
            player.load(PlayerSource(tracks = tracks(9))),
        )
        player.release()
    }

    @Test
    fun optionsOutsideTheSupportedRangeAreAConfigurationError() = runTest {
        val (player, _) = player(PlayerOptions(maxTracks = 17))
        val result = player.load(PlayerSource(tracks = tracks(1)))
        assertEquals(true, (result.errorOrNull() as? AudioError.InvalidConfiguration)?.reason?.contains("maxTracks"))
        player.release()
    }

    @Test
    fun optionsPolicyAcceptsDefaultsAndRejectsBadDuckLevels() {
        assertNull(PlayerOptionsPolicy.problem(PlayerOptions()))
        assertNull(PlayerOptionsPolicy.problem(PlayerOptions(maxTracks = PlayerOptions.MAX_SUPPORTED_TRACKS)))
        assertEquals(
            "Duck level must be between 0 and 1",
            PlayerOptionsPolicy.problem(PlayerOptions(focus = FocusPolicy.Duck(level = 1.5f))),
        )
    }
}
