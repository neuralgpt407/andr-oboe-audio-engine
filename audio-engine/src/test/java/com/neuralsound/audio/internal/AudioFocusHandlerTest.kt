package com.neuralsound.audio.internal

import android.media.AudioManager
import com.neuralsound.audio.player.FocusPolicy
import com.neuralsound.audio.player.FocusState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-18 / S3-AS4 / S3-AS5 / EC-6: focus policies. */
class AudioFocusHandlerTest {
    private class Recorder(var playing: Boolean = true) : AudioFocusHandler.Actions {
        val log = mutableListOf<String>()
        override fun isPrepared() = true
        override fun isPlaying() = playing
        override fun pauseForFocus() { playing = false; log += "pause" }
        override fun resumeAfterFocus() { playing = true; log += "resume" }
        override fun setAttenuation(level: Float) { log += "attenuate:$level" }
        override fun publish(state: FocusState) { log += "state:$state" }
    }

    @Test
    fun duckPolicyRampsDownAndBackWithoutPausing() {
        val actions = Recorder()
        val handler = AudioFocusHandler(FocusPolicy.Duck(level = 0.2f), actions)

        handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        handler.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(listOf("attenuate:0.2", "state:DUCKED", "attenuate:1.0", "state:GAINED"), actions.log)
        assertTrue(actions.playing)
    }

    @Test
    fun transientLossPausesAndResumesOnRegain() {
        listOf(FocusPolicy.Duck(), FocusPolicy.PauseAndResume).forEach { policy ->
            val actions = Recorder()
            val handler = AudioFocusHandler(policy, actions)

            handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertTrue(handler.pausedByFocus)
            handler.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

            assertEquals("$policy", listOf("pause", "state:LOST_TRANSIENT", "resume", "state:GAINED"), actions.log)
        }
    }

    @Test
    fun pauseAndResumePausesEvenWhenDuckingIsAllowed() {
        val actions = Recorder()
        val handler = AudioFocusHandler(FocusPolicy.PauseAndResume, actions)

        handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        handler.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(listOf("pause", "state:LOST_TRANSIENT", "resume", "state:GAINED"), actions.log)
    }

    @Test
    fun aUserPauseDuringTheLossCancelsAutoResume() {
        val actions = Recorder()
        val handler = AudioFocusHandler(FocusPolicy.Duck(), actions)

        handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        handler.onUserCommand()
        handler.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(actions.log.contains("resume"))
        assertFalse(actions.playing)
    }

    @Test
    fun permanentLossAlwaysPausesWithoutAutoResume() {
        listOf(FocusPolicy.Duck(), FocusPolicy.PauseAndResume).forEach { policy ->
            val actions = Recorder()
            val handler = AudioFocusHandler(policy, actions)

            handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            handler.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)

            assertFalse("$policy", actions.log.contains("resume"))
            assertFalse(actions.playing)
            assertTrue(actions.log.contains("state:LOST"))
        }
    }

    @Test
    fun ignorePolicyNeverRequestsOrReacts() {
        val actions = Recorder()
        val handler = AudioFocusHandler(FocusPolicy.Ignore, actions)

        assertFalse(handler.requestsFocus)
        handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertTrue(actions.log.isEmpty())
        assertTrue(actions.playing)
    }

    @Test
    fun resetRestoresFullOutputAfterADuck() {
        val actions = Recorder()
        val handler = AudioFocusHandler(FocusPolicy.Duck(level = 0.3f), actions)
        handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        handler.reset()

        assertEquals("attenuate:1.0", actions.log.last())
        assertFalse(handler.ducked)
    }

    @Test
    fun heldFocusIsNotRequestedAgainUntilItIsLostOrAbandoned() {
        val handler = AudioFocusHandler(FocusPolicy.Duck(), Recorder())

        assertTrue(handler.needsFocusRequest())
        handler.onFocusRequestResult(granted = true)
        assertFalse("play while holding focus must not re-request it", handler.needsFocusRequest())

        handler.onFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue("a play during a loss takes focus back", handler.needsFocusRequest())
        handler.onFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(handler.needsFocusRequest())

        handler.onFocusAbandoned()
        assertTrue(handler.needsFocusRequest())
        handler.onFocusRequestResult(granted = false)
        assertTrue("a refused request is retried on the next play", handler.needsFocusRequest())
    }

    @Test
    fun ignorePolicyNeverNeedsAFocusRequest() {
        assertFalse(AudioFocusHandler(FocusPolicy.Ignore, Recorder()).needsFocusRequest())
    }
}
