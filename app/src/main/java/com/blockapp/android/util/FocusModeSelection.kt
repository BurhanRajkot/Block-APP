package com.blockapp.android.util

import android.content.Context

/**
 * User edits on top of [FocusModeApps.findTargets], kept in SharedPreferences rather than Room
 * so a schema bump isn't needed for a preference that isn't lock state. Survives process death
 * the same way the rest of this app's recovery does — next Focus Mode open reloads the last
 * include/exclude set instead of snapping back to the auto-detect list and undoing a choice
 * made before a kill.
 *
 * Two sets, not one, because the auto-detect list changes when apps are installed or
 * uninstalled: a single "current packages" snapshot would freeze a stale list and miss a newly
 * installed Instagram, or keep a package that no longer exists. Extras/excludes are deltas
 * against whatever [FocusModeApps] would pick *right now*.
 */
object FocusModeSelection {

    private const val PREFS = "focus_mode_selection"
    private const val EXTRA = "extra_packages"
    private const val EXCLUDED = "excluded_packages"

    fun resolve(context: Context): List<LaunchableApp> {
        val all = InstalledAppsProvider.listLaunchableApps(context)
        val extra = extras(context)
        val excluded = excluded(context)
        val byPackage = all.associateBy { it.packageName }
        val selected = linkedSetOf<String>()
        for (app in all) {
            if (FocusModeApps.isSuggested(context, app.packageName) && app.packageName !in excluded) {
                selected += app.packageName
            }
        }
        for (pkg in extra) {
            if (pkg in byPackage) selected += pkg
        }
        return selected.mapNotNull { byPackage[it] }.sortedBy { it.label.lowercase() }
    }

    fun include(context: Context, packageName: String) {
        edit(context) { extras, excluded ->
            extras += packageName
            excluded -= packageName
        }
    }

    fun exclude(context: Context, packageName: String) {
        edit(context) { extras, excluded ->
            extras -= packageName
            excluded += packageName
        }
    }

    /**
     * Clears the deltas so the next [resolve] is exactly what auto-detect would pick. Used when
     * the user wants the original Focus Mode set back without uninstalling extras one by one.
     */
    fun resetToSuggested(context: Context) {
        prefs(context).edit().remove(EXTRA).remove(EXCLUDED).apply()
    }

    fun hasCustomisation(context: Context): Boolean =
        extras(context).isNotEmpty() || excluded(context).isNotEmpty()

    private fun extras(context: Context): MutableSet<String> =
        prefs(context).getStringSet(EXTRA, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun excluded(context: Context): MutableSet<String> =
        prefs(context).getStringSet(EXCLUDED, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun edit(
        context: Context,
        mutate: (extras: MutableSet<String>, excluded: MutableSet<String>) -> Unit,
    ) {
        val extras = extras(context)
        val excluded = excluded(context)
        mutate(extras, excluded)
        prefs(context).edit()
            .putStringSet(EXTRA, extras)
            .putStringSet(EXCLUDED, excluded)
            .apply()
    }
}
