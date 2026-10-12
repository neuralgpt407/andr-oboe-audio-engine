package com.neuralsound.audio

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlayerSource
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

/** NFR-1: 8 stems + boost + limiter + pitch/tempo for 60 s: callback p99 <= 50% of the buffer, render keeps up, no underruns. */
@RunWith(AndroidJUnit4::class)
class PerformanceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun eightStemsWithTheFullChainStayInsideTheRealTimeBudget() = runBlocking {
        val files = (0 until 8).map { stem("perf-stem-$it.wav", 180.0 + it * 95.0) }
        val player = AudioEngine.create(context).newPlayer()
        try {
            val tracks = files.mapIndexed { i, f -> Track(TrackId("stem$i"), Uri.fromFile(f), TrackSettings(volume = 0.6f, boostDb = 6)) }
            assertEquals(AudioResult.Done, player.load(PlayerSource(tracks = tracks, loop = true, pitchTempo = PitchTempo(semitones = 2, tempo = 1.25f))))
            player.play()
            withTimeout(3_000L) { player.state.first { it.isPlaying } }
            delay(2_000L)
            player.diagnostics(reset = true)
            delay(60_000L)
            val stats = player.diagnostics()!!
            Log.i("FEAT-0001-perf", "$stats callbackLoadP99=${stats.callbackLoadP99}")
            assertTrue("callbacks recorded", stats.callbacks > 1_000)
            assertTrue("callback p99 load ${stats.callbackLoadP99}", stats.callbackLoadP99 <= 0.5f)
            // The render thread must keep up (< 1.0 = faster than real time); NFR-1 bounds the callback.
            assertTrue("render p99 load ${stats.renderLoadP99}", stats.renderLoadP99 < 1.0f)
            assertEquals("underruns", 0L, stats.underruns)
            assertTrue(player.state.value.isPlaying)
        } finally {
            player.release()
            files.forEach(File::delete)
        }
    }

    private fun stem(name: String, hz: Double, sampleRate: Int = 44_100, seconds: Int = 20): File {
        val file = File(context.cacheDir, name)
        val frames = sampleRate * seconds
        val dataBytes = frames * 4
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate)
            .putInt(sampleRate * 4).putShort(4).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (f in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * hz * f / sampleRate) * 3_000).toInt().toShort()
            buffer.putShort(v).putShort(v)
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
        return file
    }
}
