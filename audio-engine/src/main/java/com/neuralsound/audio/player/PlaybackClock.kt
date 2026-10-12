package com.neuralsound.audio.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Smooth playback position for frame-rate UI (lyrics, captions) and UI-rate position updates. */
interface PlaybackClock {
    /** The latest observation of the engine. Updates whenever position, play state, tempo or seeking changes. */
    val reading: StateFlow<ClockReading>

    /**
     * The playback position in milliseconds at [frameTimeNanos], a `System.nanoTime()`-based
     * timestamp such as a frame time. Safe to call from the UI frame thread on every frame.
     * While advancing it never decreases; it jumps only when [ClockReading.discontinuity]
     * changes. During a seek it returns the seek target.
     */
    fun positionAt(frameTimeNanos: Long): Long

    /**
     * Readings for UI-rate updates. Emits the current reading immediately, then, while
     * [ClockReading.isAdvancing], one reading every [intervalMs] extrapolated to the emission
     * time, and immediately whenever [ClockReading.discontinuity], [ClockReading.isPlaying] or
     * [ClockReading.seeking] changes. It does not tick while paused or seeking. Completes when
     * the player is released.
     */
    fun positions(intervalMs: Long): Flow<ClockReading>
}

/** One observation of the playback clock. */
data class ClockReading(
    /** Playback position in milliseconds when it was observed. */
    val positionMs: Long = 0L,
    /** `System.nanoTime()` when [positionMs] was observed. */
    val capturedAtNanos: Long = 0L,
    val isPlaying: Boolean = false,
    /** Playing, not seeking, and the position has moved since playback started (device priming is over). */
    val isAdvancing: Boolean = false,
    /** Current tempo. */
    val rate: Float = 1f,
    val seeking: Boolean = false,
    /** The position being sought, while [seeking]. */
    val seekTargetMs: Long? = null,
    /** Increments once per seek, pause, resume, load/restore, and loop wrap or jump. */
    val discontinuity: Long = 0L,
)
