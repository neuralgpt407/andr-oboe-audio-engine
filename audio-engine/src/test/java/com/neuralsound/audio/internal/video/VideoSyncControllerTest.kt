package com.neuralsound.audio.internal.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoSyncControllerTest {
    @Test
    fun `start samples on cadence and stop prevents further samples`() = runTest {
        var samples = 0
        val controller = VideoSyncController(
            scope = backgroundScope,
            sampleIntervalMs = 250L,
            ineligibility = { null },
            synchronize = { samples += 1 },
        )

        controller.start(generation = 1L)
        runCurrent()
        assertTrue(controller.isRunning())
        assertEquals(0, samples)

        advanceTimeBy(250L)
        runCurrent()
        assertEquals(1, samples)

        controller.stop()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        assertFalse(controller.isRunning())
        assertEquals(1, samples)
    }

    @Test
    fun `ineligible loop performs zero samples`() = runTest {
        var samples = 0
        val skipped = mutableListOf<VideoSyncSkipReason>()
        val controller = VideoSyncController(
            scope = backgroundScope,
            sampleIntervalMs = 250L,
            ineligibility = { VideoSyncSkipReason.PAUSED },
            synchronize = { samples += 1 },
            onSkipped = skipped::add,
        )

        controller.start(generation = 1L)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(0, samples)
        assertEquals(4, skipped.size)
        assertTrue(skipped.all { it == VideoSyncSkipReason.PAUSED })
        controller.stop()
    }

    @Test
    fun `stale generation is rejected before sampling`() = runTest {
        var currentGeneration = 1L
        var samples = 0
        var staleSkips = 0
        val controller = VideoSyncController(
            scope = backgroundScope,
            sampleIntervalMs = 250L,
            ineligibility = { expectedGeneration ->
                if (expectedGeneration == currentGeneration) null
                else VideoSyncSkipReason.STALE_GENERATION
            },
            synchronize = { samples += 1 },
            onSkipped = { reason ->
                if (reason == VideoSyncSkipReason.STALE_GENERATION) staleSkips += 1
            },
        )

        controller.start(generation = currentGeneration)
        currentGeneration = 2L
        advanceTimeBy(250L)
        runCurrent()

        assertEquals(0, samples)
        assertEquals(1, staleSkips)
        controller.stop()
    }

    @Test
    fun `replacement generation cannot overlap an in-flight operation`() = runTest {
        var activeOperations = 0
        var maximumActiveOperations = 0
        var sampleCalls = 0
        val firstOperationStarted = CompletableDeferred<Unit>()
        val holdFirstOperation = CompletableDeferred<Unit>()
        val controller = VideoSyncController(
            scope = backgroundScope,
            sampleIntervalMs = 250L,
            ineligibility = { null },
            synchronize = {
                activeOperations += 1
                maximumActiveOperations = maxOf(maximumActiveOperations, activeOperations)
                sampleCalls += 1
                try {
                    if (sampleCalls == 1) {
                        firstOperationStarted.complete(Unit)
                        holdFirstOperation.await()
                    }
                } finally {
                    activeOperations -= 1
                }
            },
        )

        controller.start(generation = 1L)
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(firstOperationStarted.isCompleted)

        controller.start(generation = 2L)
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(1, maximumActiveOperations)

        holdFirstOperation.complete(Unit)
        runCurrent()
        advanceTimeBy(250L)
        runCurrent()

        assertEquals(1, maximumActiveOperations)
        assertTrue(sampleCalls >= 2)
        controller.stop()
    }

    @Test
    fun `starting the same live generation is idempotent`() = runTest {
        var starts = 0
        var stops = 0
        val controller = VideoSyncController(
            scope = backgroundScope,
            sampleIntervalMs = 250L,
            ineligibility = { null },
            synchronize = {},
            onLoopStarted = { starts += 1 },
            onLoopStopped = { stops += 1 },
        )

        controller.start(generation = 7L)
        controller.start(generation = 7L)
        runCurrent()

        assertEquals(1, starts)
        controller.stop()
        runCurrent()
        assertEquals(1, stops)
    }
}
