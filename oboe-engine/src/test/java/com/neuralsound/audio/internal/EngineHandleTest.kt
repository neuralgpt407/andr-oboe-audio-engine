package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EngineHandleTest {

    @Test
    fun newHandleIsInactiveAndUseReturnsDefault() {
        val engine = EngineHandle()

        assertFalse(engine.isActive)
        assertEquals(-1L, engine.use(-1L) { 5L })
    }

    @Test
    fun setPublishesHandleSoUseRunsBlockWithIt() {
        val engine = EngineHandle()
        engine.set(42L)

        assertTrue(engine.isActive)
        assertEquals(42L, engine.use(-1L) { handle -> handle })
    }

    @Test
    fun useAfterReleaseIsNoOpAndReturnsDefault() {
        val engine = EngineHandle()
        engine.set(42L)
        engine.release { /* native release */ }

        assertFalse(engine.isActive)
        assertEquals(-1L, engine.use(-1L) { 5L })

        var ran = false
        engine.use { ran = true }
        assertFalse("use{} must not run its block after release", ran)
    }

    @Test
    fun releaseIsIdempotent() {
        val engine = EngineHandle()
        engine.set(42L)

        var releaseCount = 0
        engine.release { releaseCount++ }
        engine.release { releaseCount++ }

        assertEquals("release block must run exactly once", 1, releaseCount)
        assertFalse(engine.isActive)
    }

    @Test
    fun releaseWaitsForInFlightUseToFinish() {
        val engine = EngineHandle()
        engine.set(99L)

        val useEntered = CountDownLatch(1)
        val releaseRanBeforeUseFinished = AtomicBoolean(false)
        val useFinished = AtomicBoolean(false)

        val worker = Thread {
            engine.use { handle ->
                useEntered.countDown()
                // Simulate the long-running native seekTo holding the handle.
                Thread.sleep(200)
                assertEquals(99L, handle)
                useFinished.set(true)
            }
        }
        worker.start()

        assertTrue("use() should have started", useEntered.await(2, TimeUnit.SECONDS))

        // Attempt to release while use() is still running. This must block
        // until the worker leaves use() — otherwise the native engine could be
        // deleted mid-call (the use-after-free that crashed in seekTo).
        engine.release {
            if (!useFinished.get()) {
                releaseRanBeforeUseFinished.set(true)
            }
        }

        worker.join(2_000)

        assertTrue("use() must have completed", useFinished.get())
        assertFalse(
            "release() ran while a native call was still in flight (use-after-free window)",
            releaseRanBeforeUseFinished.get()
        )
        assertFalse(engine.isActive)
    }
}
