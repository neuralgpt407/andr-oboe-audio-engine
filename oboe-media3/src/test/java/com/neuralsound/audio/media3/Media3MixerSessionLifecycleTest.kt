package com.neuralsound.audio.media3

import android.net.Uri
import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.MixerRequest
import com.neuralsound.audio.MixerSession
import com.neuralsound.audio.MixerState
import com.neuralsound.audio.MixerTrack
import com.neuralsound.audio.PlaybackEffects
import com.neuralsound.audio.PlaybackRange
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.TrackMix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Media3MixerSessionLifecycleTest {
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

    private fun request(): Media3MixerRequest {
        return Media3MixerRequest(
            audio = MixerRequest(
                tracks = listOf(MixerTrack(TrackId("vocal"), Uri.EMPTY))
            ),
            videoUri = null,
        )
    }

    private class FakeMixerSession(
        private val onPrepare: suspend () -> AudioResult = { AudioResult.Success },
    ) : MixerSession {
        private val mutableState = MutableStateFlow(MixerState())
        override val state: StateFlow<MixerState> = mutableState
        var prepareCalls = 0
        var closeCalls = 0

        override suspend fun prepare(request: MixerRequest): AudioResult {
            prepareCalls += 1
            return onPrepare()
        }

        override fun appendTrack(track: MixerTrack): AudioResult = AudioResult.Success
        override fun play(): AudioResult = AudioResult.Success
        override fun pause(): AudioResult = AudioResult.Success
        override fun seekTo(positionMs: Long): AudioResult = AudioResult.Success
        override fun setPlaybackRange(range: PlaybackRange?): AudioResult = AudioResult.Success
        override fun setLooping(looping: Boolean): AudioResult = AudioResult.Success
        override fun updateTrackMix(trackId: TrackId, mix: TrackMix): AudioResult = AudioResult.Success
        override fun setTrackOffset(trackId: TrackId, offsetMs: Long): AudioResult = AudioResult.Success
        override fun setEffects(effects: PlaybackEffects): AudioResult = AudioResult.Success

        override fun close() {
            closeCalls += 1
        }
    }
}
