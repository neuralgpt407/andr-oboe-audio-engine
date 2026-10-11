package com.neuralsound.audio

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.neuralsound.audio.waveform.Waveform
import com.neuralsound.audio.waveform.WaveformAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveformAnalyzerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val analyzer = AudioEngine.create(context).waveforms

    @Test
    fun analyzerDecodesAndClassifiesDescriptorsWithRepeatCleanup() = runBlocking {
        val valid = writeSineWav(File(context.cacheDir, "waveform-valid.wav"))
        val malformed = File(context.cacheDir, "waveform-malformed.bin").apply {
            writeBytes(byteArrayOf(0x01, 0x23, 0x45, 0x67))
        }
        try {
            assertEquals(3, analyzer.algorithmVersion)
            repeat(3) {
                val waveform = analyze(valid).getOrNull()
                assertNotNull(waveform)
                val levels = waveform!!.levels
                assertTrue(levels.isNotEmpty())
                assertTrue(levels.size <= WaveformAnalyzer.MAX_LEVELS)
                assertTrue(levels.all { it.isFinite() && it in 0f..1f })
                assertEquals(3, waveform.algorithmVersion)
            }
            val error = analyze(malformed).errorOrNull()
            assertTrue("$error", error is AudioError.Native)
        } finally {
            valid.delete()
            malformed.delete()
        }
    }

    @Test
    fun cancellingTheCallerStopsAnalysisAndTheAnalyzerStaysReusable() = runBlocking {
        val longSource = writeSparseWav(File(context.cacheDir, "waveform-cancel.wav"), durationSeconds = 30 * 60)
        val valid = writeSineWav(File(context.cacheDir, "waveform-after-cancel.wav"))
        try {
            val descriptor = ParcelFileDescriptor.open(longSource, ParcelFileDescriptor.MODE_READ_ONLY)
            val pending = async(Dispatchers.IO) { descriptor.use { analyzer.analyze(it) } }
            delay(50L)
            pending.cancel()
            val outcome = withTimeout(10_000L) { runCatching { pending.await() } }
            assertTrue(outcome.exceptionOrNull() is CancellationException)
            assertTrue(analyze(valid).isOk)
        } finally {
            longSource.delete()
            valid.delete()
        }
    }

    private suspend fun analyze(file: File): AudioResult<Waveform> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { analyzer.analyze(it) }

    private fun writeSineWav(file: File): File {
        val sampleRate = 44_100
        val frameCount = sampleRate * 2
        val samples = ShortArray(frameCount) { frame ->
            val phase = 2.0 * Math.PI * 440.0 * frame.toDouble() / sampleRate.toDouble()
            (kotlin.math.sin(phase) * Short.MAX_VALUE * 0.5).toInt().toShort()
        }
        val dataSize = samples.size * Short.SIZE_BYTES

        FileOutputStream(file).use { output ->
            output.write(wavHeader(sampleRate, dataSize))
            val data = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach(data::putShort)
            output.write(data.array())
        }
        return file
    }

    private fun writeSparseWav(file: File, durationSeconds: Int): File {
        val sampleRate = 44_100
        val dataSize = sampleRate * durationSeconds * Short.SIZE_BYTES
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(44L + dataSize)
            output.seek(0L)
            output.write(wavHeader(sampleRate, dataSize))
        }
        return file
    }

    private fun wavHeader(sampleRate: Int, dataSize: Int): ByteArray {
        return ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(sampleRate * Short.SIZE_BYTES)
                putShort(Short.SIZE_BYTES.toShort())
                putShort(16)
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }
            .array()
    }
}
