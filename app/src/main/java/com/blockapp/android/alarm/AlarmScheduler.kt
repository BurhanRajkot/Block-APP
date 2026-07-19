package com.blockapp.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object AlarmScheduler {
    const val EXTRA_PACKAGE_NAME = "package_name"

    fun schedule(context: Context, packageName: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, BlockExpiryReceiver::class.java).apply {
            putExtra(EXTRA_PACKAGE_NAME, packageName)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }
}
