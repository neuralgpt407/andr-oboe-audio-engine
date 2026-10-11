package com.neuralsound.audio.recorder

import com.neuralsound.audio.AudioResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Records the microphone into 44.1 kHz mono PCM16 WAV takes.
 *
 * Open the mic once ([openMic]) to keep it warm, then start any number of takes with [record].
 * The app must hold RECORD_AUDIO; recording in the background also needs the app's own
 * microphone foreground service.
 */
interface Recorder {
    val state: StateFlow<RecorderState>
    val events: Flow<RecorderEvent>

    /** Live input level, updated about every 32 ms while the mic is open. */
    val meter: StateFlow<MicMeter>

    suspend fun openMic(): AudioResult<Unit>
    suspend fun closeMic()

    /** Starts writing a take. Only one take can be active at a time. */
    suspend fun record(request: TakeRequest): AudioResult<Take>

    suspend fun release()

    companion object {
        /** Sample rate of every take file. */
        const val SAMPLE_RATE = 44_100

        @JvmStatic
        fun framesForMs(ms: Long): Long = ms * SAMPLE_RATE / 1000L

        @JvmStatic
        fun msForFrames(frames: Long): Long = frames * 1000L / SAMPLE_RATE
    }
}

/** An active take. */
interface Take {
    val file: java.io.File

    /** Incremental 32 ms RMS buckets for drawing the live waveform. */
    val waveform: Flow<WaveformBuckets>

    /** Frames written so far (at [Recorder.SAMPLE_RATE]). */
    val writtenFrames: StateFlow<Long>

    fun pause(): AudioResult<Unit>
    fun resume(): AudioResult<Unit>

    /** Finishes the file and returns its summary. */
    suspend fun stop(): AudioResult<TakeResult>
}
