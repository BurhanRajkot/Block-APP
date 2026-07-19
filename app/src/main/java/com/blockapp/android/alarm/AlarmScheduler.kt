package com.blockapp.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.blockapp.android.admin.DeviceAdminHelper

object AlarmScheduler {
    const val EXTRA_PACKAGE_NAME = "package_name"

    /**
     * Schedules the alarm that auto-expires this lock. Falls back to an inexact alarm when the
     * exact-alarm permission isn't granted (revocable by the user on API 33+) instead of letting
     * setExactAndAllowWhileIdle throw a SecurityException — a crash here would abort mid-lock
     * (see BlockRepository.lockApp, which inserts the row before calling this), leaving a lock
     * active with no expiry ever scheduled. An inexact alarm still fires close to on time and
     * keeps the lock self-healing even without that permission; the accessibility service's own
     * enforcement never depended on alarms in the first place.
     */
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
        if (DeviceAdminHelper.canScheduleExactAlarms(context)) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }
}
