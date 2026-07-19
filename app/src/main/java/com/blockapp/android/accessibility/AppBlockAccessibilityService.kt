package com.blockapp.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.blockapp.android.BlockApplication
import com.blockapp.android.R
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.ui.BlockOverlayActivity
import com.blockapp.android.util.ProtectedPackages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Two-tier protection:
 *
 * Tier 1 – active once Device Admin is active (i.e. setup is complete):
 *   Guards the system screens that could be used to strip this app of its protective
 *   capabilities: Accessibility settings (silently kills this service), Device Admin list/detail
 *   (first step to deactivating admin), and this app's own App Info / uninstall screens.
 *   Also guards the Play Store uninstall flow for this package.
 *
 *   Gating this on Device Admin being active (rather than running unconditionally) is what
 *   lets first-time setup complete at all: the Accessibility Service has to be turned on
 *   *before* Device Admin can be activated, so if this guard ran the moment the service turns
 *   on, it would immediately kick the user out of the Device Admin activation screen — a
 *   permanent lockout before protection is even armed. Once Device Admin is active, Tier 1 is
 *   fully armed and the only sanctioned way back into these screens is the in-app
 *   "Remove protection" flow (RemoveProtectionScreen), which deactivates admin itself,
 *   in-process, without ever needing to reach the guarded Settings UI.
 *
 * Tier 2 – Active only while locks are running:
 *   If any locked package comes to the foreground it is immediately kicked to the home
 *   screen and the block overlay is shown.
 *
 * Device Admin deactivation itself already goes through a system confirmation dialog with our
 * own warning text (see BlockDeviceAdminReceiver) -- the guard here stops reaching that
 * dialog at all.
 */
class AppBlockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null
    private var watchdogScheduled = false

    /**
     * Backstop against missed events. onAccessibilityEvent only fires when the system decides
     * a window state *changed*, and under rapid repeated open/close taps that event can be
     * coalesced or dropped entirely — by the notificationTimeout debounce, by OEM event
     * throttling, or by an animation race around performGlobalAction(HOME). If the one event
     * that would have caught a re-opened locked app is lost, nothing else fires afterward (the
     * app is just sitting there, unchanged) and it stays open for good, which is the "blocking
     * stops working after a while" bug. Polling the foreground window on a short fixed interval
     * closes that gap: even a fully missed event gets caught on the very next tick instead of
     * never.
     *
     * Only ever scheduled while [BlockApplication.repository]'s activeLocks is non-empty (see
     * the collector in onServiceConnected) — there is nothing to self-heal when nothing is
     * locked, so it costs zero wakeups the rest of the time this service is connected, which is
     * effectively all the time once Accessibility is granted.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            checkForegroundAgainstLocks()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    /**
     * This service (re)connects any time the system (re)binds it — notably after the app's
     * process was killed and restarted, whether by low memory or an OEM battery manager. In
     * that scenario a locked app can already be sitting in the foreground with no new
     * TYPE_WINDOW_STATE_CHANGED event pending (the window didn't just change, this service
     * just came back), so onAccessibilityEvent alone would never catch it until the user
     * switched away and back. Checking the current window here, straight from Room rather
     * than the in-memory cache (which may not have caught up with Room's async Flow yet this
     * soon after a cold start), closes that gap immediately; the collector started below then
     * keeps the watchdog running for as long as a lock is actually active.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        val app = application as BlockApplication
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        if (currentPackage != null) {
            val blockUntil = runBlocking { app.repository.getActiveLockUntil(currentPackage) }
            if (blockUntil != null) kickToHome(blockUntil)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope
        scope.launch {
            app.repository.activeLocks.collect { locks ->
                if (locks.isNotEmpty()) {
                    if (!watchdogScheduled) {
                        watchdogScheduled = true
                        handler.post(watchdog)
                    }
                } else if (watchdogScheduled) {
                    watchdogScheduled = false
                    handler.removeCallbacks(watchdog)
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(watchdog)
        watchdogScheduled = false
        serviceScope?.cancel()
        serviceScope = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val eventPackage = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()

        // Tier 1: guard our own protective system screens, but only once Device Admin is
        // active — otherwise this would block the setup flow itself (see class doc above).
        if (DeviceAdminHelper.isAdminActive(this) && isProtectiveSystemScreen(eventPackage, className)) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Toast.makeText(
                this,
                "This setting is protected by ${getString(R.string.app_name)}.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        // Tier 2: block locked apps (only when a lock is running)
        checkForegroundAgainstLocks(eventPackage)
    }

    /**
     * Looks up [knownPackage] (or, if absent, whatever's currently in front) against the active
     * locks and kicks it home if it's locked. Shared by the immediate event path (which already
     * knows the package from the event, so it skips the rootInActiveWindow call) and the
     * [watchdog] poll (which doesn't).
     */
    private fun checkForegroundAgainstLocks(knownPackage: String? = null) {
        val app = application as BlockApplication
        val activeLocks = app.repository.activeLocks.value
        if (activeLocks.isEmpty()) return
        val currentPackage = knownPackage ?: rootInActiveWindow?.packageName?.toString() ?: return
        activeLocks[currentPackage]?.let { blockUntil -> kickToHome(blockUntil) }
    }

    /**
     * Returns true if [eventPackage]/[className] is a system screen that could be used to
     * uninstall this app or revoke its permissions, regardless of whether a lock is active.
     */
    private fun isProtectiveSystemScreen(eventPackage: String, className: String): Boolean {
        // --- Settings app ---
        if (eventPackage in ProtectedPackages.SETTINGS) {
            // Accessibility settings — silently kills our service if the user disables it
            if (ACCESSIBILITY_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return true
            }
            // Device Admin screens — first step to removing admin, which unblocks uninstall
            if (DEVICE_ADMIN_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return true
            }
            // App Info / Uninstall screens — only when *this* app is the target
            if (APP_INFO_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return currentWindowMentionsSelf()
            }
        }

        // --- Package installer (AOSP & OEM variants) ---
        if (eventPackage in ProtectedPackages.PACKAGE_INSTALLER) {
            // Any uninstall-related screen in the installer
            if (UNINSTALLER_CLASS_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return currentWindowMentionsSelf()
            }
        }

        // --- Play Store uninstall flow ---
        if (eventPackage == ProtectedPackages.PLAY_STORE) {
            if (PLAY_STORE_UNINSTALL_HINTS.any { className.contains(it, ignoreCase = true) }) {
                return currentWindowMentionsSelf()
            }
        }

        return false
    }

    /**
     * Checks whether the currently active window contains any reference to this app
     * (by label or package name), so we only block when *our* app is the uninstall target.
     */
    private fun currentWindowMentionsSelf(): Boolean {
        val root = rootInActiveWindow ?: return false
        val label = getString(R.string.app_name)
        return root.findAccessibilityNodeInfosByText(label).isNotEmpty() ||
            root.findAccessibilityNodeInfosByText(packageName).isNotEmpty()
    }

    private fun kickToHome(blockUntil: Long) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockOverlayActivity.EXTRA_BLOCK_UNTIL, blockUntil)
        }
        startActivity(overlayIntent)
    }

    override fun onInterrupt() {}

    private companion object {
        /** How often the watchdog re-checks the foreground app against active locks. */
        const val WATCHDOG_INTERVAL_MS = 200L

        /** Matches any Accessibility-settings activity across OEMs. */
        val ACCESSIBILITY_CLASS_HINTS = listOf(
            "AccessibilitySettings",
            "AccessibilityService",
            "ToggleAccessibility",
            "AccessibilityDetail",
            "ManageAccessibility",
        )

        /** Matches Device Admin list and detail screens. */
        val DEVICE_ADMIN_CLASS_HINTS = listOf(
            "DeviceAdmin",
            "DevicePolicyManager",
            "ActiveAdmin",
        )

        /**
         * Matches App Info / App Details screens.
         * These are shared by all apps, so callers must also check [currentWindowMentionsSelf].
         */
        val APP_INFO_CLASS_HINTS = listOf(
            "InstalledAppDetails",
            "AppInfoDashboard",
            "AppInfoBase",
            "AppInfo",
            "ApplicationInfo",
            "AppDetailActivity",
            "ManageApplications",        // OEM: app list
            "AppStorageSettings",
            "AppPermissionActivity",
            "AppOpsDetails",
        )

        /** Matches uninstall confirmation / progress screens in the package installer. */
        val UNINSTALLER_CLASS_HINTS = listOf(
            "UninstallerActivity",
            "UninstallAppProgress",
            "UninstallConfirm",
            "UninstallFinish",
            "DeletePackage",
        )

        /** Matches Play Store uninstall confirmation dialogs. */
        val PLAY_STORE_UNINSTALL_HINTS = listOf(
            "UninstallActivity",
            "ConfirmUninstall",
            "AppActionDialog",
        )
    }
}
