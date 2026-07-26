package com.neuralsound.audio.internal

import com.neuralsound.audio.PlaybackRange

internal object PlaybackRangePolicy {
    private const val COMPLETION_THRESHOLD_MS = 50L

    fun seekTarget(
        requestedMs: Long,
        activeRange: PlaybackRange?,
        durationMs: Long,
    ): Long {
        if (durationMs <= 0L) return 0L
        val range = activeRange?.clampTo(durationMs)
        return requestedMs.coerceIn(range?.startMs ?: 0L, range?.endMs ?: durationMs)
    }

    fun playStartPosition(
        currentPositionMs: Long,
        activeRange: PlaybackRange?,
        durationMs: Long,
    ): Long {
        if (durationMs <= 0L) return 0L
        val range = activeRange?.clampTo(durationMs) ?: return when {
            currentPositionMs >= durationMs - 500L -> 0L
            else -> currentPositionMs.coerceIn(0L, durationMs)
        }
        return if (currentPositionMs < range.startMs || currentPositionMs >= range.endMs - COMPLETION_THRESHOLD_MS) {
            range.startMs
        } else {
            currentPositionMs
        }
    }

    fun shouldLoopAtPosition(
        positionMs: Long,
        activeRange: PlaybackRange?,
        durationMs: Long,
    ): Boolean {
        if (durationMs <= 0L) return false
        val range = activeRange?.clampTo(durationMs) ?: return positionMs >= durationMs - COMPLETION_THRESHOLD_MS
        return positionMs >= range.endMs - COMPLETION_THRESHOLD_MS
    }

    fun shouldLoopOnCompletion(
        activeRange: PlaybackRange?,
        explicitLooping: Boolean,
    ): Boolean {
        return explicitLooping || activeRange != null
    }

    fun loopStart(activeRange: PlaybackRange?): Long {
        return activeRange?.startMs ?: 0L
    }
}
