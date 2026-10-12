package com.neuralsound.audio.internal.player

import com.neuralsound.audio.player.ClockReading
import com.neuralsound.audio.player.PlaybackClock
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [PlaybackClock] fed by the player: every engine position, play-state, tempo and seek event
 * stamps a new [ClockReading] with [nanoTime].
 *
 * Writers (player threads) and [positionAt] (UI frame thread) share [lock]. A seek is "active"
 * from [beginSeek] until the engine reports [syncing] false (two consecutive checks, so the
 * brief gap between queued seeks is not mistaken for completion) or [MAX_SEEK_MS] elapses.
 */
internal class DefaultPlaybackClock(
    private val scope: CoroutineScope,
    private val syncing: StateFlow<Boolean>,
    /** The engine's current position, used when a seek settles. */
    private val enginePosition: () -> Long,
    /** Called with the engine position once a seek has settled. */
    private val onSeekSettled: (Long) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) : PlaybackClock {
    private val lock = Any()
    private val estimator = SmoothPositionEstimator()
    private val _reading = MutableStateFlow(ClockReading())
    override val reading: StateFlow<ClockReading> = _reading.asStateFlow()
    private val closed = MutableStateFlow(false)

    // Guarded by lock.
    private var ready = false
    private var moved = false
    private var anchorPositionMs = 0L
    private var seekJob: Job? = null
    private var fedReading: ClockReading? = null
    private var fedSeeking = false

    /** The seek target while a seek is active, else null. */
    val seekTargetMs: Long? get() = _reading.value.seekTargetMs

    /** A load is starting: counts as one discontinuity and ignores engine events until [onLoaded]. */
    fun onLoadStarted() = update { r ->
        ready = false
        moved = false
        cancelSeekJob()
        r.copy(isPlaying = false, isAdvancing = false, seeking = false, seekTargetMs = null)
            .bump()
    }

    /** The load finished; the engine is at [positionMs]. Does not count as a second discontinuity. */
    fun onLoaded(positionMs: Long, playing: Boolean, tempo: Float) = update { r ->
        ready = true
        moved = false
        anchorPositionMs = positionMs
        r.copy(positionMs = positionMs, isPlaying = playing, rate = tempo, isAdvancing = false)
    }

    fun onPosition(positionMs: Long) = update { r ->
        if (!ready || r.seeking) return@update r
        var next = r
        if (abs(positionMs - expectedPositionMs(r)) > JUMP_THRESHOLD_MS) {
            // Loop wrap or an external jump.
            next = next.bump()
            moved = r.isPlaying
            anchorPositionMs = positionMs
        } else if (r.isPlaying && !moved && positionMs != anchorPositionMs) {
            moved = true
        }
        next.copy(positionMs = positionMs)
    }

    fun onPlaying(playing: Boolean) = update { r ->
        if (!ready || playing == r.isPlaying) return@update r
        // While seeking the change is absorbed into the seek's single discontinuity.
        val next = if (r.seeking) r else r.bump()
        moved = false
        anchorPositionMs = r.positionMs
        next.copy(isPlaying = playing)
    }

    fun onTempo(tempo: Float) = update { r ->
        if (tempo == r.rate) return@update r
        // Re-anchor at "now" so the old rate is applied only up to the change.
        r.extrapolatedTo(nanoTime()).copy(rate = tempo)
    }

    /** A seek to [targetMs] (already clamped) was requested. */
    fun beginSeek(targetMs: Long) {
        synchronized(lock) {
            if (!ready) return
            cancelSeekJob()
            moved = false
            publish(_reading.value.bump().copy(seeking = true, seekTargetMs = targetMs))
            seekJob = scope.launch { awaitSyncEnd() }
        }
    }

    /** The engine is not syncing (or rejected the seek): end the active seek now. */
    fun settleSeekNow() {
        val settled = synchronized(lock) {
            if (!_reading.value.seeking) return
            cancelSeekJob()
            finishSeek()
        }
        onSeekSettled(settled)
    }

    fun close() {
        synchronized(lock) {
            cancelSeekJob()
            publish(_reading.value.copy(isPlaying = false, isAdvancing = false, seeking = false, seekTargetMs = null))
        }
        closed.value = true
    }

    private suspend fun awaitSyncEnd() {
        val startedAt = nanoTime()
        var idleChecks = 0
        while (true) {
            delay(SEEK_POLL_MS)
            idleChecks = if (syncing.value) 0 else idleChecks + 1
            val timedOut = (nanoTime() - startedAt) / NANOS_PER_MS > MAX_SEEK_MS
            if (idleChecks >= 2 || timedOut) break
        }
        val settled = synchronized(lock) {
            if (!_reading.value.seeking) return
            seekJob = null
            finishSeek()
        }
        onSeekSettled(settled)
    }

    /** Caller holds [lock]. Returns the settled engine position. */
    private fun finishSeek(): Long {
        val position = enginePosition()
        moved = false
        anchorPositionMs = position
        publish(_reading.value.copy(positionMs = position, seeking = false, seekTargetMs = null))
        return position
    }

    override fun positionAt(frameTimeNanos: Long): Long = synchronized(lock) { positionAtLocked(frameTimeNanos) }

    private fun positionAtLocked(frameTimeNanos: Long): Long {
        val r = _reading.value
        if (r.seeking) {
            fedSeeking = true
            return r.seekTargetMs ?: r.positionMs
        }
        val previous = fedReading
        if (previous !== r) {
            val at = maxOf(frameTimeNanos, r.capturedAtNanos)
            val position = r.extrapolatedTo(at).positionMs
            val rate = r.rate.toDouble()
            if (previous == null || fedSeeking || previous.discontinuity != r.discontinuity) {
                estimator.snap(position, r.isAdvancing, at, rate)
            } else {
                estimator.onTick(position, r.isAdvancing, at, rate)
            }
            fedReading = r
            fedSeeking = false
        }
        return estimator.positionAt(frameTimeNanos)
    }

    override fun positions(intervalMs: Long): Flow<ClockReading> {
        require(intervalMs > 0L) { "intervalMs must be positive" }
        return channelFlow {
            val first = _reading.value
            send(first)
            val ticker = launch {
                while (true) {
                    reading.first { it.isAdvancing }
                    delay(intervalMs)
                    val current = _reading.value
                    if (current.isAdvancing) send(current.extrapolatedTo(nanoTime()))
                }
            }
            val changes = launch {
                var last = Key(first)
                reading.collect { r ->
                    val key = Key(r)
                    if (key != last) {
                        last = key
                        send(r)
                    }
                }
            }
            val closer = launch {
                closed.first { it }
                ticker.cancel()
                changes.cancel()
                close()
            }
            awaitClose { closer.cancel() }
        }
    }

    // --------------------------------------------------------------------------------------

    private data class Key(val discontinuity: Long, val isPlaying: Boolean, val seeking: Boolean) {
        constructor(r: ClockReading) : this(r.discontinuity, r.isPlaying, r.seeking)
    }

    private inline fun update(transform: (ClockReading) -> ClockReading) {
        synchronized(lock) {
            if (closed.value) return
            val current = _reading.value
            val next = transform(current)
            if (next !== current) publish(next)
        }
    }

    /** Caller holds [lock]. Stamps the capture time and derives [ClockReading.isAdvancing]. */
    private fun publish(r: ClockReading) {
        _reading.value = r.copy(
            capturedAtNanos = nanoTime(),
            isAdvancing = r.isPlaying && !r.seeking && moved,
        )
    }

    private fun ClockReading.bump() = copy(discontinuity = discontinuity + 1)

    private fun ClockReading.extrapolatedTo(nowNanos: Long): ClockReading {
        if (!isAdvancing || nowNanos <= capturedAtNanos) return this
        val elapsedMs = (nowNanos - capturedAtNanos) / NANOS_PER_MS
        return copy(positionMs = positionMs + (elapsedMs * rate).toLong(), capturedAtNanos = nowNanos)
    }

    private fun expectedPositionMs(r: ClockReading): Long = r.extrapolatedTo(nanoTime()).positionMs

    private fun cancelSeekJob() {
        seekJob?.cancel()
        seekJob = null
    }

    private companion object {
        const val JUMP_THRESHOLD_MS = 150L
        const val SEEK_POLL_MS = 8L
        const val MAX_SEEK_MS = 3_000L
        const val NANOS_PER_MS = 1_000_000.0
    }
}
