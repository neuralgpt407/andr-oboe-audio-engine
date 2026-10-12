package com.neuralsound.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.recorder.RecorderStatus
import com.neuralsound.audio.recorder.TakeMode
import com.neuralsound.audio.recorder.TakeRequest
import com.neuralsound.audio.recorder.WaveformBuckets
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** S4-AS1 / S4-AS2 / S4-AS4: 44.1 kHz mono takes from any device rate, punch-in, warm mic. */
@RunWith(AndroidJUnit4::class)
class OboeRecorderResampleDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantMicPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.RECORD_AUDIO")
    }

    @Test
    fun recordsCanonical44kWavAtRealTimeRate() = runBlocking {
        val outputFile = File(context.cacheDir, "recorder-resample-test.wav").apply { delete() }
        val recorder = AudioEngine.create(context).newRecorder()
        try {
            assertTrue(recorder.openMic().isOk)
            val take = recorder.record(TakeRequest(outputFile)).getOrNull()!!
            val buckets = mutableListOf<WaveformBuckets>()
            val collector = launch(Dispatchers.Default) { take.waveform.toList(buckets) }
            val startNs = System.nanoTime()
            delay(8_000L)
            val elapsedSeconds = (System.nanoTime() - startNs) / 1_000_000_000.0
            val result = take.stop().getOrNull()!!
            collector.cancel()

            val header = readWavHeader(outputFile)
            assertEquals(Recorder.SAMPLE_RATE, header.sampleRate)
            assertEquals(1, header.channels)
            assertEquals(16, header.bitsPerSample)
            val effectiveRate = result.frames / elapsedSeconds
            assertTrue("effective rate $effectiveRate", effectiveRate in Recorder.SAMPLE_RATE * 0.95..Recorder.SAMPLE_RATE * 1.05)
            assertTrue("about one bucket per 32 ms, got ${buckets.sumOf { it.rms.size }}", buckets.sumOf { it.rms.size } in 200..260)
            assertEquals(RecorderStatus.MIC_OPEN, recorder.state.value.status)
        } finally {
            recorder.release()
            outputFile.delete()
        }
    }

    @Test
    fun punchInKeepsEarlierAudioAndTheWarmMicServesRepeatedTakes() = runBlocking {
        val file = File(context.cacheDir, "recorder-punch-in.wav").apply { delete() }
        val recorder = AudioEngine.create(context).newRecorder()
        try {
            assertTrue(recorder.openMic().isOk)
            val first = recorder.record(TakeRequest(file)).getOrNull()!!
            withTimeout(5_000L) { first.writtenFrames.first { it >= Recorder.framesForMs(3_000) } }
            first.stop()
            val before = file.readBytes()
            val punchFrame = Recorder.framesForMs(1_000)

            repeat(3) {
                val take = recorder.record(TakeRequest(file, startFrame = punchFrame, mode = TakeMode.PUNCH_IN)).getOrNull()!!
                delay(300L)
                assertTrue(take.stop().isOk)
            }

            val after = file.readBytes()
            val untouchedBytes = (44 + (punchFrame - Recorder.framesForMs(10)) * 2).toInt()
            assertArrayEquals(before.copyOfRange(44, untouchedBytes), after.copyOfRange(44, untouchedBytes))
            assertTrue(after.size >= before.size)
        } finally {
            recorder.release()
            file.delete()
        }
    }

    private fun readWavHeader(file: File): WavHeader {
        assertTrue("WAV file was not created", file.exists() && file.length() >= 44)
        val bytes = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return WavHeader(buffer.getInt(24), buffer.getShort(22).toInt(), buffer.getShort(34).toInt())
    }

    private data class WavHeader(val sampleRate: Int, val channels: Int, val bitsPerSample: Int)
}
