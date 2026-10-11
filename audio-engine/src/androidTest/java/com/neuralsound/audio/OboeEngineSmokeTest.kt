package com.neuralsound.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OboeEngineSmokeTest {
    @Test
    fun preparesMixedRateAndMultichannelTracksOnOneTimeline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = createSilentWav(
            file = File(context.cacheDir, "oboe-format-48000.wav"),
            sampleRate = 48_000,
            channelCount = 4,
        )
        val denoised = createSilentWav(
            file = File(context.cacheDir, "oboe-format-44100.wav"),
            sampleRate = 44_100,
            channelCount = 1,
        )
        val mixer = AudioEngine(context).createMixerSession()

        try {
            assertEquals(
                AudioResult.Success,
                mixer.prepare(
                    MixerRequest(
                        tracks = listOf(
                            MixerTrack(TrackId("original"), Uri.fromFile(original)),
                            MixerTrack(TrackId("denoised"), Uri.fromFile(denoised)),
                        )
                    )
                )
            )
            assertEquals(MixerStatus.READY, mixer.state.value.status)
            assertEquals(1_000L, mixer.state.value.durationMs)
        } finally {
            mixer.close()
            original.delete()
            denoised.delete()
        }
    }

    @Test
    fun prepareAppendSwitchSeekAndEffects() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = createSilentWav(
            file = File(context.cacheDir, "oboe-smoke-original.wav"),
            sampleRate = 48_000,
        )
        val denoised = createSilentWav(
            file = File(context.cacheDir, "oboe-smoke-denoised.wav"),
            sampleRate = 44_100,
            channelCount = 1,
        )
        val mixer = AudioEngine(context).createMixerSession()
        val originalId = TrackId("original")
        val vocalId = TrackId("vocal")

        try {
            assertEquals(
                AudioResult.Success,
                mixer.prepare(
                    MixerRequest(
                        tracks = listOf(
                            MixerTrack(
                                id = originalId,
                                uri = Uri.fromFile(original),
                                mix = TrackMix(volume = 1f),
                            )
                        ),
                        looping = false,
                        effects = PlaybackEffects(tempo = 1.1f, pitchSemitones = 2),
                    )
                ),
            )
            assertFalse(mixer.state.value.looping)
            assertEquals(
                PlaybackEffects(tempo = 1.1f, pitchSemitones = 2),
                mixer.state.value.effects,
            )
            assertEquals(1f, mixer.state.value.tracks.getValue(originalId).mix.volume)
            assertFalse(mixer.state.value.tracks.getValue(originalId).mix.muted)

            assertEquals(
                AudioResult.Success,
                mixer.appendTrack(
                    MixerTrack(
                        id = vocalId,
                        uri = Uri.fromFile(denoised),
                        mix = TrackMix(volume = 1f, muted = true),
                    )
                ),
            )
            assertTrue(mixer.state.value.tracks.getValue(vocalId).mix.muted)

            mixer.updateTrackMix(originalId, TrackMix(volume = 1f, muted = true))
            mixer.updateTrackMix(vocalId, TrackMix(volume = 1f, muted = false))
            assertTrue(mixer.state.value.tracks.getValue(originalId).mix.muted)
            assertFalse(mixer.state.value.tracks.getValue(vocalId).mix.muted)

            assertEquals(AudioResult.Success, mixer.play())
            delay(100L)
            val effects = PlaybackEffects(tempo = 0.9f, pitchSemitones = -2)
            assertEquals(AudioResult.Success, mixer.setEffects(effects))
            assertEquals(effects, mixer.state.value.effects)
            assertEquals(AudioResult.Success, mixer.seekTo(900L))
            withTimeout(3_000L) {
                while (
                    mixer.state.value.positionMs < 1_000L ||
                    mixer.state.value.status != MixerStatus.PAUSED
                ) {
                    delay(20L)
                }
            }
            assertEquals(1_000L, mixer.state.value.positionMs)
            assertEquals(MixerStatus.PAUSED, mixer.state.value.status)
        } finally {
            mixer.close()
            original.delete()
            denoised.delete()
        }
    }

    private fun createSilentWav(
        file: File,
        sampleRate: Int = 44_100,
        channelCount: Int = 2,
    ): File {
        val bitsPerSample = 16
        val frameCount = sampleRate
        val dataSize = frameCount * channelCount * (bitsPerSample / 8)
        val header = ByteBuffer.allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)
                putShort(1)
                putShort(channelCount.toShort())
                putInt(sampleRate)
                putInt(sampleRate * channelCount * (bitsPerSample / 8))
                putShort((channelCount * (bitsPerSample / 8)).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataSize)
            }
            .array()

        FileOutputStream(file).use { output ->
            output.write(header)
            output.write(ByteArray(dataSize))
        }
        return file
    }
}
