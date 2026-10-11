package com.neuralsound.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S1-AS1 / FR-3 / EC-3: tracks with different rates and channel counts share one clock. */
@RunWith(AndroidJUnit4::class)
class MixedSampleRatePlaybackDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun originalAt48kDenoisedAt44kAndAFiveChannel96kStemPlayTogether() = runBlocking {
        val original = toneWav("mixed-48k-stereo.wav", sampleRate = 48_000, channels = 2, extensible = false)
        val denoised = toneWav("mixed-44k-mono.wav", sampleRate = 44_100, channels = 1, extensible = false)
        val surround = toneWav("mixed-96k-5ch.wav", sampleRate = 96_000, channels = 5, extensible = true)
        val player = AudioEngine.create(context).newPlayer()
        try {
            val result = player.load(
                PlayerSource(
                    tracks = listOf(
                        Track(TrackId("original"), Uri.fromFile(original)),
                        Track(TrackId("denoised"), Uri.fromFile(denoised)),
                        Track(TrackId("surround"), Uri.fromFile(surround)),
                    ),
                ),
            )
            assertEquals(AudioResult.Done, result)
            assertTrue(abs(player.state.value.durationMs - 4_000L) <= 50L)

            player.play()
            withTimeout(3_000L) { player.state.first { it.status == PlayerStatus.PLAYING } }
            val start = player.state.value.positionMs
            delay(1_000L)
            val advanced = player.state.value.positionMs - start
            assertTrue("advanced $advanced ms", advanced in 800L..1_200L)

            repeat(20) { toggle ->
                player.updateTrack(TrackId("original")) { it.copy(muted = toggle % 2 == 0) }
                player.updateTrack(TrackId("denoised")) { it.copy(muted = toggle % 2 == 1) }
                delay(25L)
            }
            assertEquals(PlayerStatus.PLAYING, player.state.value.status)
        } finally {
            player.release()
            listOf(original, denoised, surround).forEach(File::delete)
        }
    }

    @Test
    fun anUnreadableTrackIsNamedInTheError() = runBlocking {
        val good = toneWav("mixed-good.wav", sampleRate = 44_100, channels = 2, extensible = false)
        val bad = File(context.cacheDir, "mixed-unreadable.bin").apply { writeBytes(ByteArray(4096) { (it * 31).toByte() }) }
        val player = AudioEngine.create(context).newPlayer()
        try {
            val result = player.load(
                PlayerSource(
                    tracks = listOf(
                        Track(TrackId("good"), Uri.fromFile(good)),
                        Track(TrackId("bad"), Uri.fromFile(bad)),
                    ),
                ),
            )
            val error = result.errorOrNull()
            assertTrue("$error", error is AudioError.TrackFailed && error.trackId == TrackId("bad"))
            assertTrue(!error.toString().contains(bad.path))
        } finally {
            player.release()
            good.delete()
            bad.delete()
        }
    }

    private fun toneWav(name: String, sampleRate: Int, channels: Int, extensible: Boolean, seconds: Int = 4): File {
        val file = File(context.cacheDir, name)
        val frames = sampleRate * seconds
        val dataBytes = frames * channels * 2
        val fmtSize = if (extensible) 40 else 16
        val buffer = ByteBuffer.allocate(20 + fmtSize + 8 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(12 + fmtSize + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(fmtSize).putShort((if (extensible) 0xFFFE else 1).toShort())
            .putShort(channels.toShort()).putInt(sampleRate).putInt(sampleRate * channels * 2)
            .putShort((channels * 2).toShort()).putShort(16)
        if (extensible) {
            buffer.putShort(22).putShort(16).putInt((1 shl channels) - 1)
            buffer.putInt(1).putShort(0).putShort(0x10)
            buffer.put(byteArrayOf(0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        }
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (f in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * 330.0 * f / sampleRate) * 3_000).toInt().toShort()
            repeat(channels) { buffer.putShort(v) }
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
        return file
    }
}
