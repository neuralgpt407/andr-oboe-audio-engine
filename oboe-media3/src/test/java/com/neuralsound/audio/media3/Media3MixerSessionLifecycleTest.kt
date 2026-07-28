package com.neuralsound.audio.media3

import android.net.Uri
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.AudioRoute
import com.neuralsound.audio.MixerRequest
import com.neuralsound.audio.MixerState
import com.neuralsound.audio.MixerStatus
import com.neuralsound.audio.MixerTrack
import com.neuralsound.audio.PlaybackEffects
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.media3.internal.Media3PlayerFactories
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Media3MixerSessionLifecycleTest {
    @After
    fun resetPlayerFactory() {
        Media3PlayerFactories.resetForTesting()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun prepareAfterCloseReturnsReleasedWithoutPreparingMixer() = runBlocking {
        val mixer = FakeMixerSession()
        val session = Media3MixerSession(
            context = RuntimeEnvironment.getApplication(),
            mixer = mixer,
        )
        session.close()

        val result = session.prepare(request())

        assertEquals(AudioResult.Failure(AudioFailure.Released), result)
        assertEquals(0, mixer.prepareCalls)
    }

    @Test
    fun closeInvalidatesPreparationAlreadyInFlight() = runBlocking {
        val prepareEntered = CompletableDeferred<Unit>()
        val allowPrepareToFinish = CompletableDeferred<Unit>()
        val mixer = FakeMixerSession(
            onPrepare = {
                prepareEntered.complete(Unit)
                allowPrepareToFinish.await()
                AudioResult.Success
            }
        )
        val session = Media3MixerSession(
            context = RuntimeEnvironment.getApplication(),
            mixer = mixer,
        )

        val result = async { session.prepare(request()) }
        prepareEntered.await()
        session.close()
        allowPrepareToFinish.complete(Unit)

        assertEquals(AudioResult.Failure(AudioFailure.Released), result.await())
        assertEquals(1, mixer.closeCalls)
    }

    @Test
    fun concurrentPrepareCallsAreSerialized() = runBlocking {
        val firstPrepareEntered = CompletableDeferred<Unit>()
        val allowFirstPrepareToFinish = CompletableDeferred<Unit>()
        val mixer = FakeMixerSession(
            onPrepare = { call ->
                if (call == 1) {
                    firstPrepareEntered.complete(Unit)
                    allowFirstPrepareToFinish.await()
                }
                AudioResult.Success
            }
        )
        val session = session(mixer)

        val first = async { session.prepare(request()) }
        firstPrepareEntered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            session.prepare(request())
        }

        assertEquals(1, mixer.prepareCalls)
        allowFirstPrepareToFinish.complete(Unit)
        assertEquals(AudioResult.Success, first.await())
        assertEquals(AudioResult.Success, second.await())
        assertEquals(2, mixer.prepareCalls)
        session.close()
    }

    @Test
    fun videoPreparationMutesPlayerAndPublishesListenerState() = runBlocking {
        val fakePlayer = FakePlayer()
        Media3PlayerFactories.installForTesting { fakePlayer.player }
        val mixer = FakeMixerSession()
        val session = session(mixer)
        val videoUri = Uri.parse("file:///tmp/video.mp4")

        assertEquals(AudioResult.Success, session.prepare(request(videoUri)))

        assertEquals(videoUri, fakePlayer.mediaItem?.localConfiguration?.uri)
        assertEquals(0f, fakePlayer.volume)
        assertEquals(1, fakePlayer.prepareCalls)
        assertTrue(
            fakePlayer.operationOrder.indexOf("addListener") <
                fakePlayer.operationOrder.indexOf("prepare")
        )
        assertTrue(session.videoState.value.hasVideo)
        assertSame(fakePlayer.player, session.videoState.value.player)

        fakePlayer.renderFirstFrame()
        fakePlayer.changeVideoSize(VideoSize(1_920, 1_080, 1.125f))

        assertTrue(session.videoState.value.firstFrameReady)
        assertEquals(2f, session.videoState.value.aspectRatio)
        session.close()
    }

    @Test
    fun playerFollowsPlayPauseTempoAndReadyDrift() = runBlocking {
        val fakePlayer = FakePlayer().apply {
            playbackState = Player.STATE_READY
            currentPosition = 100L
        }
        Media3PlayerFactories.installForTesting { fakePlayer.player }
        val mixer = FakeMixerSession()
        val session = session(mixer)
        assertEquals(
            AudioResult.Success,
            session.prepare(request(Uri.parse("file:///tmp/video.mp4"))),
        )

        mixer.publish(
            MixerState(
                status = MixerStatus.PLAYING,
                positionMs = 1_000L,
                durationMs = 2_000L,
                effects = PlaybackEffects(tempo = 1.25f),
                route = AudioRoute(null, null, 0L),
            )
        )
        awaitCondition {
            fakePlayer.playWhenReady &&
                fakePlayer.playbackParameters.speed == 1.25f &&
                fakePlayer.seekPositions == listOf(1_000L)
        }

        fakePlayer.currentPosition = 1_000L
        mixer.publish(
            mixer.state.value.copy(
                status = MixerStatus.PAUSED,
                positionMs = 1_100L,
            )
        )
        awaitCondition { !fakePlayer.playWhenReady }

        assertEquals(listOf(1_000L), fakePlayer.seekPositions)
        session.close()
    }

    @Test
    fun surfaceRevisionAndCloseReleasePlayerOnMainThread() = runBlocking {
        val fakePlayer = FakePlayer()
        Media3PlayerFactories.installForTesting { fakePlayer.player }
        val session = session(FakeMixerSession())
        assertEquals(
            AudioResult.Success,
            session.prepare(request(Uri.parse("file:///tmp/video.mp4"))),
        )
        val preparedRevision = session.videoState.value.revision

        session.notifyVideoSurfaceRecreated()
        assertEquals(preparedRevision + 1L, session.videoState.value.revision)

        session.close()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, fakePlayer.releaseCalls)
        assertEquals(1, fakePlayer.removeListenerCalls)
        assertSame(Looper.getMainLooper(), fakePlayer.releaseLooper)
        assertFalse(session.videoState.value.hasVideo)
    }

    private fun session(mixer: FakeMixerSession): Media3MixerSession {
        return Media3MixerSession(
            context = RuntimeEnvironment.getApplication(),
            mixer = mixer,
        )
    }

    private fun request(videoUri: Uri? = null): Media3MixerRequest {
        return Media3MixerRequest(
            audio = MixerRequest(
                tracks = listOf(MixerTrack(TrackId("vocal"), Uri.EMPTY))
            ),
            videoUri = videoUri,
        )
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(2_000L) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10L)
            }
        }
    }
}
