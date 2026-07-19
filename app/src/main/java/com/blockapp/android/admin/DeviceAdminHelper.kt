package com.blockapp.android.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

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

    fun requestAdminIntent(context: Context, explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        }
}
