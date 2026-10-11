package com.neuralsound.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackSettings
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSmokeDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = AudioEngine.create(context)

    @Test
    fun loadsMixedRateAndMultichannelTracksOnOneTimeline() = runBlocking {
        val original = silentWav("player-format-48000.wav", sampleRate = 48_000, channelCount = 4)
        val denoised = silentWav("player-format-44100.wav", sampleRate = 44_100, channelCount = 1)
        val player = engine.newPlayer()
        try {
            val result = player.load(
                PlayerSource(
                    tracks = listOf(
                        Track(TrackId("original"), Uri.fromFile(original)),
                        Track(TrackId("denoised"), Uri.fromFile(denoised)),
                    ),
                ),
            )
            assertEquals(AudioResult.Done, result)
            assertEquals(PlayerStatus.READY, player.state.value.status)
            assertEquals(1_000L, player.state.value.durationMs)
        } finally {
            player.release()
            original.delete()
            denoised.delete()
        }
    }

    @Test
    fun loadAppendSwitchSeekAndPitchTempo() = runBlocking {
        val original = silentWav("player-smoke-original.wav", sampleRate = 48_000)
        val denoised = silentWav("player-smoke-denoised.wav", sampleRate = 44_100, channelCount = 1)
        val player = engine.newPlayer()
        val originalId = TrackId("original")
        val vocalId = TrackId("vocal")
        try {
            assertEquals(
                AudioResult.Done,
                player.load(
                    PlayerSource(
                        tracks = listOf(Track(originalId, Uri.fromFile(original))),
                        pitchTempo = PitchTempo(semitones = 2, tempo = 1.1f),
                    ),
                ),
            )
            assertFalse(player.state.value.loop)
            assertEquals(PitchTempo(semitones = 2, tempo = 1.1f), player.state.value.pitchTempo)

            assertEquals(
                AudioResult.Done,
                player.appendTrack(Track(vocalId, Uri.fromFile(denoised), TrackSettings(muted = true))),
            )
            assertTrue(player.state.value.tracks.getValue(vocalId).settings.muted)

            player.updateTrack(originalId) { it.copy(muted = true) }
            player.updateTrack(vocalId) { it.copy(muted = false) }
            withTimeout(2_000L) {
                player.state.first { !it.tracks.getValue(vocalId).settings.muted }
            }
            assertTrue(player.state.value.tracks.getValue(originalId).settings.muted)

            player.play()
            withTimeout(2_000L) { player.state.first { it.status == PlayerStatus.PLAYING } }
            player.setPitchTempo(PitchTempo(semitones = -2, tempo = 0.9f))
            player.seekTo(900L)
            withTimeout(3_000L) {
                player.state.first { it.positionMs >= 1_000L && it.status != PlayerStatus.PLAYING }
            }
            assertEquals(PitchTempo(semitones = -2, tempo = 0.9f), player.state.value.pitchTempo)
        } finally {
            player.release()
            assertEquals(PlayerStatus.RELEASED, player.state.value.status)
            original.delete()
            denoised.delete()
        }
    }

    private fun silentWav(name: String, sampleRate: Int, channelCount: Int = 2, durationMs: Int = 1_000): File {
        val file = File(context.cacheDir, name)
        val frames = sampleRate * durationMs / 1_000
        val dataBytes = frames * channelCount * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channelCount.toShort())
            putInt(sampleRate); putInt(sampleRate * channelCount * 2); putShort((channelCount * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        FileOutputStream(file).use { out ->
            out.write(header.array())
            out.write(ByteArray(dataBytes))
        }
        return file
    }
}
