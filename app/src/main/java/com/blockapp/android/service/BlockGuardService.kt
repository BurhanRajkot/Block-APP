package com.blockapp.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
        startForeground(NOTIFICATION_ID, buildNotification())
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, BlockGuardService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BlockGuardService::class.java))
        }
    }
}
