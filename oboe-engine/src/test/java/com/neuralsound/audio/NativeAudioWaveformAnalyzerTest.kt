package com.neuralsound.audio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioWaveformAnalyzerTest {
    @Test
    fun publicMetadataPreservesVersion3AndThe1024SampleBound() {
        assertEquals(3, NativeAudioWaveformAnalyzer.ALGORITHM_VERSION)
        assertEquals(1024, NativeAudioWaveformAnalyzer.MAX_OUTPUT_SAMPLES)
    }

    @Test
    fun nativeSuccessRetainsAbsoluteRmsLevelsAndReleasesResources() = runBlocking {
        val bridge = FakeWaveformBridge(levels = floatArrayOf(0f, 0.25f, 1f))
        val analyzer = analyzer(bridge)

        val result = analyzer.analyzeFileDescriptor(fd = 7, maxSamples = 3)

        assertTrue(result is NativeWaveformAnalysisResult.Success)
        assertArrayEquals(
            floatArrayOf(0f, 0.25f, 1f),
            (result as NativeWaveformAnalysisResult.Success).levels,
            0f,
        )
        assertEquals(1, bridge.releaseCalls.get())
    }

    @Test
    fun everyNativeFailureKindMapsToADistinctPublicError() = runBlocking {
        val expectedErrors = mapOf(
            NativeWaveformFailure.UNREADABLE to NativeWaveformAnalysisError.Unreadable,
            NativeWaveformFailure.UNSUPPORTED to NativeWaveformAnalysisError.Unsupported,
            NativeWaveformFailure.CORRUPT to NativeWaveformAnalysisError.Corrupt,
            NativeWaveformFailure.EMPTY_MEDIA to NativeWaveformAnalysisError.EmptyMedia,
            NativeWaveformFailure.CANCELLED to NativeWaveformAnalysisError.Cancelled,
            NativeWaveformFailure.INVALID_ARGUMENT to NativeWaveformAnalysisError.InvalidArgument,
        )

        expectedErrors.forEach { (failure, expectedError) ->
            assertEquals(failure, NativeWaveformFailure.fromCode(failure.code))
            val bridge = FakeWaveformBridge(levels = null, failure = failure)

            val result = analyzer(bridge).analyzeFileDescriptor(fd = 7)

            assertEquals(
                NativeWaveformAnalysisResult.Failure(expectedError),
                result,
            )
            assertEquals(1, bridge.releaseCalls.get())
        }
    }

    @Test
    fun invalidInputsAndUnavailableNativeCodeDoNotCreateAHandle() = runBlocking {
        val invalidFdBridge = FakeWaveformBridge(levels = floatArrayOf(0f))
        assertEquals(
            NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.Unreadable,
            ),
            analyzer(invalidFdBridge).analyzeFileDescriptor(fd = -1),
        )
        assertEquals(0, invalidFdBridge.createCount)

        listOf(0, NativeAudioWaveformAnalyzer.MAX_OUTPUT_SAMPLES + 1).forEach { maxSamples ->
            val invalidBoundBridge = FakeWaveformBridge(levels = floatArrayOf(0f))
            assertEquals(
                NativeWaveformAnalysisResult.Failure(
                    NativeWaveformAnalysisError.InvalidArgument,
                ),
                analyzer(invalidBoundBridge).analyzeFileDescriptor(
                    fd = 7,
                    maxSamples = maxSamples,
                ),
            )
            assertEquals(0, invalidBoundBridge.createCount)
        }

        val unavailableBridge = FakeWaveformBridge(levels = floatArrayOf(0f))
        assertEquals(
            NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.NativeUnavailable,
            ),
            analyzer(unavailableBridge, nativeAvailable = false)
                .analyzeFileDescriptor(fd = 7),
        )
        assertEquals(0, unavailableBridge.createCount)

        val noHandleBridge = FakeWaveformBridge(
            levels = floatArrayOf(0f),
            createdHandle = 0L,
        )
        assertEquals(
            NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.NativeUnavailable,
            ),
            analyzer(noHandleBridge).analyzeFileDescriptor(fd = 7),
        )
        assertEquals(1, noHandleBridge.createCount)
        assertEquals(0, noHandleBridge.releaseCalls.get())
    }

    @Test
    fun malformedNativeEnvelopesAreRejectedAndReleased() = runBlocking {
        val malformed = listOf(
            floatArrayOf(),
            FloatArray(NativeAudioWaveformAnalyzer.MAX_OUTPUT_SAMPLES + 1),
            floatArrayOf(Float.NaN),
            floatArrayOf(-0.01f),
            floatArrayOf(1.01f),
        )

        malformed.forEach { levels ->
            val bridge = FakeWaveformBridge(levels = levels)

            val result = analyzer(bridge).analyzeFileDescriptor(fd = 7)

            assertEquals(
                NativeWaveformAnalysisResult.Failure(
                    NativeWaveformAnalysisError.Corrupt,
                ),
                result,
            )
            assertEquals(1, bridge.releaseCalls.get())
        }
    }

    @Test
    fun cancellationSignalsNativeBeforeReleasingTheHandle() = runBlocking {
        val bridge = FakeWaveformBridge(
            levels = null,
            blockUntilCancelled = true,
        )
        val analyzer = analyzer(bridge)
        val job = launch(Dispatchers.Default) {
            analyzer.analyzeFileDescriptor(fd = 7)
        }

        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue("native cancellation must be prompt", bridge.cancelled.await(2, TimeUnit.SECONDS))
        assertTrue("native resources must be released", bridge.released.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("cancel", "release"), bridge.lifecycleEvents.toList())
        assertEquals(1, bridge.releaseCalls.get())
    }

    @Test
    fun explicitCancellationReturnsTheTypedCancelledResult() = runBlocking {
        val bridge = FakeWaveformBridge(
            levels = null,
            failure = NativeWaveformFailure.CANCELLED,
            blockUntilCancelled = true,
        )
        val analyzer = analyzer(bridge)
        val result = async(Dispatchers.Default) {
            analyzer.analyzeFileDescriptor(fd = 7)
        }

        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        analyzer.cancel()

        assertEquals(
            NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.Cancelled,
            ),
            result.await(),
        )
        assertEquals(listOf("cancel", "release"), bridge.lifecycleEvents.toList())
    }

    @Test
    fun linkageFailureIsTypedAndStillReleasesTheHandle() = runBlocking {
        val bridge = FakeWaveformBridge(
            levels = null,
            analyzeFailure = UnsatisfiedLinkError("missing waveform symbol"),
        )

        val result = analyzer(bridge).analyzeFileDescriptor(fd = 7)

        assertEquals(
            NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.NativeUnavailable,
            ),
            result,
        )
        assertEquals(1, bridge.releaseCalls.get())
    }

    private fun analyzer(
        bridge: NativeAudioWaveformAnalyzer.NativeWaveformBridge,
        nativeAvailable: Boolean = true,
    ) = NativeAudioWaveformAnalyzer(
        dispatcher = Dispatchers.Default,
        nativeBridge = bridge,
        nativeLibraryLoader = { nativeAvailable },
    )

    private class FakeWaveformBridge(
        private val levels: FloatArray?,
        private val failure: NativeWaveformFailure = NativeWaveformFailure.CORRUPT,
        private val blockUntilCancelled: Boolean = false,
        private val analyzeFailure: Throwable? = null,
        private val createdHandle: Long = 1L,
    ) : NativeAudioWaveformAnalyzer.NativeWaveformBridge {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val released = CountDownLatch(1)
        val releaseCalls = AtomicInteger(0)
        val lifecycleEvents = CopyOnWriteArrayList<String>()
        var createCount: Int = 0
            private set

        override fun create(
            owner: NativeAudioWaveformAnalyzer,
            fd: Int,
            maxOutputSamples: Int,
        ): Long {
            ++createCount
            return createdHandle
        }

        override fun analyze(
            owner: NativeAudioWaveformAnalyzer,
            handle: Long,
        ): FloatArray? {
            started.countDown()
            if (blockUntilCancelled) {
                check(cancelled.await(2, TimeUnit.SECONDS)) {
                    "native cancellation was not forwarded"
                }
            }
            analyzeFailure?.let { throw it }
            return levels
        }

        override fun failure(
            owner: NativeAudioWaveformAnalyzer,
            handle: Long,
        ): NativeWaveformFailure = failure

        override fun cancel(owner: NativeAudioWaveformAnalyzer, handle: Long) {
            lifecycleEvents += "cancel"
            cancelled.countDown()
        }

        override fun release(owner: NativeAudioWaveformAnalyzer, handle: Long) {
            lifecycleEvents += "release"
            releaseCalls.incrementAndGet()
            released.countDown()
        }
    }
}
