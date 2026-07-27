package com.neuralsound.audio.consumer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.neuralsound.audio.AudioEngine
import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.AudioRoute
import com.neuralsound.audio.ChannelGain
import com.neuralsound.audio.FrameRecordingRequest
import com.neuralsound.audio.MixerRequest
import com.neuralsound.audio.MixerState
import com.neuralsound.audio.MixerStatus
import com.neuralsound.audio.MixerTrack
import com.neuralsound.audio.MixerTrackState
import com.neuralsound.audio.NativeAudioWaveformAnalyzer
import com.neuralsound.audio.NativeWaveformAnalysisError
import com.neuralsound.audio.NativeWaveformAnalysisResult
import com.neuralsound.audio.PlaybackEffects
import com.neuralsound.audio.PlaybackRange
import com.neuralsound.audio.RecorderError
import com.neuralsound.audio.RecorderFailure
import com.neuralsound.audio.RecorderOperationResult
import com.neuralsound.audio.RecorderState
import com.neuralsound.audio.RecorderStatus
import com.neuralsound.audio.RecorderTelemetry
import com.neuralsound.audio.RecordingRequest
import com.neuralsound.audio.RecordingResult
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.TrackMix
import com.neuralsound.audio.media3.Media3AudioEngine
import com.neuralsound.audio.media3.Media3MixerRequest
import com.neuralsound.audio.media3.Media3VideoState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class KotlinApiCompatibilityTest {
    @Test
    fun v01NamedArgumentsAndInterfaceCallsCompileAgainstV02() {
        val trackId = TrackId(value = "legacy")
        val gain = ChannelGain(left = 1f, right = 0.5f)
        val mix = TrackMix(volume = 0.7f, muted = false, channelGain = gain)
        val track = MixerTrack(
            id = trackId,
            uri = Uri.EMPTY,
            mix = mix,
            offsetMs = 12L,
        )
        val range = PlaybackRange(startMs = 10L, endMs = 900L)
        val effects = PlaybackEffects(tempo = 1.1f, pitchSemitones = 2)
        val request = MixerRequest(
            tracks = listOf(track),
            startPositionMs = 20L,
            playbackRange = range,
            looping = true,
            effects = effects,
            autoPlay = false,
        )
        val route = AudioRoute(deviceId = 1, deviceType = 2, topologyRevision = 3L)
        val state = MixerState(
            status = MixerStatus.READY,
            positionMs = 20L,
            durationMs = 1_000L,
            playbackRange = range,
            looping = true,
            effects = effects,
            tracks = mapOf(
                trackId to MixerTrackState(mix = mix, offsetMs = 12L)
            ),
            route = route,
            failure = null,
        )
        val context: Context = RuntimeEnvironment.getApplication()
        val output = File(context.cacheDir, "legacy-source-compat.wav")
        val recordingRequest = RecordingRequest(
            outputFile = output,
            startOffsetMs = 25L,
        )
        val operation = RecorderOperationResult(
            error = RecorderError.InvalidState,
            message = "legacy",
        )
        val recording = RecordingResult(
            file = output,
            durationMs = 1_000L,
            sampleRate = 44_100,
            acceptedFrames = 44_100L,
            writtenFrames = 44_100L,
            error = null,
            message = null,
        )
        val telemetry = RecorderTelemetry(
            takeId = 1L,
            sampleRate = 44_100,
            acceptedFrames = 10L,
            writtenFrames = 10L,
            rawPeak = 0.5f,
            firstBucketIndex = 0,
            completedBucketRms = floatArrayOf(0.25f),
            partialBucketRms = 0.1f,
            partialBucketFrames = 5,
        )
        val media3Request = Media3MixerRequest(audio = request, videoUri = null)
        val videoState = Media3VideoState(
            player = null,
            hasVideo = false,
            firstFrameReady = true,
            aspectRatio = null,
            revision = 4L,
        )

        assertEquals(20L, state.positionMs)
        assertEquals(25L, recordingRequest.startOffsetMs)
        assertEquals(RecorderError.InvalidState, operation.error)
        assertTrue(recording.isSuccess)
        assertEquals(10L, telemetry.writtenFrames)
        assertEquals(request, media3Request.audio)
        assertTrue(videoState.firstFrameReady)
        assertEquals(
            AudioFailure.NativeOperationFailed(operation = "legacy", detail = "detail"),
            AudioFailure.NativeOperationFailed(operation = "legacy", detail = "detail"),
        )
        assertEquals(
            AudioFailure.UnsupportedTrackFormat(
                trackId = trackId,
                sampleRate = 48_000,
                channelCount = 2,
                requiredSampleRate = 44_100,
            ),
            AudioFailure.UnsupportedTrackFormat(
                trackId = trackId,
                sampleRate = 48_000,
                channelCount = 2,
                requiredSampleRate = 44_100,
            ),
        )
    }

    @Test
    fun publishedV02EntryPointsAcceptRealRequests() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val output = File(context.cacheDir, "v02-consumer.wav")
        val audioEngine = AudioEngine(context = context)
        val recorder = audioEngine.createFramePreciseRecorderSession()
        val recordingStart = recorder.startWriting(
            FrameRecordingRequest(
                outputFile = output,
                startOffsetFrames = 128L,
            )
        )
        assertTrue(
            recordingStart.error == RecorderError.InvalidState ||
                recordingStart.error == RecorderError.NativeUnavailable
        )
        assertEquals(
            RecorderState(
                status = RecorderStatus.FAILED,
                currentFailure = RecorderFailure(
                    error = RecorderError.NativeUnavailable,
                    message = "unavailable",
                ),
            ).status,
            RecorderStatus.FAILED,
        )
        recorder.close()

        val source = File(context.cacheDir, "v02-waveform-empty.bin").apply {
            writeBytes(byteArrayOf())
        }
        val waveform = NativeAudioWaveformAnalyzer()
        val waveformResult = ParcelFileDescriptor.open(
            source,
            ParcelFileDescriptor.MODE_READ_ONLY,
        ).use { waveform.analyze(source = it, maxSamples = 64) }
        assertEquals(
            NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.NativeUnavailable,
            ),
            waveformResult,
        )
        waveform.cancel()
        source.delete()

        val media3 = Media3AudioEngine(context = context).createSession()
        val result = media3.prepare(
            request = Media3MixerRequest(
                audio = MixerRequest(
                    tracks = listOf(
                        MixerTrack(id = TrackId(value = "published"), uri = Uri.EMPTY)
                    )
                ),
                videoUri = null,
            )
        )
        assertTrue(result is AudioResult.Failure)
        media3.close()
    }
}
