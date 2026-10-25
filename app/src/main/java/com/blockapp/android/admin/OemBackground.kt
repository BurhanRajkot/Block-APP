package com.blockapp.android.admin

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The phone maker's own "let this app run" switches, which sit on top of Android's battery
 * settings and which Android's APIs can neither read nor change.
 *
 * On a Pixel, battery "Unrestricted" plus a running accessibility service is enough. On most
 * other phones it isn't. Xiaomi, Oppo/Realme/OnePlus, Vivo and Huawei/Honor kill background apps
 * that aren't on their own autostart list, and once the process is gone the accessibility
 * service has to wait for a rebind, during which locks aren't enforced. Xiaomi also keeps a
 * separate "Display pop-up windows while running in the background" permission, without which the
 * block wall never appears. Samsung puts apps it considers unused into "Sleeping apps".
 *
 * None of these can be checked from here, so each step is confirmed by the user ([markDone]) and
 * only ever presented as a help, never as required: a wrong guess about a ROM must not strand
 * anyone in setup. The screens are reached by explicit component names that differ across ROM
 * versions, so each step lists several and [open] tries them in order, ending on this app's App
 * info, which every phone has.
 *
 * Kept narrow on purpose: these OEM screens live in packages that ProtectedPackages treats as
 * Settings (com.miui.securitycenter, com.coloros.safecenter), and none of the activity names
 * below may match one of Tier 1's class-name hints, or the step would bounce itself.
 */
object OemBackground {

    enum class Maker(val label: String) {
        SAMSUNG("Samsung"),
        XIAOMI("Xiaomi"),
        OPPO("Oppo, Realme or OnePlus"),
        VIVO("Vivo"),
        HUAWEI("Huawei or Honor"),
        OTHER("this phone"),
    }

    class Step(
        val id: String,
        val title: String,
        val detail: String,
        /** Tried in order. Empty means the step is only advice, with nothing to open. */
        val intents: List<Intent>,
    )

    fun maker(): Maker {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            "samsung" in brand -> Maker.SAMSUNG
            listOf("xiaomi", "redmi", "poco").any { it in brand } -> Maker.XIAOMI
            listOf("oppo", "realme", "oneplus").any { it in brand } -> Maker.OPPO
            listOf("vivo", "iqoo").any { it in brand } -> Maker.VIVO
            listOf("huawei", "honor").any { it in brand } -> Maker.HUAWEI
            else -> Maker.OTHER
        }
    }

    fun steps(context: Context): List<Step> {
        val pkg = context.packageName
        return when (maker()) {
            Maker.SAMSUNG -> listOf(
                Step(
                    id = "samsung_never_sleep",
                    title = "Never put App Blocker to sleep",
                    detail = "Battery → Background usage limits → Never sleeping apps → + → App Blocker. " +
                        "Samsung otherwise sleeps apps it thinks you don't use, and a sleeping app can't block.",
                    intents = listOf(
                        component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                        component("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                        component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    ),
                ),
            )
            Maker.XIAOMI -> listOf(
                Step(
                    id = "xiaomi_autostart",
                    title = "Turn on Autostart",
                    detail = "Find App Blocker in the list and turn it on, so MIUI and HyperOS let it " +
                        "come back after a restart.",
                    intents = listOf(
                        component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                        component("com.miui.securitycenter", "com.miui.powercenter.autostart.AutoStartManagementActivity"),
                    ),
                ),
                Step(
                    id = "xiaomi_popups",
                    title = "Allow pop-ups in the background",
                    detail = "Other permissions → \"Display pop-up windows while running in the background\" → " +
                        "Allow. Without it the block screen never shows.",
                    intents = listOf(
                        component("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                            .putExtra("extra_pkgname", pkg),
                        component("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
                            .putExtra("extra_pkgname", pkg),
                    ),
                ),
                Step(
                    id = "xiaomi_battery",
                    title = "Battery saver: No restrictions",
                    detail = "Pick App Blocker and set it to \"No restrictions\".",
                    intents = listOf(
                        component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                            .putExtra("package_name", pkg)
                            .putExtra("package_label", "App Blocker"),
                    ),
                ),
            )
            Maker.OPPO -> listOf(
                Step(
                    id = "oppo_autostart",
                    title = "Allow auto launch",
                    detail = "Turn App Blocker on in the auto launch (or startup manager) list, and allow " +
                        "background activity under its battery settings.",
                    intents = listOf(
                        component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                        component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                        component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                        component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                    ),
                ),
            )
            Maker.VIVO -> listOf(
                Step(
                    id = "vivo_background",
                    title = "Allow background running",
                    detail = "Turn on autostart for App Blocker, and allow high background power use " +
                        "under Battery.",
                    intents = listOf(
                        component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                        component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    ),
                ),
            )
            Maker.HUAWEI -> listOf(
                Step(
                    id = "huawei_launch",
                    title = "Manage app launch by hand",
                    detail = "Find App Blocker, turn off \"Manage automatically\", and leave auto-launch, " +
                        "secondary launch and run in background all on.",
                    intents = listOf(
                        component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                        component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                        component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    ),
                ),
            )
            Maker.OTHER -> emptyList()
        } + Step(
            id = "recents_lock",
            title = "Lock it in Recents",
            detail = "Open Recents, tap App Blocker's icon above its card, and pick Lock or Keep open " +
                "where your phone offers it. Phones with a task cleaner skip locked apps.",
            intents = emptyList(),
        )
    }

    /**
     * Opens the first of [step]'s screens that this phone has. Returns false when none could be
     * opened, so the caller can say where to look by hand. OEM screens that exist but aren't
     * exported throw SecurityException, which is treated the same as missing.
     */
    fun open(context: Context, step: Step): Boolean {
        for (intent in step.intents + appInfo(context)) {
            try {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }

    fun isDone(context: Context, step: Step): Boolean {
        val stamp = installStamp(context) ?: return false
        return prefs(context).getLong(step.id, NOT_DONE) == stamp
    }

    fun markDone(context: Context, step: Step, done: Boolean) {
        val stamp = installStamp(context) ?: return
        prefs(context).edit().apply {
            if (done) putLong(step.id, stamp) else remove(step.id)
        }.apply()
    }

    private fun component(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun appInfo(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /**
     * Ticks are tied to this install's firstInstallTime. These OEM switches reset on a reinstall
     * but survive an update, and Auto Backup restores the prefs, so a plain boolean would come
     * back saying "done" for a switch the reinstall turned off.
     */
    private fun installStamp(context: Context): Long? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
    } catch (e: Exception) {
        null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "setup_oem"
    private const val NOT_DONE = -1L
}
