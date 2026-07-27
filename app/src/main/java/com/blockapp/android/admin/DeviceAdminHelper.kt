package com.blockapp.android.admin

import android.app.admin.DevicePolicyManager
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Single place the app asks "is capability X currently granted?". Despite the name it covers
 * every protective capability, not just Device Admin — battery exemption, exact alarms and
 * notifications are all revocable at any moment, so every one of them is a live query rather
 * than something cached at setup time. OnboardingScreen and SettingsScreen re-run these on
 * every resume for exactly that reason.
 */
object DeviceAdminHelper {

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, BlockDeviceAdminReceiver::class.java)

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent(context))
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Whether this app can schedule exact alarms (used to auto-expire a lock at the right
     * moment). Below API 31 this was unrestricted, so it's always true there. On API 31-32 it's
     * granted automatically at install; on API 33+ the user must grant it explicitly via
     * Settings, and can revoke it later, so this must be re-checked rather than assumed.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    /**
     * Whether BlockGuardService's ongoing notification can actually be shown. Checked via
     * NotificationManagerCompat rather than the POST_NOTIFICATIONS permission alone: the
     * permission being granted still leaves the notification invisible if the user (or a
     * restore from another device) turned the app's notifications off at the app level, and
     * a hidden keep-alive notification is exactly what makes the service easy to kill unnoticed.
     */
    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Deactivates Device Admin directly, in-process — no Settings UI involved. An app holding
     * admin is always allowed to drop its own admin this way, which is what lets
     * RemoveProtectionScreen offer a real way out without ever touching the guarded Device
     * Admin / Accessibility system screens (see AppBlockAccessibilityService's Tier 1 doc).
     */
    fun removeAdmin(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.removeActiveAdmin(adminComponent(context))
    }

    fun isAccessibilityActive(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val component = ComponentName(
            context,
            com.blockapp.android.accessibility.AppBlockAccessibilityService::class.java,
        )
        // Android stores entries as either "pkg/pkg.Class" or shorthand "pkg/.Class" —
        // ComponentName.unflattenFromString() normalizes both before comparing.
        return enabledServices.split(":").any { raw ->
            ComponentName.unflattenFromString(raw) == component
        }
    }

    /** Returns true if any protective permission is missing and setup is needed. */
    fun requiresSetup(context: Context): Boolean =
        !isAdminActive(context) || !isAccessibilityActive(context)

    /**
     * Builds the Device Admin activation intent. **Launch it from an Activity, and never add
     * `FLAG_ACTIVITY_NEW_TASK`** — Settings' `DeviceAdminAdd` activity opens with
     *
     *     if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0) { finish(); return; }
     *
     * so the flag makes the activation screen appear and vanish in the same frame with no dialog,
     * no error and nothing in the UI to explain it. That looked exactly like "Device Admin is
     * broken on this device" and made setup impossible to finish, which in turn left the app
     * uninstallable in two taps. The flag is deliberately not applied here so no caller inherits
     * it by accident; see OnboardingScreen's launcher for the sanctioned call site.
     */
    fun requestAdminIntent(context: Context, explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        }
}
