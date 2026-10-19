package com.blockapp.android.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

data class LaunchableApp(val packageName: String, val label: String)

/**
 * Every call here is a binder round trip, so all of it is blocking: call it off the main thread.
 * Calling [listLaunchableApps] inside `remember {}` during composition is what used to freeze
 * AppPickerScreen and FocusModeScreen for most of a second on open.
 */
object InstalledAppsProvider {

    /**
     * Packages with a launcher icon, minus this app and [ProtectedPackages]. One
     * queryIntentActivities call and no labels, which makes it cheap enough to re-run on every
     * screen-time read (UsageStatsProvider), so an app installed a minute ago still shows up.
     */
    fun launchablePackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapTo(HashSet()) { it.activityInfo.packageName }
            .apply {
                remove(context.packageName)
                removeAll(ProtectedPackages.ALL)
            }
    }

    /**
     * [packageName]'s display name, memoised for the life of the process. Resolving a label
     * loads that app's resources, which costs a few milliseconds per app. Done for every
     * installed app on every usage read, it was the largest single cost of opening Home and
     * Insights. A rename after an app update shows on the next process start, which is fine.
     */
    fun label(context: Context, packageName: String): String =
        labels.getOrPut(packageName) {
            val pm = context.packageManager
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }
        }

    fun listLaunchableApps(context: Context): List<LaunchableApp> =
        launchablePackages(context)
            .map { LaunchableApp(it, label(context, it)) }
            .sortedBy { it.label.lowercase() }

    private val labels = ConcurrentHashMap<String, String>()
}
