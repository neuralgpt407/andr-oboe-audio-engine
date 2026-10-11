package com.neuralsound.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neuralsound.audio.internal.NativeMixerController
import com.neuralsound.audio.internal.player.DefaultPlayer
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackSettings
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S2-AS1 / S2-AS2: +18 dB on a full-scale source never exceeds 0.98 before the safety clamp. */
@RunWith(AndroidJUnit4::class)
class SignalChainDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun boostedFullScaleToneIsHeldByTheLimiterNotTheClamp() = runBlocking {
        val tone = fullScaleTone(File(context.cacheDir, "chain-fullscale.wav"))
        val controller = NativeMixerController(context)
        val player = DefaultPlayer(controller, PlayerOptions(), NativeMixerController.nativeLibraryAvailable)
        val id = TrackId("tone")
        try {
            val loaded = player.load(PlayerSource(tracks = listOf(Track(id, Uri.fromFile(tone), TrackSettings(boostDb = 18)))))
            assertEquals(AudioResult.Done, loaded)
            player.play()
            withTimeout(3_000L) { player.state.first { it.status == PlayerStatus.PLAYING } }
            delay(300L)
            controller.consumeOutputPeak()
            delay(1_500L)
            val boostedPeak = controller.consumeOutputPeak()
            assertTrue("pre-clamp peak $boostedPeak", boostedPeak in 0.9f..0.9805f)

            player.updateTrack(id) { it.copy(boostDb = 0, volume = 0.25f) }
            delay(300L)
            controller.consumeOutputPeak()
            delay(800L)
            val quietPeak = controller.consumeOutputPeak()
            assertTrue("unboosted peak $quietPeak", quietPeak in 0.2f..0.3f)
        } finally {
            player.release()
            tone.delete()
        }
    }

    private fun fullScaleTone(file: File, sampleRate: Int = 44_100, seconds: Int = 6): File {
        val frames = sampleRate * seconds
        val dataBytes = frames * 4
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate)
            .putInt(sampleRate * 4).putShort(4).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (f in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * 1_000.0 * f / sampleRate) * 32_000).toInt().toShort()
            buffer.putShort(v).putShort(v)
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
        return file
    }
}
