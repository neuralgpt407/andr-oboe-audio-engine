package com.neuralsound.audio

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePreciseRecorderApiTest {
    @Test
    fun frameRecordingRequestRetainsExactNonMillisecondOffset() {
        val request = FrameRecordingRequest(
            outputFile = File("take.wav"),
            startOffsetFrames = 4_410_001L,
        )

        assertEquals(4_410_001L, request.startOffsetFrames)
        assertThrows(IllegalArgumentException::class.java) {
            FrameRecordingRequest(File("take.wav"), startOffsetFrames = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FrameRecordingRequest(File(" "), startOffsetFrames = 0L)
        }
    }

    @Test
    fun framePreciseRecorderIsAnAdditiveRecorderSubtypeAndFactory() {
        assertTrue(RecorderSession::class.java.isAssignableFrom(FramePreciseRecorderSession::class.java))
        assertFalse(
            RecorderSession::class.java.declaredMethods.any {
                it.name == "getCurrentFailure" ||
                    (it.name == "startWriting" &&
                        it.parameterTypes.contentEquals(arrayOf(FrameRecordingRequest::class.java)))
            },
        )

        val factory = AudioEngine::class.java.getDeclaredMethod("createFramePreciseRecorderSession")
        assertEquals(FramePreciseRecorderSession::class.java, factory.returnType)

        val legacyFactory = AudioEngine::class.java.getDeclaredMethod("createRecorderSession")
        assertEquals(RecorderSession::class.java, legacyFactory.returnType)
        assertEquals(123L, RecordingRequest(File("legacy.wav"), startOffsetMs = 123L).startOffsetMs)
    }

    @Test
    fun recorderFailureRetainsTypedErrorAndOptionalDiagnostic() {
        val failure = RecorderFailure(
            error = RecorderError.WriterFileError,
            message = "disk full",
        )
        val state = RecorderState(
            status = RecorderStatus.FAILED,
            currentFailure = failure,
        )

        assertEquals(RecorderError.WriterFileError, failure.error)
        assertEquals("disk full", failure.message)
        assertEquals(RecorderStatus.FAILED, state.status)
        assertEquals(failure, state.currentFailure)
        assertThrows(IllegalArgumentException::class.java) {
            RecorderState(status = RecorderStatus.FAILED, currentFailure = null)
        }
    }
}
