package com.neuralsound.audio.internal.recorder

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.neuralsound.audio.recorder.MicForeground
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Seam so the draft can be tested without a real service. */
internal interface MicForegroundControl {
    /** Starts the foreground service; false when it could not be promoted. */
    suspend fun promote(foreground: MicForeground): Boolean

    /** Stops the service. Idempotent. */
    suspend fun stop()
}

/** Starts [MicrophoneForegroundService] and waits for its promotion result. */
internal class MicForegroundController(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : MicForegroundControl {
    private val context: Context = context.applicationContext
    private val lock = Mutex()
    private var running = false

    override suspend fun promote(foreground: MicForeground): Boolean = lock.withLock {
        if (running) return@withLock true
        val pending = MicForegroundRegistry.Pending(foreground)
        MicForegroundRegistry.set(pending)
        val intent = Intent(context, MicrophoneForegroundService::class.java)
        val started = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: RuntimeException) {
            false // e.g. ForegroundServiceStartNotAllowedException
        }
        val promoted = started && withTimeoutOrNull(timeoutMs) { pending.result.await() } == true
        MicForegroundRegistry.clear(pending)
        if (promoted) {
            running = true
        } else {
            withContext(NonCancellable) { runCatching { context.stopService(intent) } }
        }
        promoted
    }

    override suspend fun stop() {
        lock.withLock {
            if (!running) return@withLock
            running = false
            runCatching { context.stopService(Intent(context, MicrophoneForegroundService::class.java)) }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}
