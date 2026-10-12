package com.neuralsound.audio.internal.recorder

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Opt-in microphone foreground service. It shows the notification the app supplied through
 * [com.neuralsound.audio.recorder.MicForeground] and keeps the process eligible to record from
 * the background. Not exported; started only by [MicForegroundController].
 */
internal class MicrophoneForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pending = MicForegroundRegistry.take()
        if (pending == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val promoted = try {
            val foreground = pending.foreground
            val notification = foreground.notification(this)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            ServiceCompat.startForeground(this, foreground.notificationId, notification, type)
            true
        } catch (_: RuntimeException) {
            // ForegroundServiceStartNotAllowedException, SecurityException, IllegalStateException,
            // or an app notification builder that threw.
            false
        }
        pending.result.complete(promoted)
        if (!promoted) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
