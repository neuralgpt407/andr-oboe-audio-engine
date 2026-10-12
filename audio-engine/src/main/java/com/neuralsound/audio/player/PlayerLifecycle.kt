package com.neuralsound.audio.player

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How a [Player] reacts to app and screen lifecycle events when attached with [bindTo].
 *
 * Background and foreground refer to the whole app process, so rotating a screen never pauses.
 */
data class LifecycleBinding(
    /** Pause a playing player when the app process goes to the background. */
    val pauseInBackground: Boolean = true,
    /**
     * Resume when the app returns to the foreground, but only if this binding paused the player
     * and the user has not played or paused since.
     */
    val resumeInForeground: Boolean = false,
    /**
     * Release the player when the owner is destroyed, unless the owner is an [Activity] that is
     * being destroyed for a configuration change such as rotation.
     */
    val releaseOnDestroy: Boolean = true,
)

/**
 * Ties this player to [owner] and the app process according to [binding].
 *
 * Call from the main thread. Background and foreground come from the process lifecycle, not
 * from [owner]. The returned handle removes every observer; closing it is idempotent and the
 * binding is also removed once the player has been released by it.
 */
fun Player.bindTo(owner: LifecycleOwner, binding: LifecycleBinding = LifecycleBinding()): AutoCloseable =
    bindToInternal(owner, binding, PlayerLifecycleDefaults.processLifecycle())

/**
 * Releases this player when [scope] completes or is cancelled.
 *
 * @throws IllegalArgumentException if [scope] has no [Job], since it could never complete.
 */
fun Player.releaseWith(scope: CoroutineScope) {
    val job = requireNotNull(scope.coroutineContext[Job]) {
        "releaseWith needs a CoroutineScope that contains a Job, otherwise it can never complete."
    }
    job.invokeOnCompletion { releaseInProcessScope() }
}

/** Test seam: where background/foreground comes from. */
internal object PlayerLifecycleDefaults {
    @Volatile
    var processLifecycle: () -> Lifecycle = { ProcessLifecycleOwner.get().lifecycle }
}

/** Outlives every screen so a release started on destroy always runs to completion. */
private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private fun Player.releaseInProcessScope() {
    processScope.launch { release() }
}

internal fun Player.bindToInternal(
    owner: LifecycleOwner,
    binding: LifecycleBinding,
    processLifecycle: Lifecycle,
): AutoCloseable {
    val player = this
    val closed = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var pausedByBinding = false

    // Any status change not caused by the binding (user played) cancels the pending resume.
    scope.launch {
        player.state.map { it.status }.distinctUntilChanged().collect { status ->
            if (pausedByBinding && status != PlayerStatus.PAUSED) pausedByBinding = false
        }
    }

    val processObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_STOP ->
                if (binding.pauseInBackground && player.state.value.isPlaying) {
                    pausedByBinding = true
                    player.pause()
                }
            Lifecycle.Event.ON_START ->
                if (binding.resumeInForeground && pausedByBinding) {
                    pausedByBinding = false
                    player.play()
                }
            else -> Unit
        }
    }
    lateinit var ownerObserver: LifecycleEventObserver

    fun remove() {
        scope.coroutineContext[Job]?.cancel()
        // Observers may only be removed on the main thread.
        processScope.launch {
            processLifecycle.removeObserver(processObserver)
            owner.lifecycle.removeObserver(ownerObserver)
        }
    }

    ownerObserver = LifecycleEventObserver { source, event ->
        if (event != Lifecycle.Event.ON_DESTROY) return@LifecycleEventObserver
        if (source is Activity && source.isChangingConfigurations) return@LifecycleEventObserver
        if (closed.compareAndSet(false, true)) {
            source.lifecycle.removeObserver(ownerObserver)
            processLifecycle.removeObserver(processObserver)
            scope.coroutineContext[Job]?.cancel()
            player.releaseInProcessScope()
        }
    }

    if (binding.pauseInBackground || binding.resumeInForeground) processLifecycle.addObserver(processObserver)
    if (binding.releaseOnDestroy) owner.lifecycle.addObserver(ownerObserver)

    return AutoCloseable { if (closed.compareAndSet(false, true)) remove() }
}
