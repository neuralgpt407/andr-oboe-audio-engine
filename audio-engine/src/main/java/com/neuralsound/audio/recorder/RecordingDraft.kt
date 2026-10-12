package com.neuralsound.audio.recorder

import android.app.Notification
import android.content.Context
import com.neuralsound.audio.AudioResult
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Describes a recording draft to open with [Recorder.openDraft].
 *
 * @property file The draft WAV file. It is created on the first pause and stays a valid
 * 44.1 kHz mono PCM16 file between edits. Working copies live beside it as `<name>.work`.
 * @property saturation Applies soft saturation to the input; off by default.
 * @property foreground Opt-in microphone foreground service for background recording.
 */
data class DraftRequest(
    val file: File,
    val saturation: Boolean = false,
    val foreground: MicForeground? = null,
)

/**
 * Opt-in microphone foreground service for background recording; the app supplies the
 * notification. (Service wiring is not active yet; the type is reserved.)
 *
 * @property notificationId Id the notification is shown under.
 * @property notification Builds the notification the app wants to show while recording.
 */
class MicForeground(
    val notificationId: Int,
    val notification: (Context) -> Notification,
)

/**
 * A recording that can be paused, resumed, partly re-recorded and finally committed to a file.
 *
 * Every edit works on a copy of the draft file. The draft only changes when an edit finishes
 * successfully, so a failed edit leaves the last good audio untouched (see [DraftState.problem]).
 * Calls are serialized; after [commit] or [discard] every call returns an `InvalidState` error.
 */
interface RecordingDraft {
    /** Current draft state. */
    val state: StateFlow<DraftState>

    /** Starts a new take on an empty draft, otherwise appends at the end of the draft. */
    suspend fun record(): AudioResult<Unit>

    /** Finishes the running edit. The draft file is valid while paused. */
    suspend fun pause(): AudioResult<Unit>

    /** Appends at the end of the draft. */
    suspend fun resume(): AudioResult<Unit>

    /**
     * Records over a working copy of the draft starting at [frame] (clamped to the draft length).
     * Audio before [frame] and after the overwritten span is kept.
     */
    suspend fun rerecordFrom(frame: Long): AudioResult<Unit>

    /**
     * Moves the finished draft to [destination], replacing a file there atomically.
     * A running edit is paused first. An empty draft is not committed (`InvalidState`).
     */
    suspend fun commit(destination: File): AudioResult<TakeResult>

    /** Stops any running edit and deletes the draft and its working copies. */
    suspend fun discard()
}

/** Snapshot of a [RecordingDraft]. */
data class DraftState(
    val status: DraftStatus = DraftStatus.EMPTY,
    /** Frame where the next playback or edit position sits (44.1 kHz). */
    val cursorFrame: Long = 0L,
    /** Length of the draft in frames, including audio captured by a running edit. */
    val totalFrames: Long = 0L,
    /** Waveform of the last good draft file. */
    val waveform: DraftWaveform = DraftWaveform(),
    /** Waveform of the running edit, starting where capture began. Empty when not recording. */
    val liveWaveform: DraftWaveform = DraftWaveform(),
    /** Live microphone level (0..1) while recording, otherwise 0. */
    val inputLevel: Float = 0f,
    /** Why the last call did not fully succeed, or null. */
    val problem: DraftProblem? = null,
    /** True when the draft is intact and the failed action can be retried. */
    val recoverable: Boolean = false,
)

/** Lifecycle of a [RecordingDraft]. */
enum class DraftStatus { EMPTY, READY, RECORDING, PAUSED, COMMITTING, COMMITTED, DISCARDED, FAILED }

/** Reason a draft action failed or was cut short. */
enum class DraftProblem {
    PERMISSION_DENIED,
    MIC_UNAVAILABLE,
    FOREGROUND_SERVICE_UNAVAILABLE,
    WRITER_FAILURE,
    INPUT_LOST,
    OVERFLOW,
    EMPTY_TAKE,
    INVALID_TAKE,
}

/** 32 ms RMS levels starting at [startFrame]. */
class DraftWaveform(
    val startFrame: Long = 0L,
    val levels: FloatArray = FloatArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is DraftWaveform && startFrame == other.startFrame && levels.contentEquals(other.levels)

    override fun hashCode(): Int = 31 * startFrame.hashCode() + levels.contentHashCode()

    override fun toString(): String = "DraftWaveform(startFrame=$startFrame, count=${levels.size})"
}
