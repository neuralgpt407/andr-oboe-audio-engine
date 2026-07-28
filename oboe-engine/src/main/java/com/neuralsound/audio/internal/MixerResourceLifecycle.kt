package com.neuralsound.audio.internal

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes mixer initialization and teardown around the native handle.
 *
 * Closing marks the lifecycle first, then waits for any initialization that
 * already owns the monitor. This prevents teardown from deleting a handle
 * while the initializer is still configuring it.
 */
internal class MixerResourceLifecycle {
    private val monitor = Any()
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean
        get() = closed.get()

    fun <T> withOpen(onClosed: () -> T, block: () -> T): T = synchronized(monitor) {
        if (closed.get()) onClosed() else block()
    }

    fun <T> withLock(block: () -> T): T = synchronized(monitor) {
        block()
    }

    fun close(block: () -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        synchronized(monitor) {
            block()
        }
    }
}
