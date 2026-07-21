package com.blockapp.android.usage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.blockapp.android.util.InstalledAppsProvider
import java.util.Calendar

data class AppUsage(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long,
)

object UsageStatsProvider {

    /**
     * PACKAGE_USAGE_STATS is a special app-op granted from the "Usage access" settings list,
     * not a normal runtime permission — there's no ActivityResultContract for it, so callers
     * have to poll this (typically on resume, after sending the user to that settings screen).
     */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Per-app foreground time since local midnight, descending by time spent.
     *
     * Joined against [InstalledAppsProvider.listLaunchableApps] rather than showing every
     * package the system tracks — raw usage-stats results are full of background services,
     * IMEs, and system UI packages a user never "opened", which is noise for a screen-time view.
     * That join also keeps this app and the protected system packages out of the list, matching
     * the same exclusions already applied everywhere else app identity is shown.
     */
    fun todayUsageByApp(context: Context): List<AppUsage> {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()

        // INTERVAL_BEST, not INTERVAL_DAILY: with a fixed bucket size the result can snap to
        // bucket boundaries instead of the requested range, so an explicit "since local
        // midnight" query would drift. INTERVAL_BEST lets the system pick the finest bucket
        // that still covers the whole range.
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startOfDay, now)
            ?: return emptyList()

        val launchableLabels = InstalledAppsProvider.listLaunchableApps(context)
            .associate { it.packageName to it.label }

        return stats
            .filter { it.totalTimeInForeground > 0L }
            .groupBy { it.packageName }
            .mapNotNull { (pkg, entries) ->
                val label = launchableLabels[pkg] ?: return@mapNotNull null
                AppUsage(pkg, label, entries.sumOf { it.totalTimeInForeground })
            }
            .sortedByDescending { it.foregroundMillis }
    }
}
