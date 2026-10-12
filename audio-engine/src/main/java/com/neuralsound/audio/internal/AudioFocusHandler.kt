package com.neuralsound.audio.internal

import android.media.AudioManager
import com.neuralsound.audio.player.FocusPolicy
import com.neuralsound.audio.player.FocusState

/**
 * Pure audio-focus state machine (FEAT-0001 FR-18). The controller feeds it platform focus
 * changes and performs the returned side effects through [Actions].
 *
 * - [FocusPolicy.Duck]: a may-duck loss ramps the output down to the duck level and back.
 * - [FocusPolicy.PauseAndResume]: a may-duck loss pauses like any transient loss.
 * - Both: a transient loss pauses and resumes on regain, but only when focus caused the
 *   pause; a permanent loss pauses with no auto-resume.
 * - [FocusPolicy.Ignore]: focus is never requested, so no changes arrive.
 */
internal class AudioFocusHandler(
    private val policy: FocusPolicy,
    private val actions: Actions,
) {
    interface Actions {
        fun isPrepared(): Boolean
        fun isPlaying(): Boolean
        fun pauseForFocus()
        fun resumeAfterFocus()
        fun setAttenuation(level: Float)
        fun publish(state: FocusState)
    }

    @Volatile var pausedByFocus = false
        private set

    @Volatile var ducked = false
        private set

    val requestsFocus: Boolean
        get() = policy !is FocusPolicy.Ignore

    /** Call when the app explicitly starts or stops playback; a user action cancels auto-resume. */
    fun onUserCommand() {
        pausedByFocus = false
    }

    fun onFocusChange(focusChange: Int) {
        if (!requestsFocus) return
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                unduck()
                if (pausedByFocus) {
                    pausedByFocus = false
                    if (actions.isPrepared() && !actions.isPlaying()) actions.resumeAfterFocus()
                }
                actions.publish(FocusState.GAINED)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                unduck()
                if (actions.isPrepared() && actions.isPlaying()) actions.pauseForFocus()
                // Permanent loss never delivers GAIN again for this request: no auto-resume.
                pausedByFocus = false
                actions.publish(FocusState.LOST)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseTransiently()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> when (policy) {
                is FocusPolicy.Duck -> {
                    if (!ducked) {
                        ducked = true
                        actions.setAttenuation(policy.level.coerceIn(0f, 1f))
                    }
                    actions.publish(FocusState.DUCKED)
                }
                else -> pauseTransiently()
            }
        }
    }

    /** Resets duck/auto-resume state when the session is torn down or reloaded. */
    fun reset() {
        pausedByFocus = false
        if (ducked) {
            ducked = false
            actions.setAttenuation(1f)
        }
    }

    private fun pauseTransiently() {
        unduck()
        if (actions.isPrepared() && actions.isPlaying()) {
            actions.pauseForFocus()
            pausedByFocus = true
        }
        actions.publish(FocusState.LOST_TRANSIENT)
    }

    private fun unduck() {
        if (ducked) {
            ducked = false
            actions.setAttenuation(1f)
        }
    }
}
