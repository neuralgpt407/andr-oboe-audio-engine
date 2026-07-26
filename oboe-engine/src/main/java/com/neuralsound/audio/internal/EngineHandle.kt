package com.neuralsound.audio.internal

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Owns the raw native Oboe engine pointer and guards its lifetime.
 *
 * The native seekTo()/play()/pause()/setVolume() calls run on background
 * coroutines and can block for hundreds of milliseconds (seekTo deliberately
 * waits on fade-out, pause, flush and decoder-ready). Deleting (releasing) the
 * native engine while one of those calls is still executing is a use-after-free
 * that crashes inside native code — the production crash was
 * OboeAudioEngine::seekTo operating on a freed `this`.
 *
 * This gate makes every native call hold a shared (read) lock for its full
 * duration and makes [release] take the exclusive (write) lock. As a result the
 * pointer can never be deleted while a native call is still using it, and any
 * native call that starts after release sees a cleared handle and no-ops.
 */
internal class EngineHandle {

    // Fair so a release() is not starved by the frequent position-poll reads.
    private val lock = ReentrantReadWriteLock(/* fair = */ true)

    @Volatile
    private var handle: Long = 0L

    val isActive: Boolean
        get() = lock.read { handle != 0L }

    /** Publishes a freshly created native handle so other threads may use it. */
    fun set(newHandle: Long) = lock.write { handle = newHandle }

    /**
     * Runs [block] with the live native handle while holding a shared lock,
     * guaranteeing the engine is not released for the duration of the call.
     * Returns [default] without running [block] when no engine is active.
     */
    fun <T> use(default: T, block: (Long) -> T): T = lock.read {
        val current = handle
        if (current == 0L) default else block(current)
    }

    /** Shared-lock variant for native calls that return nothing. */
    fun use(block: (Long) -> Unit) {
        lock.read {
            val current = handle
            if (current != 0L) block(current)
        }
    }

    /**
     * Takes the exclusive lock, runs [block] with the current handle so it can
     * be released natively, then clears it. Waits for any in-flight [use] calls
     * to finish first. A no-op when no engine is active, so it is safe to call
     * repeatedly.
     */
    fun release(block: (Long) -> Unit) = lock.write {
        val current = handle
        if (current != 0L) {
            block(current)
            handle = 0L
        }
    }
}
