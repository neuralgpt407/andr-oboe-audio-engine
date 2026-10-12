package com.neuralsound.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neuralsound.audio.player.FocusPolicy
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
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

/** FR-13 / S3-AS1 / S3-AS2: independent players; closing one never affects the other; snapshot hand-off. */
@RunWith(AndroidJUnit4::class)
class ConcurrentPlayersDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = AudioEngine.create(context)

    @Test
    fun releasingOnePlayerLeavesTheOtherPlaying() = runBlocking {
        val a = tone("concurrent-a.wav", 330.0)
        val b = tone("concurrent-b.wav", 550.0)
        val player = engine.newPlayer()
        val preview = engine.newPlayer(PlayerOptions(focus = FocusPolicy.Ignore))
        try {
            player.load(PlayerSource(tracks = listOf(Track(TrackId("song"), Uri.fromFile(a))), autoPlay = true))
            preview.load(PlayerSource(tracks = listOf(Track(TrackId("take"), Uri.fromFile(b))), autoPlay = true))
            withTimeout(3_000L) { player.state.first { it.isPlaying } }
            withTimeout(3_000L) { preview.state.first { it.isPlaying } }

            preview.release()
            val before = player.state.value.positionMs
            delay(700L)
            assertEquals(PlayerStatus.RELEASED, preview.state.value.status)
            assertEquals(PlayerStatus.PLAYING, player.state.value.status)
            assertTrue(player.state.value.positionMs > before + 400)
        } finally {
            player.release()
            preview.release()
            a.delete()
            b.delete()
        }
    }

    @Test
    fun detachAndRestoreContinuesInANewPlayer() = runBlocking {
        val a = tone("handoff-a.wav", 330.0)
        val preview = engine.newPlayer()
        val player = engine.newPlayer()
        try {
            preview.load(PlayerSource(tracks = listOf(Track(TrackId("song"), Uri.fromFile(a))), autoPlay = true))
            withTimeout(3_000L) { preview.state.first { it.isPlaying } }
            preview.seekTo(2_000L)
            delay(400L)
            val snapshot = preview.detach()!!
            assertEquals(AudioResult.Done, player.restore(snapshot))
            withTimeout(3_000L) { player.state.first { it.isPlaying } }
            assertTrue("restored at ${player.state.value.positionMs}", player.state.value.positionMs in snapshot.positionMs..snapshot.positionMs + 600)
        } finally {
            preview.release()
            player.release()
            a.delete()
        }
    }

    private fun tone(name: String, hz: Double, seconds: Int = 8, sampleRate: Int = 44_100): File {
        val file = File(context.cacheDir, name)
        val frames = sampleRate * seconds
        val dataBytes = frames * 4
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(sampleRate)
            .putInt(sampleRate * 4).putShort(4).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataBytes)
        for (f in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * hz * f / sampleRate) * 1_500).toInt().toShort()
            buffer.putShort(v).putShort(v)
        }
        FileOutputStream(file).use { it.write(buffer.array()) }
        return file
    }
}
