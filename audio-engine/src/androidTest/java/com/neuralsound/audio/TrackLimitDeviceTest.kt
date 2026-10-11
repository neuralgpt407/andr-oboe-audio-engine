package com.neuralsound.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** S1-AS3: ten stems play in sync with a limit of ten; the eleventh is rejected without stopping playback. */
@RunWith(AndroidJUnit4::class)
class TrackLimitDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tenStemsPlayAndTheEleventhIsRejected() = runBlocking {
        val files = (0..10).map { toneWav("limit-stem-$it.wav", hz = 200.0 + it * 110.0) }
        val player = AudioEngine.create(context).newPlayer(PlayerOptions(maxTracks = 10))
        try {
            val stems = files.take(10).mapIndexed { i, f -> Track(TrackId("stem$i"), Uri.fromFile(f)) }
            assertEquals(AudioResult.Done, player.load(PlayerSource(tracks = stems)))
            player.play()
            withTimeout(3_000L) { player.state.first { it.status == PlayerStatus.PLAYING } }

            val extra = player.appendTrack(Track(TrackId("stem10"), Uri.fromFile(files[10])))
            assertEquals(AudioResult.Err(AudioError.TrackLimitExceeded(10)), extra)

            val before = player.state.value.positionMs
            delay(500L)
            assertEquals(PlayerStatus.PLAYING, player.state.value.status)
            assertTrue(player.state.value.positionMs > before)
            assertEquals(10, player.state.value.tracks.size)
        } finally {
            player.release()
            files.forEach(File::delete)
        }
    }

    private fun toneWav(name: String, hz: Double, sampleRate: Int = 44_100, seconds: Int = 5): File {
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
