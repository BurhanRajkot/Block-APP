package com.blockapp.android.admin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.blockapp.android.accessibility.AppBlockAccessibilityService

/**
 * Android 13+'s "Restricted setting" wall (Enhanced Confirmation Mode from Android 15), which is
 * why the Accessibility switch is greyed out on a sideloaded install.
 *
 * The order of the way through is the whole problem, and it's not the order people try. The
 * "Allow restricted settings" item in App info's ⋮ menu does not exist until Android has blocked
 * the switch once and shown its "Restricted setting" dialog: the dialog itself is what marks
 * the app eligible (EnhancedConfirmationManager.setClearRestrictionAllowed). Open App info
 * first and the ⋮ menu isn't there at all, which is exactly what the user hit on a Pixel. So
 * OnboardingScreen walks it as: try the switch, then App info → ⋮ → Allow, then the switch again.
 *
 * Whether the app is actually restricted can't be read: EnhancedConfirmationManager.isRestricted
 * and the ACCESS_RESTRICTED_SETTINGS app-op both need system permissions. [likelyRestricted]
 * guesses from how the APK was installed, and is only ever used to pick wording, never to hide
 * a step, since a wrong guess must not strand anyone.
 */
object RestrictedSettings {

    /** The wall exists from API 33 (TIRAMISU); below that the switch simply works. */
    fun applies(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * True when the install came from a downloaded or local APK file, which is what Android
     * restricts. A store install, or `adb install` / `./gradlew installRelease` over USB, isn't
     * restricted on Android 13-14. Any failure reads as "likely", the safe side to err on.
     */
    fun likelyRestricted(context: Context): Boolean {
        if (!applies()) return false
        return try {
            val source = context.packageManager
                .getInstallSourceInfo(context.packageName)
                .packageSource
            source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE ||
                // Android 15's ECM also restricts some installs that report no source at all.
                // A literal because compileSdk 34 has no VANILLA_ICE_CREAM constant yet.
                (source == PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED &&
                    Build.VERSION.SDK_INT >= API_ANDROID_15)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Straight to this app's own switch rather than the whole Accessibility list, where it sits
     * several taps down under "Downloaded apps". The action is a hidden constant, but Settings
     * honours it for the service's own package when started for a result (so the calling
     * package is known). Where it doesn't, Settings falls back to the list itself; the caller
     * catches ActivityNotFoundException and opens [accessibilityListIntent].
     */
    fun accessibilityDetailsIntent(context: Context): Intent =
        Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).putExtra(
            Intent.EXTRA_COMPONENT_NAME,
            ComponentName(context, AppBlockAccessibilityService::class.java).flattenToString(),
        )

    fun accessibilityListIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    /** This app's App info page, where the ⋮ → "Allow restricted settings" item appears. */
    fun appInfoIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )

    private const val PREFS = "setup"
    private const val KEY_TRIED_SWITCH = "a11y_tried_switch"
    private const val KEY_OPENED_APP_INFO = "a11y_opened_app_info"

    /**
     * Which steps of the walk-through the user has started. Kept in SharedPreferences, not
     * Compose state, because the walk-through sends the user to Settings twice and the process
     * can be killed while they're there.
     *
     * Each mark stores the install's lastUpdateTime and only counts for that install. A
     * reinstall or an update resets the restriction, and Auto Backup restores these prefs, so
     * a plain boolean would come back saying "done" and send the user straight to an App info
     * page whose ⋮ item isn't there yet.
     */
    fun triedSwitch(context: Context): Boolean = isMarked(context, KEY_TRIED_SWITCH)
    fun openedAppInfo(context: Context): Boolean = isMarked(context, KEY_OPENED_APP_INFO)
    fun markTriedSwitch(context: Context) = mark(context, KEY_TRIED_SWITCH)
    fun markOpenedAppInfo(context: Context) = mark(context, KEY_OPENED_APP_INFO)

    private fun isMarked(context: Context, key: String): Boolean {
        val stamp = installStamp(context) ?: return false
        return prefs(context).getLong(key, NOT_MARKED) == stamp
    }

    private fun mark(context: Context, key: String) {
        val stamp = installStamp(context) ?: return
        prefs(context).edit().putLong(key, stamp).apply()
    }

    private fun installStamp(context: Context): Long? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (e: Exception) {
        null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val NOT_MARKED = -1L

    private const val API_ANDROID_15 = 35

    /** Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS, which is @SystemApi in the SDK stubs. */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
}
