package com.neuralsound.audio.internal.video

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlayerState
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.VideoState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** FR-26 / S5-AS1..AS4: muted video follows the audio clock; presentation can come and go. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlayerVideoTest {
    private val fake = FakePlayer().apply { playbackState = Player.STATE_READY }
    private val audio = MutableStateFlow(PlayerState(status = PlayerStatus.PAUSED, durationMs = 60_000))
    private var videoState = VideoState()
    private var now = 0L
    private val uri = Uri.parse("file:///tmp/clip.mp4")

    @After
    fun resetFactory() = ExoPlayerFactories.resetForTesting()

    private fun TestScope.video(): PlayerVideo {
        ExoPlayerFactories.installForTesting { fake.player }
        return PlayerVideo(
            context = RuntimeEnvironment.getApplication(),
            audio = audio,
            publish = { transform -> videoState = transform(videoState) },
            mainDispatcher = StandardTestDispatcher(testScheduler),
            clock = { now },
            syncIntervalMs = 100,
        )
    }

    private fun TestScope.step(ms: Long) {
        now += ms
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun sourcePreparesAMutedPlayerAndPublishesFrameState() = runTest {
        val video = video()
        video.setSource(uri)
        runCurrent()

        assertEquals(uri, fake.mediaItem?.localConfiguration?.uri)
        assertEquals(0f, fake.volume)
        assertEquals(1, fake.prepareCalls)
        assertTrue(videoState.hasVideo)
        assertSame(fake.player, videoState.player)

        fake.renderFirstFrame()
        fake.changeVideoSize(VideoSize(1_920, 1_080, 1.125f))
        assertTrue(videoState.firstFrameReady)
        assertEquals(2f, videoState.aspectRatio)
        video.release()
    }

    @Test
    fun followsPlayPauseAndTempo() = runTest {
        val video = video()
        video.setSource(uri)
        runCurrent()

        audio.value = audio.value.copy(status = PlayerStatus.PLAYING, pitchTempo = PitchTempo(tempo = 1.5f))
        runCurrent()
        assertTrue(fake.playWhenReady)
        assertEquals(1.5f, fake.playbackParameters.speed)

        audio.value = audio.value.copy(status = PlayerStatus.PAUSED)
        runCurrent()
        assertFalse(fake.playWhenReady)
        video.release()
    }

    @Test
    fun steadyDriftWithinTheDeadbandIsLeftAloneAndLargeDriftIsCorrected() = runTest {
        val video = video()
        video.setSource(uri)
        runCurrent()
        audio.value = audio.value.copy(status = PlayerStatus.PLAYING, positionMs = 10_000)
        fake.currentPosition = 9_950
        runCurrent()
        fake.seekPositions.clear() // the jump from 0 to 10 s seeks immediately; only drift matters below
        repeat(10) { step(100) }
        assertTrue("50 ms drift is inside the 75 ms dead-band", fake.seekPositions.isEmpty())

        // Gradual drift (no position jump) beyond the 450 ms hard band is corrected after confirmation.
        audio.value = audio.value.copy(positionMs = 10_400)
        runCurrent()
        fake.currentPosition = 9_900
        repeat(3) { step(100) }
        assertEquals(listOf(10_400L), fake.seekPositions)
        assertEquals(1L, videoState.sync.hardCorrections)
        assertEquals(500L, videoState.sync.lastDriftMs)
        video.release()
    }

    @Test
    fun audioSeekMovesVideoImmediately() = runTest {
        val video = video()
        video.setSource(uri)
        runCurrent()
        fake.seekPositions.clear()

        audio.value = audio.value.copy(status = PlayerStatus.PLAYING, positionMs = 30_000)
        runCurrent()

        assertEquals(listOf(30_000L), fake.seekPositions)
        video.release()
    }

    @Test
    fun suspendKeepsAudioAndResumeSeeksToTheAudioPositionFirst() = runTest {
        val video = video()
        video.setSource(uri)
        runCurrent()
        audio.value = audio.value.copy(status = PlayerStatus.PLAYING, positionMs = 5_000)
        runCurrent()

        video.suspendPresentation()
        runCurrent()
        assertFalse(fake.playWhenReady)
        fake.seekPositions.clear()

        audio.value = audio.value.copy(positionMs = 42_000)
        runCurrent()
        assertTrue("suspended video does not follow", fake.seekPositions.isEmpty())

        video.attachPresentation()
        runCurrent()
        assertEquals(42_000L, fake.seekPositions.first())
        assertTrue(fake.playWhenReady)
        video.release()
    }

    @Test
    fun detachReleasesThePlayerWhileARevisionTracksEveryChange() = runTest {
        val video = video()
        video.setSource(uri)
        runCurrent()
        val attached = videoState.revision

        video.notifySurfaceRecreated()
        assertEquals(attached + 1, videoState.revision)

        video.detachPresentation()
        runCurrent()
        assertNull(videoState.player)
        assertEquals(1, fake.releaseCalls)
        assertEquals(1, fake.removeListenerCalls)
        assertTrue(videoState.revision > attached + 1)

        video.attachPresentation()
        runCurrent()
        assertSame(fake.player, videoState.player)
        video.release()
    }

    @Test
    fun audioOnlySourcesHaveNoVideo() = runTest {
        val video = video()
        video.setSource(null)
        runCurrent()
        assertFalse(videoState.hasVideo)
        assertEquals(0, fake.prepareCalls)
        video.release()
    }
}
