package com.blockapp.android.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class LaunchableApp(val packageName: String, val label: String)

object InstalledAppsProvider {

    fun listLaunchableApps(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        return resolved
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName && it !in ProtectedPackages.ALL }
            .map { pkg ->
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    pkg
                }
                LaunchableApp(pkg, label)
            }
            .sortedBy { it.label.lowercase() }
    }
}
