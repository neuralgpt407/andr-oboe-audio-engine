package com.neuralsound.audio.internal.recorder

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.internal.legacy.FramePreciseRecorderSession
import com.neuralsound.audio.internal.legacy.FrameRecordingRequest
import com.neuralsound.audio.internal.legacy.RecorderError
import com.neuralsound.audio.internal.legacy.RecorderFailure
import com.neuralsound.audio.internal.legacy.RecorderOperationResult
import com.neuralsound.audio.internal.legacy.RecorderState as LegacyState
import com.neuralsound.audio.internal.legacy.RecorderStatus as LegacyStatus
import com.neuralsound.audio.internal.legacy.RecorderTelemetry
import com.neuralsound.audio.internal.legacy.RecordingRequest
import com.neuralsound.audio.internal.legacy.RecordingResult
import com.neuralsound.audio.recorder.RecorderEvent
import com.neuralsound.audio.recorder.RecorderStatus
import com.neuralsound.audio.recorder.TakeMode
import com.neuralsound.audio.recorder.TakeRequest
import com.neuralsound.audio.recorder.WaveformBuckets
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-20 / FR-22 / FR-24 / S4-AS2 / S4-AS4 / S4-AS6 / EC-8 / EC-9. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRecorderTest {
    private val dir: File = Files.createTempDirectory("takes").toFile()

    private fun TestScope.recorder(session: FakeSession, permission: Boolean = true): DefaultRecorder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DefaultRecorder(session, { permission }, dispatcher, dispatcher, tickMs = 32)
    }

    @Test
    fun deniedPermissionIsTypedAndNeverOpensTheMic() = runTest {
        val session = FakeSession()
        val recorder = recorder(session, permission = false)

        assertEquals(AudioResult.Err(AudioError.PermissionDenied), recorder.openMic())
        assertEquals(AudioResult.Err(AudioError.PermissionDenied), recorder.record(TakeRequest(File(dir, "a.wav"))))
        assertTrue(session.calls.isEmpty())
        recorder.release()
    }

    @Test
    fun aWarmMicServesSeveralTakesWithoutReopening() = runTest {
        val session = FakeSession()
        val recorder = recorder(session)

        assertTrue(recorder.openMic().isOk)
        repeat(3) { i ->
            val take = recorder.record(TakeRequest(File(dir, "take$i.wav"))).getOrNull()!!
            session.produce(frames = 44_100L)
            assertTrue(take.stop().isOk)
        }

        assertEquals(1, session.calls.count { it == "openMic" })
        assertEquals(3, session.calls.count { it.startsWith("write:") })
        assertEquals(RecorderStatus.MIC_OPEN, recorder.state.value.status)
        recorder.release()
    }

    @Test
    fun newTakesReplaceTheFileAndPunchInsRequireOne() = runTest {
        val session = FakeSession()
        val recorder = recorder(session)
        val existing = File(dir, "old.wav").apply { writeBytes(ByteArray(1000)) }

        recorder.record(TakeRequest(existing, mode = TakeMode.NEW)).getOrNull()!!.stop()
        assertFalse("NEW deletes the previous file before writing", session.sawExistingFileAtStart)

        val missing = File(dir, "missing.wav")
        assertEquals(AudioResult.Err(AudioError.FileError), recorder.record(TakeRequest(missing, startFrame = 10, mode = TakeMode.PUNCH_IN)))

        existing.writeBytes(ByteArray(1000))
        val punch = recorder.record(TakeRequest(existing, startFrame = 1_323_000, mode = TakeMode.PUNCH_IN))
        assertTrue(punch.isOk)
        assertEquals("write:${existing.path}@1323000", session.calls.last())
        recorder.release()
    }

    @Test
    fun waveformBucketsArePushedWithContinuousIndicesAcrossPauseAndResume() = runTest {
        val session = FakeSession()
        val recorder = recorder(session)
        val take = recorder.record(TakeRequest(File(dir, "wave.wav"), startFrame = 1_000)).getOrNull()!!
        val buckets = mutableListOf<WaveformBuckets>()
        val collector = launch { take.waveform.toList(buckets) }
        runCurrent()

        session.produce(frames = 4_000L, buckets = floatArrayOf(0.1f, 0.2f))
        advanceTimeBy(40); runCurrent()
        assertEquals(RecorderStatus.RECORDING, recorder.state.value.status)

        assertTrue(take.pause().isOk)
        runCurrent()
        assertEquals(RecorderStatus.PAUSED, recorder.state.value.status)
        assertEquals(4_000L, take.writtenFrames.value)

        assertTrue(take.resume().isOk)
        runCurrent()
        assertEquals("resume punches in after the written frames", "write:${take.file.path}@5000", session.calls.last())
        session.produce(frames = 2_000L, buckets = floatArrayOf(0.3f))
        advanceTimeBy(40); runCurrent()

        val result = take.stop().getOrNull()!!
        runCurrent()
        assertEquals(6_000L, result.frames)
        assertEquals(1_000L, result.startFrame)
        assertEquals(listOf(0, 2), buckets.map { it.firstIndex })
        assertEquals(listOf(0.1f, 0.2f, 0.3f), buckets.flatMap { it.rms.toList() })
        collector.cancel()
        recorder.release()
    }

    @Test
    fun meterReportsPeakAndClipping() = runTest {
        val session = FakeSession()
        val recorder = recorder(session)
        recorder.openMic()

        session.peak = 0.995f
        advanceTimeBy(40); runCurrent()

        assertEquals(0.995f, recorder.meter.value.peak)
        assertTrue(recorder.meter.value.clipping)
        assertTrue(recorder.state.value.health.streamOpen)
        recorder.release()
    }

    @Test
    fun inputDisconnectFinalizesThePartialTakeAndFails() = runTest {
        val session = FakeSession()
        val recorder = recorder(session)
        val events = mutableListOf<RecorderEvent>()
        val collector = launch { recorder.events.toList(events) }
        val take = recorder.record(TakeRequest(File(dir, "lost.wav"))).getOrNull()!!
        session.produce(frames = 22_050L)

        session.currentFailure.value = RecorderFailure(RecorderError.StreamDisconnected)
        runCurrent()

        assertEquals(RecorderStatus.FAILED, recorder.state.value.status)
        assertEquals(AudioError.Disconnected, recorder.state.value.error)
        val lost = events.filterIsInstance<RecorderEvent.InputLost>().single()
        assertEquals(22_050L, lost.partial?.frames)
        assertTrue(session.calls.contains("stop"))
        assertEquals(AudioResult.Err(AudioError.InvalidState), take.stop())
        collector.cancel()
        recorder.release()
    }

    @Test
    fun oneTakeAtATimeAndNothingAfterRelease() = runTest {
        val session = FakeSession()
        val recorder = recorder(session)
        recorder.record(TakeRequest(File(dir, "one.wav")))

        assertEquals(AudioResult.Err(AudioError.InvalidState), recorder.record(TakeRequest(File(dir, "two.wav"))))

        recorder.release()
        assertEquals(RecorderStatus.RELEASED, recorder.state.value.status)
        assertTrue(session.closed)
        assertEquals(AudioResult.Err(AudioError.Released), recorder.openMic())
    }

    private class FakeSession : FramePreciseRecorderSession {
        override val state: StateFlow<LegacyState> = MutableStateFlow(LegacyState(LegacyStatus.IDLE))
        override val status = MutableStateFlow(LegacyStatus.IDLE)
        override val currentFailure = MutableStateFlow<RecorderFailure?>(null)
        override var micAmplitude: Float = 0f
        override val writtenDurationMs: Long get() = segmentFrames * 1000 / 44_100

        val calls = mutableListOf<String>()
        var closed = false
        var peak: Float
            get() = micAmplitude
            set(value) { micAmplitude = value }
        var sawExistingFileAtStart = false
        private var segmentFrames = 0L
        private var segmentBuckets = FloatArray(0)

        fun produce(frames: Long, buckets: FloatArray = FloatArray(0)) {
            segmentFrames += frames
            segmentBuckets += buckets
        }

        override fun startMicSession(): RecorderOperationResult {
            calls += "openMic"
            status.value = LegacyStatus.MIC_SESSION_ACTIVE
            return RecorderOperationResult.Success
        }

        override fun startWriting(request: FrameRecordingRequest): RecorderOperationResult {
            calls += "write:${request.outputFile.path}@${request.startOffsetFrames}"
            sawExistingFileAtStart = request.outputFile.exists()
            segmentFrames = 0
            segmentBuckets = FloatArray(0)
            status.value = LegacyStatus.WRITING
            return RecorderOperationResult.Success
        }

        override fun startWriting(request: RecordingRequest) = error("ms offsets are not used")
        override fun pauseWriting() = error("segments are stopped, not paused")

        override fun stopWriting(): RecordingResult {
            calls += "stop"
            status.value = LegacyStatus.MIC_SESSION_ACTIVE
            return RecordingResult(File("x"), writtenDurationMs, 44_100, segmentFrames, segmentFrames)
        }

        override fun telemetry(afterBucketIndex: Int): RecorderTelemetry {
            val first = afterBucketIndex.coerceAtMost(segmentBuckets.size)
            return RecorderTelemetry(
                takeId = 1, sampleRate = 44_100, acceptedFrames = segmentFrames, writtenFrames = segmentFrames,
                rawPeak = micAmplitude, firstBucketIndex = first,
                completedBucketRms = segmentBuckets.copyOfRange(first, segmentBuckets.size),
                partialBucketRms = 0f, partialBucketFrames = 0,
            )
        }

        override fun releaseMicSession(): RecorderOperationResult {
            calls += "closeMic"
            status.value = LegacyStatus.IDLE
            return RecorderOperationResult.Success
        }

        override fun close() {
            closed = true
            status.value = LegacyStatus.RELEASED
        }
    }
}
