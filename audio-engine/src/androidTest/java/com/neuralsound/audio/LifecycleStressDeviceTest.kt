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
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** S3-AS8 / S3-AS9: concurrent commands never crash or deadlock; append after the end restarts audio. */
@RunWith(AndroidJUnit4::class)
class LifecycleStressDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun randomConcurrentCommandsThenReleaseLeaveATypedReleasedPlayer() = runBlocking {
        val files = (0 until 6).map { tone("stress-$it.wav", 220.0 + it * 110, seconds = 8) }
        val player = AudioEngine.create(context).newPlayer()
        try {
            assertEquals(AudioResult.Done, player.load(PlayerSource(tracks = listOf(Track(TrackId("t0"), Uri.fromFile(files[0]))))))
            withTimeout(30_000L) {
                (0 until 4).map { worker ->
                    async(Dispatchers.Default) {
                        val random = Random(worker)
                        repeat(50) { step ->
                            when (random.nextInt(6)) {
                                0 -> player.play()
                                1 -> player.pause()
                                2 -> player.seekTo(random.nextLong(0, 8_000))
                                3 -> player.seekAndPlay(random.nextLong(0, 8_000))
                                4 -> player.setPitchTempo(com.neuralsound.audio.player.PitchTempo(random.nextInt(-3, 3), 0.75f + random.nextFloat()))
                                else -> {
                                    val i = 1 + (worker + step) % 5
                                    player.appendTrack(Track(TrackId("t$i"), Uri.fromFile(files[i])))
                                }
                            }
                            delay(random.nextLong(0, 15))
                        }
                    }
                }.awaitAll()
            }
            player.pause()
        } finally {
            withTimeout(10_000L) { player.release() }
        }
        assertEquals(PlayerStatus.RELEASED, player.state.value.status)
        assertEquals(AudioResult.Err(AudioError.Released), player.load(PlayerSource(tracks = listOf(Track(TrackId("t0"), Uri.fromFile(files[0]))))))
        assertEquals(AudioResult.Err(AudioError.Released), player.appendTrack(Track(TrackId("t9"), Uri.fromFile(files[1]))))
        files.forEach(File::delete)
    }

    @Test
    fun appendingAfterTheEndRestartsRendering() = runBlocking {
        val short = tone("eos-short.wav", 330.0, seconds = 1)
        val long = tone("eos-long.wav", 440.0, seconds = 4)
        val player = AudioEngine.create(context).newPlayer()
        try {
            assertEquals(AudioResult.Done, player.load(PlayerSource(tracks = listOf(Track(TrackId("short"), Uri.fromFile(short))), autoPlay = true)))
            withTimeout(5_000L) { player.state.first { it.status == PlayerStatus.ENDED } }
            assertEquals(AudioResult.Done, player.appendTrack(Track(TrackId("long"), Uri.fromFile(long))))
            player.play()
            withTimeout(3_000L) { player.state.first { it.status == PlayerStatus.PLAYING } }
            val start = player.state.value.positionMs
            delay(800L)
            assertTrue("position advanced from $start to ${player.state.value.positionMs}", player.state.value.positionMs > start + 300)
            assertTrue(player.state.value.durationMs >= 3_900L)
        } finally {
            player.release()
            short.delete()
            long.delete()
        }
    }

    private fun tone(name: String, hz: Double, seconds: Int, sampleRate: Int = 44_100): File {
        val file = File(context.cacheDir, name)
        val frames = sampleRate * seconds
        val dataBytes = frames * 4
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate)
            .putInt(sampleRate * 4).putShort(4).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (f in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * hz * f / sampleRate) * 2_000).toInt().toShort()
            buffer.putShort(v).putShort(v)
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
        return file
    }
}
