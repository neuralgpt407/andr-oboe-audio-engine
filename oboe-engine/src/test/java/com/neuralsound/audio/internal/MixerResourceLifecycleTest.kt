package com.neuralsound.audio.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerResourceLifecycleTest {
    @Test
    fun closeWaitsForInitializationBeforeReleasingResources() {
        val lifecycle = MixerResourceLifecycle()
        val initializationEntered = CountDownLatch(1)
        val allowInitializationToFinish = CountDownLatch(1)
        val releaseCalled = AtomicBoolean(false)

        val initialization = Thread {
            lifecycle.withOpen(
                onClosed = { error("initialization unexpectedly rejected") },
            ) {
                initializationEntered.countDown()
                assertTrue(allowInitializationToFinish.await(2, TimeUnit.SECONDS))
            }
        }
        initialization.start()
        assertTrue(initializationEntered.await(2, TimeUnit.SECONDS))

        val close = Thread {
            lifecycle.close {
                releaseCalled.set(true)
            }
        }
        close.start()
        val closeDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!lifecycle.isClosed && System.nanoTime() < closeDeadlineNanos) {
            Thread.yield()
        }

        assertTrue(lifecycle.isClosed)
        assertFalse(releaseCalled.get())
        allowInitializationToFinish.countDown()
        initialization.join(2_000L)
        close.join(2_000L)

        assertFalse(initialization.isAlive)
        assertFalse(close.isAlive)
        assertTrue(releaseCalled.get())
    }

    @Test
    fun initializationAfterCloseIsRejectedWithoutAllocatingResources() {
        val lifecycle = MixerResourceLifecycle()
        var releases = 0
        var allocations = 0
        lifecycle.close { releases += 1 }

        val result = lifecycle.withOpen(
            onClosed = { "closed" },
        ) {
            allocations += 1
            "open"
        }

        assertEquals("closed", result)
        assertEquals(0, allocations)
        assertEquals(1, releases)
    }
}
