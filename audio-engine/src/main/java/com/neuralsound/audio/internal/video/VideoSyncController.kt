package com.neuralsound.audio.internal.video

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Lifecycle scheduler for video synchronization.
 *
 * A loop awaits each synchronization operation before scheduling the next one. The operation
 * mutex also prevents an old, cancelling generation from overlapping a newly started generation.
 */
internal class VideoSyncController(
    private val scope: CoroutineScope,
    private val sampleIntervalMs: Long,
    private val ineligibility: (expectedGeneration: Long) -> VideoSyncSkipReason?,
    private val synchronize: suspend (expectedGeneration: Long) -> Unit,
    private val onLoopStarted: () -> Unit = {},
    private val onLoopStopped: () -> Unit = {},
    private val onSkipped: (VideoSyncSkipReason) -> Unit = {},
) {
    private val lifecycleLock = Any()
    private val operationMutex = Mutex()
    private var loopJob: Job? = null
    private var loopGeneration: Long = Long.MIN_VALUE

    init {
        require(sampleIntervalMs > 0L)
    }

    fun start(generation: Long) {
        synchronized(lifecycleLock) {
            if (loopGeneration == generation && loopJob?.isActive == true) return

            loopJob?.cancel()
            loopGeneration = generation
            loopJob = scope.launch {
                val thisJob = coroutineContext[Job]
                onLoopStarted()
                try {
                    while (isActive) {
                        delay(sampleIntervalMs)
                        operationMutex.withLock {
                            if (!isActive) return@withLock
                            val skipReason = ineligibility(generation)
                            if (skipReason == null) {
                                synchronize(generation)
                            } else {
                                onSkipped(skipReason)
                            }
                        }
                    }
                } finally {
                    onLoopStopped()
                    synchronized(lifecycleLock) {
                        if (loopJob === thisJob) {
                            loopJob = null
                            loopGeneration = Long.MIN_VALUE
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            loopJob?.cancel()
            loopJob = null
            loopGeneration = Long.MIN_VALUE
        }
    }

    internal fun isRunning(): Boolean = synchronized(lifecycleLock) {
        loopJob?.isActive == true
    }
}

internal enum class VideoSyncSkipReason {
    PAUSED,
    SEEKING,
    NO_PLAYER,
    STALE_GENERATION,
    NOT_READY,
    NOT_PLAYING,
    FIRST_FRAME_UNAVAILABLE,
    NATIVE_POSITION_UNAVAILABLE,
}
