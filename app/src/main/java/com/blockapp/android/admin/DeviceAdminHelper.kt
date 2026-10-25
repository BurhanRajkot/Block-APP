package com.blockapp.android.admin

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.blockapp.android.accessibility.AppBlockAccessibilityService
import com.blockapp.android.accessibility.ServiceState

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

    /** The three states the Accessibility step can be in. See ServiceState for why it's three. */
    enum class AccessibilityState {
        /** Switched off in Settings. */
        OFF,

        /**
         * Switched on in Settings, but the service isn't running in this process. Right after a
         * process start this is normal for a moment, until the system rebinds. If it lasts, the
         * service crashed or was stopped, and the switch has to be turned off and on again.
         */
        ON_NOT_RUNNING,

        RUNNING,
    }

    fun accessibilityState(context: Context): AccessibilityState = when {
        ServiceState.connected.value -> AccessibilityState.RUNNING
        isAccessibilityEnabledInSettings(context) -> AccessibilityState.ON_NOT_RUNNING
        else -> AccessibilityState.OFF
    }

    /**
     * Whether the switch is on (or the service is running, which proves it). What setup gates
     * on: a switched-on service that isn't running yet needs a different fix, not the same
     * step again. See [accessibilityState] for telling those apart.
     */
    fun isAccessibilityActive(context: Context): Boolean =
        ServiceState.connected.value || isAccessibilityEnabledInSettings(context)

    /**
     * Two sources, either one enough. The Secure string is what AOSP Settings writes, but it is
     * a raw setting some OEM builds format their own way and some guard behind restricted
     * reads, and on those the app said "Accessibility is off" for a switch the user could see
     * was on. AccessibilityManager's enabled list is the framework's own view of the same
     * switch, so an entry in either one counts.
     */
    private fun isAccessibilityEnabledInSettings(context: Context): Boolean {
        val component = ComponentName(context, AppBlockAccessibilityService::class.java)
        val fromSecure = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
                ?.split(':')
                // Android stores entries as either "pkg/pkg.Class" or shorthand "pkg/.Class";
                // ComponentName.unflattenFromString() normalizes both before comparing.
                ?.any { raw -> ComponentName.unflattenFromString(raw.trim()) == component }
                ?: false
        } catch (e: RuntimeException) {
            false
        }
        if (fromSecure) return true
        return try {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val service = info.resolveInfo?.serviceInfo ?: return@any false
                    service.packageName == component.packageName && service.name == component.className
                }
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * "Display over other apps". Nothing is drawn with it (ReelBubble is an accessibility
     * overlay and needs none), but holding it is one of Android's exemptions from the
     * background start limits on activities and foreground services. On Xiaomi and some other
     * builds those limits otherwise stop the block wall appearing after a bounce home.
     */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * Whether Android 11+'s "Pause app activity if unused" (also "Remove permissions if app is
     * unused") is off for this app. It strips Accessibility's companion permissions and puts
     * the app to sleep after a few months untouched, which for a blocker used mostly through
     * its walls is a real risk. Below API 30 the feature doesn't exist.
     */
    fun isExemptFromHibernation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return try {
            context.packageManager.isAutoRevokeWhitelisted
        } catch (e: RuntimeException) {
            true
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
