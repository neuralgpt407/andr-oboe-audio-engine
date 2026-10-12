package com.neuralsound.audio.internal.recorder

import com.neuralsound.audio.recorder.MicForeground
import kotlinx.coroutines.CompletableDeferred

/**
 * Process-local hand-off between [MicForegroundController] and [MicrophoneForegroundService].
 * The app's notification builder is a lambda, so it never travels in an Intent.
 */
internal object MicForegroundRegistry {
    class Pending(val foreground: MicForeground) {
        /** Completed by the service: true once promoted to the foreground, false on refusal. */
        val result = CompletableDeferred<Boolean>()
    }

    private var pending: Pending? = null

    @Synchronized
    fun set(value: Pending) {
        pending?.result?.complete(false)
        pending = value
    }

    /** Hands the pending request to the service; a request is consumed once. */
    @Synchronized
    fun take(): Pending? = pending.also { pending = null }

    /** Drops [value] if it is still waiting (timeout or stop). */
    @Synchronized
    fun clear(value: Pending) {
        if (pending === value) pending = null
    }
}
