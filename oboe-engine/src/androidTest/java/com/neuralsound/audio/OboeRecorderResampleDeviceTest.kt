package com.neuralsound.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OboeRecorderResampleDeviceTest {
    private val canonicalRate = 44_100

    @Before
    fun grantMicPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(
            instrumentation.targetContext.packageName,
            "android.permission.RECORD_AUDIO",
        )
    }

    @Test
    fun recordsCanonical44kWavAtCorrectPitch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputFile = File(context.cacheDir, "oboe-recorder-resample-test.wav")
        outputFile.delete()
        val recorder = AudioEngine(context).createRecorderSession()

        try {
            assertEquals(RecorderStatus.IDLE, recorder.status.value)
            val micResult = recorder.startMicSession()
            assertTrue("startMicSession failed: ${micResult.message}", micResult.isSuccess)

            val writeResult = recorder.startWriting(RecordingRequest(outputFile))
            assertTrue("startWriting failed: ${writeResult.message}", writeResult.isSuccess)

            val recordMs = 8_000L
            val startNs = System.nanoTime()
            Thread.sleep(recordMs)
            val elapsedSeconds = (System.nanoTime() - startNs) / 1_000_000_000.0
            val result = recorder.stopWriting()

            assertTrue("recording failed / dropped frames: ${result.message}", result.isSuccess)
            assertEquals(canonicalRate, result.sampleRate)
            assertTrue("no frames written", result.writtenFrames > canonicalRate)

            val header = readWavHeader(outputFile)
            assertEquals("WAV header sample rate", canonicalRate, header.sampleRate)
            assertEquals("WAV header channels", 1, header.channels)
            assertEquals("WAV header bits/sample", 16, header.bitsPerSample)

            val effectiveRate = result.writtenFrames / elapsedSeconds
            val lowerBound = canonicalRate * 0.95
            val upperBound = canonicalRate * 1.05
            assertTrue(
                "effective rate $effectiveRate outside [$lowerBound, $upperBound]",
                effectiveRate in lowerBound..upperBound,
            )
            recorder.releaseMicSession()
        } finally {
            recorder.close()
            outputFile.delete()
        }
    }

    private fun readWavHeader(file: File): WavHeader {
        assertTrue("WAV file was not created", file.exists() && file.length() >= 44)
        val bytes = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return WavHeader(
            sampleRate = buffer.getInt(24),
            channels = buffer.getShort(22).toInt(),
            bitsPerSample = buffer.getShort(34).toInt(),
        )
    }

    private data class WavHeader(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )
}
