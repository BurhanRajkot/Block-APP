package com.blockapp.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.blockapp.android.MainActivity
import com.blockapp.android.R

/**
 * Foreground service whose only job is to keep this app's process — and therefore the bound
 * AppBlockAccessibilityService — alive at foreground priority for as long as any lock is
 * active. Without this, Android (and OEM battery managers especially) are free to kill the
 * process in the background the moment it's not visible; when that happens, enforcement stops
 * silently until the system gets around to rebinding the accessibility service, which can take
 * long enough that a "closed" blocked app reopens clean. Only BlockApplication starts/stops
 * this, by observing BlockRepository.activeLocks — never call start/stop from UI code directly,
 * or it'll fight with that observer.
 */
class BlockGuardService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The type is passed explicitly on API 34, where a foreground service must name the
        // type it was declared with. Guarded rather than trusted: if this throws, the process
        // goes with it, and the accessibility service and every running lock go too, over a
        // keep-alive that is only ever a help (invariant 9). The service is not stopped on
        // failure: stopping one started with startForegroundService before it reached
        // startForeground is itself a crash ("did not then call startForeground").
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "startForeground refused", e)
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.guard_notification_title))
            .setContentText(getString(R.string.guard_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .build()
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guard_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "block_guard"
        private const val NOTIFICATION_ID = 1

        private const val TAG = "BlockGuardService"

        /**
         * Returns false when Android refused the start, and never throws.
         *
         * Refusal is normal, not an edge case. From Android 12 a foreground service can't be
         * started while the app is in the background, and this app is started in the background
         * all the time: the system rebinds the accessibility service after killing the process,
         * and that cold start finds a running lock and lands here. The refusal is an
         * IllegalStateException (ForegroundServiceStartNotAllowedException), and uncaught it
         * crashed the whole process on every such restart. The accessibility service died with
         * it, so Settings still showed the switch on while nothing was blocked and the app
         * read "not protected": the crash a real phone hit. BlockApplication retries once the
         * app is in front again.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, BlockGuardService::class.java))
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "foreground service start not allowed now", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "foreground service start refused", e)
            false
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BlockGuardService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "stop failed", e)
            }
        }
    }
}
