package com.neuralsound.audio.consumer

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.neuralsound.audio.AudioEngine
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.onErr
import com.neuralsound.audio.onOk
import com.neuralsound.audio.player.ChannelGain
import com.neuralsound.audio.player.FocusPolicy
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerEvent
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackSettings
import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.recorder.RecorderEvent
import com.neuralsound.audio.recorder.TakeMode
import com.neuralsound.audio.recorder.TakeRequest
import com.neuralsound.audio.waveform.WaveformAnalyzer
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * S6-AS1: a clean consumer compiles against the published artifact and touches every public
 * entry point. Native code cannot load under Robolectric, so calls must fail with typed errors
 * instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
class PublicApiSmokeTest {
    private val engine = AudioEngine.create(RuntimeEnvironment.getApplication())

    @Test
    fun playerSurfaceIsUsableAndFailsTypedWithoutNativeCode() = runTest {
        val player = engine.newPlayer(PlayerOptions(maxTracks = 10, focus = FocusPolicy.PauseAndResume))
        val source = PlayerSource(
            tracks = listOf(Track(TrackId("vocal"), Uri.parse("content://media/vocal"), TrackSettings(boostDb = 6, balance = ChannelGain.balance(-0.2f)))),
            range = PlaybackRange(0, 1_000),
            pitchTempo = PitchTempo(semitones = 2, tempo = 1.5f),
        )
        val result = player.load(source)
        result.onOk { error("native code is unavailable under Robolectric") }.onErr { assertEquals(AudioError.Unavailable, it) }

        player.play(); player.pause(); player.togglePlayPause()
        player.seekTo(10); player.seekToFraction(0.5f); player.seekBy(-10_000); player.seekAndPlay(0)
        player.setRange(null, loop = false)
        player.setPitchTempo(PitchTempo())
        player.updateTrack(TrackId("vocal")) { it.copy(muted = true) }
        player.video.attachPresentation(); player.video.suspendPresentation(); player.video.detachPresentation(); player.video.notifySurfaceRecreated()
        assertEquals(null, player.diagnostics())
        val events: kotlinx.coroutines.flow.Flow<PlayerEvent> = player.events
        assertTrue(events.toString().isNotEmpty())
        player.release()
        assertEquals(PlayerStatus.RELEASED, player.state.value.status)
        assertEquals(AudioResult.Err(AudioError.Released), player.restore(com.neuralsound.audio.player.PlayerSnapshot(source, 0, false)))
    }

    @Test
    fun recorderAndWaveformSurfacesExist() = runTest {
        val recorder: Recorder = engine.newRecorder()
        val request = TakeRequest(File("take.wav"), startFrame = Recorder.framesForMs(1_000), mode = TakeMode.PUNCH_IN, saturation = true)
        assertEquals(44_100L, request.startFrame)
        assertFalse(recorder.meter.value.clipping)
        val events: kotlinx.coroutines.flow.Flow<RecorderEvent> = recorder.events
        assertTrue(events.toString().isNotEmpty())
        recorder.release()

        val analyzer: WaveformAnalyzer = engine.waveforms
        assertEquals(3, analyzer.algorithmVersion)
        assertEquals(1024, WaveformAnalyzer.MAX_LEVELS)
        val file = File.createTempFile("smoke", ".wav").apply { writeBytes(ByteArray(64)) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            assertEquals(AudioResult.Err(AudioError.Unavailable), analyzer.analyze(it))
        }
    }
}
