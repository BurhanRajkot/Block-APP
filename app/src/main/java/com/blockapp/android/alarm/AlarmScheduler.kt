package com.blockapp.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.usage.UsageArchiveReceiver
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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

    /**
     * Arms the once-a-day UsageArchiveReceiver alarm, a little before midnight so it catches
     * nearly the whole day. Inexact and non-waking on purpose: nothing about it is urgent, and
     * a late or batched run loses nothing (UsageArchive replays the last week each time). Safe to
     * call repeatedly, since FLAG_UPDATE_CURRENT replaces the existing alarm. Called on every
     * process start and on boot, which also re-arms it after a force-stop cancels it.
     */
    fun scheduleDailyArchive(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            DAILY_ARCHIVE_REQUEST_CODE,
            Intent(context, UsageArchiveReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val zone = ZoneId.systemDefault()
        var first = LocalDate.now(zone).atTime(DAILY_ARCHIVE_TIME).atZone(zone).toInstant().toEpochMilli()
        if (first <= System.currentTimeMillis()) first += AlarmManager.INTERVAL_DAY
        alarmManager.setInexactRepeating(AlarmManager.RTC, first, AlarmManager.INTERVAL_DAY, pendingIntent)
    }

    /** Lock expiry alarms use packageName.hashCode() codes on a different receiver, so no clash. */
    private const val DAILY_ARCHIVE_REQUEST_CODE = 0x0A2C
    private val DAILY_ARCHIVE_TIME: LocalTime = LocalTime.of(23, 40)
}
