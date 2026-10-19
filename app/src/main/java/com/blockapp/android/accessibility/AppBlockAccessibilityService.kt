package com.blockapp.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.blockapp.android.BlockApplication
import com.blockapp.android.R
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.ui.BlockOverlayActivity
import com.blockapp.android.util.ProtectedPackages
import com.blockapp.android.util.ReelApps
import com.blockapp.android.util.ReelLimit
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId

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
 *
 * Separately from both tiers, scroll and content-change events feed [ReelTracker], which counts
 * short-form videos watched per app (and the time spent on them) for HomeScreen and Insights,
 * and opens a session while a reel viewer is on screen, during which [ReelBubble] floats
 * today's total beside the camera. Both only observe and never act on the foreground app, so
 * they have no bearing on enforcement. Content-change events in particular go nowhere near
 * Tier 1 or Tier 2; they only arm the tracker's throttled scan.
 *
 * Tier 3 – the daily reel limit (util/ReelLimit), the one place reel tracking leads to action:
 *   once today's total reaches the user's limit, a reel session that opens (or one that is open
 *   when the limit is reached) is answered with GLOBAL_ACTION_BACK and the reel-limit wall. It
 *   acts only on what ReelTracker already calls a reel viewer, which never includes this app,
 *   launchers, or ProtectedPackages, so it can't reach the screens setup and removal depend on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppBlockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var serviceScope: CoroutineScope? = null
    private var watchdogScheduled = false
    private var reelTracker: ReelTracker? = null
    private var reelBubble: ReelBubble? = null
    private var bubbleTransientPackages: Set<String> = emptySet()

    /**
     * Screen-off ends the reel session. Without it the bubble, an accessibility overlay, stays
     * up over the lock screen, and the session's watch time keeps running while nobody watches.
     */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Only ever registered from API 28, alongside the tracker (see onServiceConnected).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) reelTracker?.onScreenOff()
        }
    }
    private var screenOffRegistered = false

    private var reelSessionPackage: String? = null
    private var reelTotalToday = -1
    private var lastLimitKickAt = 0L
    /** What the reel wall shows: today's limit, or a reel-free window ending at [until]. */
    private class ReelWall(val packageName: String, val limit: Int, val until: Long, val quietFrom: Long?)

    private var pendingLimitWall: ReelWall? = null

    private val showReelLimitWall = Runnable {
        val wall = pendingLimitWall ?: return@Runnable
        pendingLimitWall = null
        startActivity(
            Intent(this, BlockOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
                putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, wall.packageName)
                putExtra(BlockOverlayActivity.EXTRA_BLOCK_UNTIL, wall.until)
                putExtra(BlockOverlayActivity.EXTRA_REEL_LIMIT, wall.limit)
                wall.quietFrom?.let { putExtra(BlockOverlayActivity.EXTRA_QUIET_FROM, it) }
            },
        )
    }

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
            if (blockUntil != null) kickToHome(currentPackage, blockUntil)
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceScope = scope

        // The tracker needs scrollDeltaY, which AccessibilityEvent only gained in API 28 (P).
        // Below that the counter simply stays at zero, and HomeScreen says why.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bubble = ReelBubble(this, app.repository, scope) { ReelLimit.todayLimit(this) }
            reelBubble = bubble
            bubbleTransientPackages = bubbleTransientPackages()
            reelTracker = ReelTracker(
                service = this,
                handler = handler,
                // An allowlist, not a blocklist: see ReelApps.KNOWN for the browser images that
                // were counted as reels when every app was watched.
                trackedPackages = ReelApps.KNOWN,
                listener = object : ReelTracker.Listener {
                    override fun onReelsWatched(packageName: String, count: Int, watchMs: Long) {
                        app.repository.addReelsWatched(packageName, count, watchMs)
                    }

                    override fun onSessionStarted(packageName: String) {
                        reelSessionPackage = packageName
                        bubble.show(packageName)
                        enforceReelLimit()
                    }

                    override fun onSessionEnded(packageName: String) {
                        reelSessionPackage = null
                        bubble.hide()
                    }
                },
            )
            // Not exported: screen-off is a system broadcast, which reaches a not-exported
            // receiver anyway, and nothing else should be able to end a session.
            ContextCompat.registerReceiver(
                this,
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            screenOffRegistered = true

            // Today's total, re-keyed at midnight so the limit resets with the day.
            scope.launch {
                todayTicker()
                    .flatMapLatest { day -> app.repository.observeReelTotal(day) }
                    .collect { total -> onReelTotal(total) }
            }
            // The reel plan's next step, applied the moment the day turns, so a new week's
            // lower limit doesn't wait for the app to be opened. The process-start and daily
            // alarm runs cover the rest (see ReelPlan.refresh).
            scope.launch {
                todayTicker().collect {
                    try {
                        ReelPlan.refresh(this@AppBlockAccessibilityService, app.repository)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeException) {
                        // A plan step is not worth the service (invariant 9).
                        Log.w("ReelPlan", "refresh failed", e)
                    }
                }
            }
        }
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
        handler.removeCallbacks(showOverlay)
        handler.removeCallbacks(showReelLimitWall)
        reelSessionPackage = null
        reelTotalToday = -1
        watchdogScheduled = false
        // The tracker only exists from API 28, but the field's type doesn't say so.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) reelTracker?.release()
        reelTracker = null
        reelBubble?.hide(immediate = true)
        reelBubble = null
        if (screenOffRegistered) {
            screenOffRegistered = false
            unregisterReceiver(screenOffReceiver)
        }
        serviceScope?.cancel()
        serviceScope = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) reelTracker?.onScrolled(event)
                return
            }
            // Many per second while a video plays. Only the tracker's throttle sees these.
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) reelTracker?.onContentChanged(pkg, bubbleTransientPackages)
                return
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> Unit
            else -> return
        }
        val eventPackage = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()

        // Runs before Tier 1/2 so a bounce home also ends the reel session and its bubble.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            reelTracker?.onWindowStateChanged(eventPackage, className, bubbleTransientPackages)
        }

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
        activeLocks[currentPackage]?.let { blockUntil -> kickToHome(currentPackage, blockUntil) }
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

    /**
     * Sends the locked app home at once, then puts the block wall up [OVERLAY_DELAY_MS] later.
     *
     * The delay is load-bearing. GLOBAL_ACTION_HOME is carried out asynchronously by the
     * system, and started together with the wall it routinely landed *after* it, so the launcher
     * ended up on top and the wall was never seen: observed on an API 34 emulator, where the
     * log read overlay, overlay, HOME, HOME, overlay, HOME. The user was just dumped on the
     * home screen with no reason given. Re-posting one pending start on every kick means the
     * wall always comes last, after the final HOME of a burst. The HOME itself stays
     * immediate, so enforcement never waits on the delay.
     */
    private fun kickToHome(packageName: String, blockUntil: Long) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.removeCallbacks(showOverlay)
        pendingOverlay = packageName to blockUntil
        handler.postDelayed(showOverlay, OVERLAY_DELAY_MS)
    }

    private var pendingOverlay: Pair<String, Long>? = null

    private val showOverlay = Runnable {
        val (packageName, blockUntil) = pendingOverlay ?: return@Runnable
        pendingOverlay = null
        val overlayIntent = Intent(this, BlockOverlayActivity::class.java).apply {
            // NO_ANIMATION on top of the usual flags: without it the user sees the launcher's
            // window animation and then the overlay's, back to back, which reads as a glitch
            // rather than as one deliberate bounce.
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            putExtra(BlockOverlayActivity.EXTRA_PACKAGE_NAME, packageName)
            putExtra(BlockOverlayActivity.EXTRA_BLOCK_UNTIL, blockUntil)
        }
        startActivity(overlayIntent)
    }

    /**
     * Warns once as the total closes in on the limit, then enforces it. The first value after
     * (re)connecting only sets the baseline, so a restart mid-evening doesn't re-announce a
     * warning the user already saw.
     */
    private fun onReelTotal(total: Int) {
        val previous = reelTotalToday
        reelTotalToday = total
        val limit = ReelLimit.todayLimit(this)
        if (limit != null) {
            val warnAt = limit - LIMIT_WARNING_REELS
            if (previous in 0 until warnAt && total in warnAt until limit) {
                Toast.makeText(this, "${limit - total} reels left today", Toast.LENGTH_SHORT).show()
            }
        }
        // Runs with no limit too: reel-free hours apply whether or not a limit is set.
        enforceReelLimit()
    }

    /**
     * Tier 3: backs out of the open reel viewer and shows the reel-limit wall, if today's total
     * has reached the limit. BACK rather than HOME so only the reel viewer is left: the app
     * underneath (Instagram's feed, YouTube's home) stays usable, which is the promise the wall
     * makes. The wall follows OVERLAY_DELAY_MS later for the same reason as Tier 2's.
     */
    private fun enforceReelLimit() {
        val packageName = reelSessionPackage ?: return
        // Reel-free hours (ReelPlan) close a viewer whatever today's count is. Read on every
        // call rather than cached, so a window that starts while a session is open applies at
        // the next counted reel.
        val quiet = ReelPlan.quietWindowAt(this)
        val limit = ReelLimit.todayLimit(this)
        val wall = when {
            quiet != null -> ReelWall(packageName, limit ?: NO_LIMIT, until = quiet.second, quietFrom = quiet.first)
            // reelTotalToday is -1 until Room's first answer, so a 0 limit waits for it too.
            limit != null && reelTotalToday >= 0 && reelTotalToday >= limit ->
                ReelWall(packageName, limit, until = nextMidnight(), quietFrom = null)
            else -> return
        }
        val now = SystemClock.uptimeMillis()
        // One kick per burst: the session-start and total-changed paths often fire together.
        if (now - lastLimitKickAt < LIMIT_KICK_COOLDOWN_MS) return
        lastLimitKickAt = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.removeCallbacks(showReelLimitWall)
        pendingLimitWall = wall
        handler.postDelayed(showReelLimitWall, OVERLAY_DELAY_MS)
    }

    private fun todayTicker(): Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now())
            delay(DAY_CHECK_MS)
        }
    }.distinctUntilChanged()

    private fun nextMidnight(): Long =
        LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * Windows that open on top of the reel viewer without the user leaving it: the
     * notification shade and the keyboard (typing a comment). Hiding for those would make
     * the bubble flicker away every time either appears.
     */
    private fun bubbleTransientPackages(): Set<String> {
        val keyboards = getSystemService(InputMethodManager::class.java)
            ?.inputMethodList
            ?.map { it.packageName }
            .orEmpty()
        return keyboards.toSet() + SYSTEM_UI_PACKAGE
    }

    override fun onInterrupt() {}

    private companion object {
        /** How often the watchdog re-checks the foreground app against active locks. */
        const val WATCHDOG_INTERVAL_MS = 200L

        /**
         * Wait between sending a locked app home and showing the block wall. Long enough for
         * the HOME transition to finish (150-450ms in the emulator log), short enough that the
         * wall reads as the cause of the bounce rather than a separate event.
         */
        const val OVERLAY_DELAY_MS = 350L

        /** EXTRA_REEL_LIMIT for a reel-free-hours wall with no daily limit set. */
        const val NO_LIMIT = -1

        /** How many reels before the limit the "N reels left" warning appears. */
        const val LIMIT_WARNING_REELS = 10

        /** Minimum spacing between reel-limit kicks; see enforceReelLimit. */
        const val LIMIT_KICK_COOLDOWN_MS = 1_500L

        /** How often the day is re-checked so today's reel total resets at midnight. */
        const val DAY_CHECK_MS = 60_000L

        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

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
