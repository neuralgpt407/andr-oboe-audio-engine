package com.neuralsound.audio.internal

import com.neuralsound.audio.RecorderError
import com.neuralsound.audio.RecorderStatus
import com.neuralsound.audio.RecorderTelemetry
import com.neuralsound.audio.RecordingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OboeRecorderEngineLifecycleTest {

    @Test
    fun recorderNativeMethodsKeepRegisteredJvmNames() {
        val nativeMethodNames = NativeRecorderSession::class.java.declaredMethods
            .asSequence()
            .map { it.name }
            .filter { it.startsWith("native") }
            .toSet()

        assertTrue("nativeCreate must match JNI registration", "nativeCreate" in nativeMethodNames)
        assertTrue(
            "recorder native method names must not be module-mangled",
            nativeMethodNames.none { "$" in it },
        )
        assertTrue("nativeGetTelemetry must match JNI registration", "nativeGetTelemetry" in nativeMethodNames)
        assertTrue(
            "nativeGetLastErrorCode must match JNI registration",
            "nativeGetLastErrorCode" in nativeMethodNames,
        )
    }

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
    fun asynchronousNativeFailureUpdatesObservableStatus() {
        val bridge = FakeRecorderNativeBridge()
        val recorder = NativeRecorderSession(bridge) { true }
        assertTrue(recorder.startWriting(RecordingRequest(File("take.wav"))).isSuccess)

        bridge.lastFailure = NativeRecorderFailure.STREAM_DISCONNECTED
        bridge.failed.set(true)

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (recorder.status.value != RecorderStatus.FAILED && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10)
        }

        assertEquals(RecorderStatus.FAILED, recorder.status.value)
        assertEquals(RecorderError.StreamDisconnected, recorder.startMicSession().error)
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
        var startWritingCalls = 0
        var releaseCalls = 0
        val failed = AtomicBoolean(false)
        var stopResult = NativeRecordingResult(
            durationMs = 0L,
            acceptedFrames = 0L,
            writtenFrames = 0L,
            sampleRate = 0,
            failed = false,
        )
        var lastFailure = NativeRecorderFailure.NONE

        override fun create(owner: NativeRecorderSession): Long = 42L

        override fun startMicSession(owner: NativeRecorderSession, handle: Long): Boolean {
            startMicSessionCalls++
            return handle == 42L
        }

        override fun startWriting(
            owner: NativeRecorderSession,
            handle: Long,
            outputPath: String,
            startOffsetMs: Long,
        ): Boolean {
            startWritingCalls++
            return handle == 42L && onStartWriting()
        }

        override fun pauseWriting(owner: NativeRecorderSession, handle: Long) {
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
            return telemetry.copy(completedBucketRms = telemetry.completedBucketRms.copyOf())
        }

        override fun getWrittenDurationMs(owner: NativeRecorderSession, handle: Long): Long = 0L

        override fun getSampleRate(owner: NativeRecorderSession, handle: Long): Int = 0

        override fun hasFailed(owner: NativeRecorderSession, handle: Long): Boolean = failed.get()

        override fun getLastFailure(
            owner: NativeRecorderSession,
            handle: Long,
        ): NativeRecorderFailure = lastFailure

        override fun getLastError(owner: NativeRecorderSession, handle: Long): String = ""

        override fun releaseMicSession(owner: NativeRecorderSession, handle: Long) = Unit

        override fun release(owner: NativeRecorderSession, handle: Long) {
            releaseCalls++
        }
    }
}
