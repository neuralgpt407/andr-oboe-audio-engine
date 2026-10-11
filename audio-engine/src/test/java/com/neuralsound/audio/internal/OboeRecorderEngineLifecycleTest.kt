package com.neuralsound.audio.internal

import com.neuralsound.audio.FrameRecordingRequest
import com.neuralsound.audio.RecorderError
import com.neuralsound.audio.RecorderStatus
import com.neuralsound.audio.RecorderTelemetry
import com.neuralsound.audio.RecordingRequest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OboeRecorderEngineLifecycleTest {

    @Test
    fun releaseWaitsForInFlightNativeCallAndIsIdempotent() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(
            nativeBridge = bridge,
            nativeLibraryLoader = { true },
        )

        assertTrue(recorder.startMicSession().isSuccess)

        val nativeCallEntered = CountDownLatch(1)
        val allowNativeCallToFinish = CountDownLatch(1)
        val releaseCompleted = AtomicBoolean(false)
        bridge.onStartWriting = {
            nativeCallEntered.countDown()
            assertTrue(allowNativeCallToFinish.await(2, TimeUnit.SECONDS))
            true
        }

        val worker = Thread {
            recorder.startWriting(RecordingRequest(File("take.wav")))
        }
        worker.start()

        assertTrue(nativeCallEntered.await(2, TimeUnit.SECONDS))
        val releaser = Thread {
            recorder.close()
            releaseCompleted.set(true)
        }
        releaser.start()

        Thread.sleep(100)
        assertFalse(
            "release must wait until the in-flight native call leaves the handle guard",
            releaseCompleted.get(),
        )

        allowNativeCallToFinish.countDown()
        worker.join(2_000)
        releaser.join(2_000)

        assertTrue(releaseCompleted.get())
        recorder.close()
        assertEquals(1, bridge.releaseCalls)

        val postRelease = recorder.startMicSession()
        assertFalse(postRelease.isSuccess)
        assertEquals(RecorderError.Released, postRelease.error)
        assertEquals(1, bridge.startMicSessionCalls)
    }

    @Test
    fun recorderLifecycleCallsAreSerializedAcrossNativeBridge() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(
            nativeBridge = bridge,
            nativeLibraryLoader = { true },
        )
        val nativeCallEntered = CountDownLatch(1)
        val allowNativeCallToFinish = CountDownLatch(1)
        val pauseCalls = AtomicInteger(0)
        bridge.onStartWriting = {
            nativeCallEntered.countDown()
            assertTrue(allowNativeCallToFinish.await(2, TimeUnit.SECONDS))
            true
        }
        bridge.onPauseWriting = {
            pauseCalls.incrementAndGet()
        }

        val writer = Thread {
            recorder.startWriting(RecordingRequest(File("take.wav")))
        }
        writer.start()
        assertTrue(nativeCallEntered.await(2, TimeUnit.SECONDS))

        val pauser = Thread {
            recorder.pauseWriting()
        }
        pauser.start()
        Thread.sleep(100)
        assertEquals(
            "pauseWriting must not enter native code while startWriting is still in flight",
            0,
            pauseCalls.get(),
        )

        allowNativeCallToFinish.countDown()
        writer.join(2_000)
        pauser.join(2_000)

        assertEquals(1, pauseCalls.get())
        recorder.close()
    }

    @Test
    fun startingMicDuringActiveTakePreservesWritingState() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(bridge) { true }

        assertTrue(recorder.startWriting(RecordingRequest(File("take.wav"))).isSuccess)
        assertEquals(RecorderStatus.WRITING, recorder.status.value)

        assertTrue(recorder.startMicSession().isSuccess)
        assertEquals(RecorderStatus.WRITING, recorder.status.value)
        assertEquals(0, bridge.startMicSessionCalls)
        assertTrue(recorder.pauseWriting().isSuccess)
        recorder.close()
    }

    @Test
    fun startingAnotherTakeBeforeStopIsRejected() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(bridge) { true }

        assertTrue(recorder.startWriting(RecordingRequest(File("first.wav"))).isSuccess)
        val secondTake = recorder.startWriting(RecordingRequest(File("second.wav")))

        assertFalse(secondTake.isSuccess)
        assertEquals(RecorderError.InvalidState, secondTake.error)
        assertEquals(1, bridge.startWritingCalls)
        recorder.close()
    }

    @Test
    fun framePreciseStartsUseExactCumulativeFramesAcrossTenCycles() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(bridge) { true }
        var cumulativeFrames = 101L

        repeat(10) { cycle ->
            bridge.stopResult = NativeRecordingResult(
                durationMs = 32L,
                acceptedFrames = 1_411L,
                writtenFrames = 1_411L,
                sampleRate = 44_100,
                failed = false,
            )
            bridge.telemetry = bridge.telemetry.copy(
                takeId = cycle + 1L,
                sampleRate = 44_100,
                acceptedFrames = 1_411L,
                writtenFrames = 1_411L,
                firstBucketIndex = 1,
            )

            assertTrue(
                recorder.startWriting(
                    FrameRecordingRequest(File("take.wav"), cumulativeFrames),
                ).isSuccess,
            )
            val telemetry = recorder.telemetry(afterBucketIndex = 1)
            assertEquals(cycle + 1L, telemetry?.takeId)
            assertEquals(1, telemetry?.firstBucketIndex)
            assertTrue(recorder.pauseWriting().isSuccess)
            val stopped = recorder.stopWriting()
            assertTrue(stopped.isSuccess)
            assertEquals(stopped.acceptedFrames, stopped.writtenFrames)
            cumulativeFrames += stopped.writtenFrames
        }

        assertEquals(
            listOf(
                101L,
                1_512L,
                2_923L,
                4_334L,
                5_745L,
                7_156L,
                8_567L,
                9_978L,
                11_389L,
                12_800L,
            ),
            bridge.startFrameOffsets,
        )
        assertTrue("frame API must never round-trip through milliseconds", bridge.startMillisecondOffsets.isEmpty())
        assertEquals(List(10) { 1 }, bridge.telemetryCursors)
        assertEquals(10, bridge.pauseWritingCalls)
        recorder.close()
    }

    @Test
    fun legacyRecordingRequestStillUsesMillisecondNativePath() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(bridge) { true }

        assertTrue(
            recorder.startWriting(
                RecordingRequest(File("legacy.wav"), startOffsetMs = 123L),
            ).isSuccess,
        )

        assertEquals(listOf(123L), bridge.startMillisecondOffsets)
        assertTrue(bridge.startFrameOffsets.isEmpty())
        recorder.close()
    }

    @Test
    fun asynchronousNativeFailurePublishesAndReplaysThenAcceptedStartClears() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(bridge) { true }
        assertNull(recorder.currentFailure.value)
        assertTrue(
            recorder.startWriting(FrameRecordingRequest(File("take.wav"), 101L)).isSuccess,
        )

        bridge.lastFailure = NativeRecorderFailure.STREAM_DISCONNECTED
        bridge.lastError = "input route disconnected"
        bridge.failed.set(true)

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (recorder.currentFailure.value == null && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10)
        }

        assertEquals(RecorderStatus.FAILED, recorder.status.value)
        assertEquals(RecorderStatus.FAILED, recorder.state.value.status)
        val replayedState = runBlocking {
            withTimeout(1_000L) {
                recorder.state.first { it.status == RecorderStatus.FAILED }
            }
        }
        val replayed = runBlocking {
            withTimeout(1_000L) {
                recorder.currentFailure.filterNotNull().first()
            }
        }
        assertEquals(RecorderError.StreamDisconnected, replayedState.currentFailure?.error)
        assertEquals(RecorderError.StreamDisconnected, replayed.error)
        assertEquals("input route disconnected", replayed.message)
        assertTrue("one native snapshot supplies the typed failure", bridge.failureSnapshotCalls > 0)

        bridge.stopResult = NativeRecordingResult(
            durationMs = 0L,
            acceptedFrames = 0L,
            writtenFrames = 0L,
            sampleRate = 44_100,
            failed = true,
        )
        recorder.stopWriting()
        bridge.failed.set(false)
        bridge.lastFailure = NativeRecorderFailure.NONE
        bridge.lastError = ""
        assertTrue(
            recorder.startWriting(FrameRecordingRequest(File("retry.wav"), 101L)).isSuccess,
        )
        assertNull(recorder.currentFailure.value)
        assertEquals(RecorderStatus.WRITING, recorder.state.value.status)
        assertNull(recorder.state.value.currentFailure)

        bridge.lastFailure = NativeRecorderFailure.WRITER_OVERFLOW
        bridge.lastError = "ring full after retry"
        bridge.failed.set(true)
        val retryDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            recorder.currentFailure.value?.error != RecorderError.WriterOverflow &&
            System.nanoTime() < retryDeadlineNanos
        ) {
            Thread.sleep(10)
        }
        assertEquals(RecorderError.WriterOverflow, recorder.currentFailure.value?.error)
        recorder.close()
    }

    @Test
    fun failureFlowMapsOverflowFileInvalidOutputInvalidStateAndReleased() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(bridge) { true }

        assertTrue(recorder.startWriting(RecordingRequest(File("active.wav"))).isSuccess)
        val invalidState = recorder.startWriting(
            FrameRecordingRequest(File("second.wav"), startOffsetFrames = 5L),
        )
        assertEquals(RecorderError.InvalidState, invalidState.error)
        assertEquals(RecorderError.InvalidState, recorder.currentFailure.value?.error)

        bridge.lastFailure = NativeRecorderFailure.WRITER_OVERFLOW
        bridge.lastError = "ring full"
        bridge.failed.set(true)
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            recorder.currentFailure.value?.error != RecorderError.WriterOverflow &&
            System.nanoTime() < deadlineNanos
        ) {
            Thread.sleep(10)
        }
        assertEquals(RecorderError.WriterOverflow, recorder.currentFailure.value?.error)

        bridge.stopResult = NativeRecordingResult(
            durationMs = 1L,
            acceptedFrames = 2L,
            writtenFrames = 1L,
            sampleRate = 44_100,
            failed = true,
        )
        bridge.lastFailure = NativeRecorderFailure.WRITER_FILE_ERROR
        assertEquals(RecorderError.WriterFileError, recorder.stopWriting().error)
        assertEquals(RecorderError.WriterFileError, recorder.currentFailure.value?.error)

        bridge.failed.set(true)
        bridge.lastFailure = NativeRecorderFailure.INVALID_OUTPUT
        bridge.onStartWriting = { false }
        val invalidOutput = recorder.startWriting(
            FrameRecordingRequest(File("invalid.wav"), startOffsetFrames = 0L),
        )
        assertEquals(RecorderError.InvalidOutput, invalidOutput.error)
        assertEquals(RecorderError.InvalidOutput, recorder.currentFailure.value?.error)

        recorder.close()
        assertEquals(RecorderError.Released, recorder.startMicSession().error)
        assertEquals(RecorderError.Released, recorder.currentFailure.value?.error)
    }

    @Test
    fun asynchronousWriterFileFailurePublishesFromOneNativeSnapshot() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(bridge) { true }
        assertTrue(
            recorder.startWriting(FrameRecordingRequest(File("take.wav"), 0L)).isSuccess,
        )

        bridge.lastFailure = NativeRecorderFailure.WRITER_FILE_ERROR
        bridge.lastError = "disk write failed"
        bridge.failed.set(true)
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            recorder.currentFailure.value?.error != RecorderError.WriterFileError &&
            System.nanoTime() < deadlineNanos
        ) {
            Thread.sleep(10)
        }

        assertEquals(RecorderStatus.FAILED, recorder.status.value)
        assertEquals(RecorderError.WriterFileError, recorder.currentFailure.value?.error)
        assertEquals("disk write failed", recorder.currentFailure.value?.message)
        assertEquals(1, bridge.failureSnapshotCalls)
        recorder.close()
    }

    @Test
    fun micOnlyRetryReplacesMonitorThatIsStoppingAfterFailure() {
        val bridge = FakeRecorderNativeBridge()
        val monitorStopping = CountDownLatch(1)
        val allowMonitorToStop = CountDownLatch(1)
        val stopHookCalls = AtomicInteger(0)
        val recorder = NativeRecorderSession(
            nativeBridge = bridge,
            nativeLibraryLoader = { true },
            failureMonitorStopHook = {
                if (stopHookCalls.getAndIncrement() == 0) {
                    monitorStopping.countDown()
                    assertTrue(allowMonitorToStop.await(2, TimeUnit.SECONDS))
                }
            },
        )
        assertTrue(recorder.startMicSession().isSuccess)

        bridge.lastFailure = NativeRecorderFailure.STREAM_DISCONNECTED
        bridge.lastError = "first route failure"
        bridge.failed.set(true)
        assertTrue(monitorStopping.await(2, TimeUnit.SECONDS))
        assertEquals(RecorderStatus.FAILED, recorder.status.value)

        bridge.failed.set(false)
        bridge.lastFailure = NativeRecorderFailure.NONE
        bridge.lastError = ""
        assertTrue(recorder.startMicSession().isSuccess)
        allowMonitorToStop.countDown()

        bridge.lastFailure = NativeRecorderFailure.WRITER_OVERFLOW
        bridge.lastError = "second asynchronous failure"
        bridge.failed.set(true)
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            recorder.currentFailure.value?.error != RecorderError.WriterOverflow &&
            System.nanoTime() < deadlineNanos
        ) {
            Thread.sleep(10)
        }

        assertEquals(RecorderStatus.FAILED, recorder.status.value)
        assertEquals(RecorderError.WriterOverflow, recorder.currentFailure.value?.error)
        recorder.close()
    }

    @Test
    fun canonicalConversionFailureIsTypedAndNeverStartsWriting() {
        val bridge = FakeRecorderNativeBridge().apply {
            startMicSessionResult = false
            failed.set(true)
            lastFailure = NativeRecorderFailure.MIC_SESSION_OPEN_FAILED
            lastError = "required 48000 to 44100 conversion unavailable"
        }
        val recorder = NativeRecorderSession(bridge) { true }

        val result = recorder.startMicSession()

        assertEquals(RecorderError.MicSessionOpenFailed, result.error)
        assertEquals(RecorderError.MicSessionOpenFailed, recorder.currentFailure.value?.error)
        assertEquals(
            "required 48000 to 44100 conversion unavailable",
            recorder.currentFailure.value?.message,
        )
        assertTrue(bridge.startFrameOffsets.isEmpty())
        assertTrue(bridge.startMillisecondOffsets.isEmpty())
        recorder.close()
    }

    @Test
    fun telemetryUsesCursorDeltaAndPreservesExplicitFrameCounts() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(
            nativeBridge = bridge,
            nativeLibraryLoader = { true },
        )

        bridge.telemetry = RecorderTelemetry(
            takeId = 7L,
            sampleRate = 44_100,
            acceptedFrames = 2_822L,
            writtenFrames = 2_822L,
            rawPeak = 0.5f,
            firstBucketIndex = 2,
            completedBucketRms = floatArrayOf(0.25f, 0.5f),
            partialBucketRms = 0.1f,
            partialBucketFrames = 37,
        )
        assertEquals(2, recorder.telemetry(2)?.firstBucketIndex)

        bridge.onStartWriting = {
            bridge.telemetry = bridge.telemetry.copy(completedBucketRms = FloatArray(0))
            true
        }
        assertTrue(recorder.startWriting(RecordingRequest(File("take.wav"))).isSuccess)

        assertTrue(recorder.telemetry(2)?.completedBucketRms?.isEmpty() == true)
        recorder.close()
    }

    @Test
    fun stopResultRequiresAcceptedAndWrittenFramesToMatch() {
        val bridge = FakeRecorderNativeBridge().apply {
            stopResult = NativeRecordingResult(
                durationMs = 64L,
                acceptedFrames = 2_822L,
                writtenFrames = 2_822L,
                sampleRate = 44_100,
                failed = false,
            )
        }
        val recorder = NativeRecorderSession(bridge) { true }

        val withoutTake = recorder.stopWriting()
        assertFalse(withoutTake.isSuccess)
        assertEquals(RecorderError.InvalidState, withoutTake.error)
        assertEquals(null, withoutTake.file)

        assertTrue(recorder.startWriting(RecordingRequest(File("take.wav"))).isSuccess)
        val success = recorder.stopWriting()
        assertTrue(success.isSuccess)
        assertEquals(64L, success.durationMs)
        assertEquals(2_822L, success.acceptedFrames)
        assertEquals(2_822L, success.writtenFrames)

        bridge.stopResult = bridge.stopResult.copy(writtenFrames = 2_800L)
        assertTrue(recorder.startWriting(RecordingRequest(File("take.wav"))).isSuccess)
        val mismatch = recorder.stopWriting()
        assertFalse(mismatch.isSuccess)
        assertEquals(RecorderError.WriterFileError, mismatch.error)
        recorder.close()
    }

    @Test
    fun nativeFailureCodeDeterminesPublicRecorderError() {
        val bridge = FakeRecorderNativeBridge().apply {
            stopResult = stopResult.copy(failed = true, sampleRate = 44_100)
            lastFailure = NativeRecorderFailure.WRITER_OVERFLOW
        }
        val recorder = NativeRecorderSession(bridge) { true }

        assertTrue(recorder.startWriting(RecordingRequest(File("take.wav"))).isSuccess)
        val result = recorder.stopWriting()

        assertEquals(RecorderError.WriterOverflow, result.error)
        assertFalse(result.isSuccess)
        recorder.close()
    }

    private class FakeRecorderNativeBridge : OboeRecorderNativeBridge {
        var onStartWriting: () -> Boolean = { true }
        var onPauseWriting: () -> Unit = {}
        var telemetry = RecorderTelemetry(
            takeId = 0L,
            sampleRate = 0,
            acceptedFrames = 0L,
            writtenFrames = 0L,
            rawPeak = 0f,
            firstBucketIndex = 0,
            completedBucketRms = FloatArray(0),
            partialBucketRms = 0f,
            partialBucketFrames = 0,
        )
        var startMicSessionCalls = 0
        var startMicSessionResult = true
        var startWritingCalls = 0
        var releaseCalls = 0
        var pauseWritingCalls = 0
        val startMillisecondOffsets = mutableListOf<Long>()
        val startFrameOffsets = mutableListOf<Long>()
        val telemetryCursors = mutableListOf<Int>()
        val failed = AtomicBoolean(false)
        var stopResult = NativeRecordingResult(
            durationMs = 0L,
            acceptedFrames = 0L,
            writtenFrames = 0L,
            sampleRate = 0,
            failed = false,
        )
        var lastFailure = NativeRecorderFailure.NONE
        var lastError = ""
        var failureSnapshotCalls = 0

        override fun create(owner: NativeRecorderSession): Long = 42L

        override fun startMicSession(owner: NativeRecorderSession, handle: Long): Boolean {
            startMicSessionCalls++
            return handle == 42L && startMicSessionResult
        }

        override fun startWriting(
            owner: NativeRecorderSession,
            handle: Long,
            outputPath: String,
            startOffsetMs: Long,
        ): Boolean {
            startWritingCalls++
            startMillisecondOffsets += startOffsetMs
            return handle == 42L && onStartWriting()
        }

        override fun startWritingAtFrame(
            owner: NativeRecorderSession,
            handle: Long,
            outputPath: String,
            startOffsetFrames: Long,
        ): Boolean {
            startFrameOffsets += startOffsetFrames
            return handle == 42L && onStartWriting()
        }

        override fun pauseWriting(owner: NativeRecorderSession, handle: Long) {
            pauseWritingCalls++
            onPauseWriting()
        }

        override fun stopWriting(
            owner: NativeRecorderSession,
            handle: Long,
        ): NativeRecordingResult = stopResult

        override fun getMicPeak(owner: NativeRecorderSession, handle: Long): Float = 0f

        override fun getTelemetry(
            owner: NativeRecorderSession,
            handle: Long,
            lastConsumedBucketIndex: Int,
        ): RecorderTelemetry {
            telemetryCursors += lastConsumedBucketIndex
            return telemetry.copy(completedBucketRms = telemetry.completedBucketRms.copyOf())
        }

        override fun getWrittenDurationMs(owner: NativeRecorderSession, handle: Long): Long = 0L

        override fun getSampleRate(owner: NativeRecorderSession, handle: Long): Int = 0

        override fun hasFailed(owner: NativeRecorderSession, handle: Long): Boolean = failed.get()

        override fun getFailureSnapshot(
            owner: NativeRecorderSession,
            handle: Long,
        ): NativeRecorderFailureSnapshot {
            failureSnapshotCalls++
            return NativeRecorderFailureSnapshot(
                code = lastFailure.code,
                message = lastError,
            )
        }

        override fun releaseMicSession(owner: NativeRecorderSession, handle: Long) = Unit

        override fun release(owner: NativeRecorderSession, handle: Long) {
            releaseCalls++
        }
    }
}
