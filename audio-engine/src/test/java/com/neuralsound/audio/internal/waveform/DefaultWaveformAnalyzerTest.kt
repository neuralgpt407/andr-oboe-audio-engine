package com.neuralsound.audio.internal.waveform

import android.os.ParcelFileDescriptor
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.internal.NativeWaveformBridge
import com.neuralsound.audio.internal.NativeWaveformFailure
import com.neuralsound.audio.internal.WaveformAnalyzerRuntime
import com.neuralsound.audio.waveform.Waveform
import com.neuralsound.audio.waveform.WaveformAnalyzer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** FR-25 / S1-AS9: the public analyzer keeps algorithm v3 and the 1024-level bound. */
@RunWith(RobolectricTestRunner::class)
class DefaultWaveformAnalyzerTest {
    private val file = File.createTempFile("waveform", ".wav").apply { writeBytes(ByteArray(64)); deleteOnExit() }

    private fun descriptor() = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private fun analyzer(bridge: NativeWaveformBridge, native: Boolean = true) = DefaultWaveformAnalyzer(
        WaveformAnalyzerRuntime(dispatcher = Dispatchers.Default, nativeBridge = bridge, nativeLibraryLoader = { native }),
    )

    @Test
    fun keepsTheVersion3ContractAndBound() {
        assertEquals(3, DefaultWaveformAnalyzer.ALGORITHM_VERSION)
        assertEquals(3, analyzer(Bridge(floatArrayOf())).algorithmVersion)
        assertEquals(1024, WaveformAnalyzer.MAX_LEVELS)
    }

    @Test
    fun successReturnsLevelsTaggedWithTheAlgorithmVersion() = runBlocking {
        val bridge = Bridge(floatArrayOf(0.1f, 0.5f, 0.25f))
        val result = descriptor().use { analyzer(bridge).analyze(it, maxLevels = 3) }

        assertEquals(AudioResult.Ok(Waveform(floatArrayOf(0.1f, 0.5f, 0.25f), 3)), result)
        assertEquals(3, bridge.requestedLevels)
    }

    @Test
    fun nativeFailuresMapToTheSingleErrorModel() = runBlocking {
        val cases = mapOf(
            NativeWaveformFailure.UNREADABLE to AudioError.FileError,
            NativeWaveformFailure.UNSUPPORTED to AudioError.Native("waveform:unsupported-codec"),
            NativeWaveformFailure.CORRUPT to AudioError.Native("waveform:corrupt-media"),
            NativeWaveformFailure.EMPTY_MEDIA to AudioError.Native("waveform:empty-media"),
        )
        cases.forEach { (failure, expected) ->
            val result = descriptor().use { analyzer(Bridge(null, failure)).analyze(it) }
            assertEquals(failure.name, AudioResult.Err(expected), result)
        }
    }

    @Test
    fun outOfRangeLevelCountsAreRejectedBeforeNativeWork() = runBlocking {
        val bridge = Bridge(floatArrayOf(0.1f))
        val tooMany = descriptor().use { analyzer(bridge).analyze(it, maxLevels = 2048) }
        val none = descriptor().use { analyzer(bridge).analyze(it, maxLevels = 0) }

        assertTrue(tooMany.errorOrNull() is AudioError.InvalidConfiguration)
        assertTrue(none.errorOrNull() is AudioError.InvalidConfiguration)
        assertEquals(0, bridge.createCount)
    }

    @Test
    fun unavailableNativeCodeIsTyped() = runBlocking {
        val result = descriptor().use { analyzer(Bridge(floatArrayOf()), native = false).analyze(it) }
        assertEquals(AudioResult.Err(AudioError.Unavailable), result)
    }

    @Test
    fun cancellingTheCallerCancelsNativeAnalysis() = runBlocking {
        val bridge = Bridge(floatArrayOf(0.2f), blockUntilCancelled = true)
        val pfd = descriptor()
        val job = async(Dispatchers.Default) { analyzer(bridge).analyze(pfd) }
        assertTrue(bridge.started.await(2, TimeUnit.SECONDS))
        job.cancel()
        val outcome = runCatching { job.await() }
        pfd.close()

        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertTrue(bridge.cancelled.await(2, TimeUnit.SECONDS))
    }

    private class Bridge(
        private val levels: FloatArray?,
        private val failure: NativeWaveformFailure = NativeWaveformFailure.CORRUPT,
        private val blockUntilCancelled: Boolean = false,
    ) : NativeWaveformBridge {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        var createCount = 0
        var requestedLevels = -1

        override fun create(fd: Int, maxOutputSamples: Int): Long {
            createCount++
            requestedLevels = maxOutputSamples
            return 1L
        }

        override fun analyze(handle: Long): FloatArray? {
            started.countDown()
            if (blockUntilCancelled) {
                cancelled.await(2, TimeUnit.SECONDS)
                return null
            }
            return levels
        }

        override fun failure(handle: Long): Int =
            if (blockUntilCancelled && cancelled.count == 0L) NativeWaveformFailure.CANCELLED.code else failure.code

        override fun cancel(handle: Long) = cancelled.countDown()
        override fun release(handle: Long) = Unit
    }
}
