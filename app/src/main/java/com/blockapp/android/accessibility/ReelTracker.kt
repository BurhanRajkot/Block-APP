package com.blockapp.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decides when the user is watching short-form video (Reels, Shorts, TikTok, Spotlight, …) in
 * any app, counts the videos, and times the stretch spent watching. The floating ReelBubble is
 * shown for exactly as long as a *session* is open here.
 *
 * Two independent sources feed it, because neither works in every app:
 *  - [ReelScreenScanner] looks at the screen after content changes. It can open a session on the
 *    first reel, before any swipe, and it notices the page changing in apps whose pager never
 *    sends scroll events.
 *  - [ReelScrollDetector] measures swipes. A reel pager always comes to rest exactly one
 *    container height per swipe, and a feed fling stops anywhere. That confirms viewers the
 *    scanner wasn't sure about, counts fast swipes the throttled scanner would merge, and spots
 *    the user scrolling a feed, which ends the session.
 *
 * Counting rules, in the order they were needed:
 *  - Counting is by furthest position reached per container, not by swipes, so swiping back to
 *    rewatch a reel and forward again doesn't count it twice.
 *  - Once a session has seen its pager scroll, swipes are the only counter. Page changes the
 *    scanner sees are then the same swipes seen twice.
 *  - A session opened on the same page that ended the last one (back from comments, the
 *    notification shade, another app) doesn't count that reel again.
 *  - Watch time is the gap between counted reels, each capped at [MAX_REEL_MS], so a phone left
 *    on one reel doesn't log an hour.
 *
 * Observe-only, like everything reel-related: it never acts on the foreground app, and nothing
 * in here may throw into the service that enforces locks (CLAUDE.md invariant 9). Owned by
 * AppBlockAccessibilityService. Its public methods are called on the main thread, and only
 * [ReelScreenScanner] runs on [scanThread]. Needs API 28 for scroll deltas.
 *
 * Logs every decision under the [TAG] tag (`adb logcat -s ReelTracker`), so a miscount
 * on a real device can be traced to the rule that caused it.
 */
@RequiresApi(Build.VERSION_CODES.P)
internal class ReelTracker(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val ignoredPackages: Set<String>,
    private val listener: Listener,
) {
    interface Listener {
        fun onReelsWatched(packageName: String, count: Int, watchMs: Long)
        fun onSessionStarted(packageName: String)
        fun onSessionEnded(packageName: String)
    }

    /** A page as the scanner saw it. See [ReelScreenScanner.Match.pageSignature]. */
    private class PageKey(val node: AccessibilityNodeInfo, val signature: String) {
        fun sameReelAs(other: PageKey) =
            node == other.node || (signature.isNotEmpty() && signature == other.signature)
    }

    private class Session(val packageName: String, now: Long) {
        val id = nextSessionId++
        var lastMark = now
        /** True once a snapped swipe was seen. From then on only swipes count. */
        var scrollDriven = false
        var lastPage: AccessibilityNodeInfo? = null
        /** The current page and the ones just before it, oldest first, to spot swiping back. */
        val recentPages = ArrayDeque<PageKey>()
        var missedScans = 0
        var firstMissAt = 0L
    }

    private class Container {
        var position = 0
        var furthest = 0
        /** Furthest position already counted, or -1 if this container's start hasn't been. */
        var countedThrough = -1
        var sessionId = -1
        var snappedStreak = 0
        /** Learned from the first snapped swipe, so clipped bounds can't throw the next ones off. */
        var pageHeight = 0
        /** Until then, scanner matches on this container are ignored: it just scrolled like a feed. */
        var feedUntil = 0L

        fun reset() {
            position = 0
            furthest = 0
            countedThrough = -1
            sessionId = -1
            snappedStreak = 0
        }
    }

    private class Candidate(val packageName: String, val pager: AccessibilityNodeInfo, val at: Long)

    private var session: Session? = null
    private var candidate: Candidate? = null
    private val endedPages = HashMap<String, Pair<PageKey, Long>>()

    /** Access-ordered and capped, so scrollers seen once and never again don't accumulate. */
    private val containers =
        object : LinkedHashMap<AccessibilityNodeInfo, Container>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<AccessibilityNodeInfo, Container>,
            ) = size > MAX_TRACKED_CONTAINERS
        }

    private val detector = ReelScrollDetector(
        handler = handler,
        ignoredPackages = ignoredPackages,
        onBurst = ::onBurst,
    )

    private val scanThread = HandlerThread("ReelScanner").apply { start() }
    private val scanHandler = Handler(scanThread.looper)
    private var scanPending = false
    private var lastScanAt = 0L
    private var released = false
    private val runScan = Runnable { startScan() }

    // ── inputs ────────────────────────────────────────────────────────────────────────────────

    fun onScrolled(event: AccessibilityEvent) {
        val metrics = service.resources.displayMetrics
        detector.onScrolled(event, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Any content change in the foreground app. These arrive many times a second while a video
     * plays, so this only arms a throttled scan and returns.
     */
    fun onContentChanged(packageName: String) {
        if (packageName !in ignoredPackages) requestScan()
    }

    /**
     * A window came to the front. A different app ends the session, unless it is one of
     * [transientPackages] (keyboard, notification shade), which open on top of the reel viewer
     * without the user leaving it.
     */
    fun onWindowStateChanged(packageName: String, className: String, transientPackages: Set<String>) {
        val current = session
        if (current != null && packageName != current.packageName && packageName !in transientPackages) {
            // This app's own non-activity windows (the bubble itself, the Tier 1 toast) report
            // as its package. Treating them as a switch would end the session the moment the
            // bubble appears.
            val ownOverlay = packageName == service.packageName &&
                !className.startsWith("${service.packageName}.")
            if (!ownOverlay) endSession("switched to $packageName")
        }
        if (packageName !in ignoredPackages) requestScan()
    }

    /** The bubble must not sit over the lock screen, and nothing is watched with the screen off. */
    fun onScreenOff() {
        detector.cancel()
        endSession("screen off")
    }

    fun release() {
        released = true
        handler.removeCallbacks(runScan)
        detector.cancel()
        endSession("service unbound")
        scanThread.quitSafely()
    }

    // ── scanning ──────────────────────────────────────────────────────────────────────────────

    private fun requestScan() {
        if (scanPending || released) return
        scanPending = true
        val now = SystemClock.uptimeMillis()
        val delay = maxOf(SCAN_DELAY_MS, lastScanAt + SCAN_INTERVAL_MS - now)
        handler.postDelayed(runScan, delay)
    }

    private fun startScan() {
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        // Reels are watched upright. In landscape, full-screen video players would fit the
        // pager shape, and the bubble's camera anchor is wrong anyway.
        if (width > height) {
            finishScan(rootPackage = null, match = null, failed = true)
            return
        }
        scanHandler.post {
            var rootPackage: String? = null
            var match: ReelScreenScanner.Match? = null
            var failed = false
            try {
                val root = service.rootInActiveWindow
                rootPackage = root?.packageName?.toString()
                if (root != null && rootPackage !in ignoredPackages) {
                    match = ReelScreenScanner.scan(root, width, height)
                }
            } catch (e: RuntimeException) {
                // The window can vanish mid-walk (IllegalStateException), or the binder call
                // can fail. A failed scan says nothing either way, so it mustn't count as a
                // miss either.
                failed = true
            }
            handler.post { finishScan(rootPackage, match, failed) }
        }
    }

    private fun finishScan(rootPackage: String?, match: ReelScreenScanner.Match?, failed: Boolean) {
        scanPending = false
        lastScanAt = SystemClock.uptimeMillis()
        if (released || failed || rootPackage == null || rootPackage in ignoredPackages) return
        try {
            onScanResult(rootPackage, match)
        } catch (e: RuntimeException) {
            Log.w(TAG, "scan result dropped", e)
        }
    }

    private fun onScanResult(rootPackage: String, rawMatch: ReelScreenScanner.Match?) {
        val now = SystemClock.uptimeMillis()
        val match = rawMatch?.takeUnless { (containers[it.pager]?.feedUntil ?: 0L) > now }
        // Backstop for a switch whose window-state event was dropped: the bubble would
        // otherwise follow the user into the next app.
        session?.let { if (it.packageName != rootPackage) endSession("$rootPackage in front") }
        val current = session

        if (match == null) {
            // Missing for two scans in a row, at least END_AFTER_MS apart, before giving up.
            // A single miss is often just a swipe caught mid-settle, with two pages on screen.
            if (current != null) {
                if (current.missedScans++ == 0) current.firstMissAt = now
                if (current.missedScans >= END_AFTER_MISSES && now - current.firstMissAt >= END_AFTER_MS) {
                    endSession("reel viewer gone")
                } else {
                    // Scans only run on content changes. If the user landed on a screen that
                    // then sits still (a feed, a profile), nothing would trigger the
                    // confirming scan, and the bubble would stay up over it.
                    requestScan()
                }
            }
            return
        }

        if (current != null) {
            current.missedScans = 0
            if (match.page != current.lastPage) {
                val previous = current.lastPage
                current.lastPage = match.page
                val key = PageKey(match.page, match.pageSignature)
                val revisit = current.recentPages.any { it.sameReelAs(key) }
                current.recentPages.removeAll { it.sameReelAs(key) }
                current.recentPages.addLast(key)
                while (current.recentPages.size > RECENT_PAGES) current.recentPages.removeFirst()
                if (previous != null && !current.scrollDriven && !revisit) {
                    deferPageChangeCount(current)
                }
            }
            return
        }

        if (!match.confident) {
            candidate = Candidate(rootPackage, match.pager, now)
            return
        }
        val key = PageKey(match.page, match.pageSignature)
        val ended = endedPages[rootPackage]
        val sameReelAsBefore = ended != null && ended.first.sameReelAs(key) && now - ended.second < REVISIT_MS
        startSession(rootPackage, "scanner match")
        session?.let {
            it.lastPage = match.page
            it.recentPages.addLast(key)
        }
        if (!sameReelAsBefore) count(1)
    }

    /**
     * A page change the scanner saw counts as a new reel unless a measured swipe claims it
     * first. The wait is needed because both can see the same swipe. A View pager's burst is
     * only scored SETTLE_MS after it stops, often after the scan that saw the page change.
     * Compose pagers, on the other hand, report scroll deltas in an estimated unit rather than
     * pixels (a 2209px page swipe arrived as -7px on an emulator), so their bursts never snap
     * and this is the only counter that works for them.
     */
    private fun deferPageChangeCount(current: Session) {
        val sessionId = current.id
        handler.postDelayed(
            {
                val live = session
                if (!released && live != null && live.id == sessionId && !live.scrollDriven) {
                    Log.d(TAG, "${live.packageName}: page changed, no swipe measured, +1")
                    count(1)
                }
            },
            PAGE_CHANGE_GRACE_MS,
        )
    }

    // ── swipes ────────────────────────────────────────────────────────────────────────────────

    private fun onBurst(burst: ScrollBurst) {
        try {
            scoreBurst(burst)
        } catch (e: RuntimeException) {
            Log.w(TAG, "burst dropped", e)
        }
    }

    private fun scoreBurst(burst: ScrollBurst) {
        val now = SystemClock.uptimeMillis()
        val pkg = burst.packageName
        val container = containers.getOrPut(burst.node) { Container() }
        val active = session?.takeIf { it.packageName == pkg }

        // Inside a session a looser fit is fine: the viewer is already established, and a
        // pager whose bounds are clipped by a system bar is off by a few percent every swipe.
        val tolerance = if (active != null) LENIENT_SNAP else STRICT_SNAP
        val page = if (container.pageHeight > 0) container.pageHeight else burst.containerHeight
        val pages = (burst.netDeltaY.toFloat() / page).roundToInt()
        val offBoundary = abs(burst.netDeltaY - pages * page) > page * tolerance
        // RecyclerView/ViewPager report first/last visible adapter position. Two different
        // values at rest means several items share the screen, which is a feed, even if the
        // distance happened to land on a page boundary.
        val severalVisible = burst.fromIndex >= 0 && burst.toIndex >= 0 && burst.fromIndex != burst.toIndex
        Log.d(
            TAG,
            "$pkg: burst moved ${burst.netDeltaY}px over a ${page}px page ($pages pages, " +
                "items ${burst.fromIndex}..${burst.toIndex}, pager actions ${burst.hasPageActions})",
        )

        if (offBoundary || severalVisible || abs(pages) > MAX_PAGES_PER_BURST) {
            container.reset()
            container.feedUntil = now + FEED_COOLDOWN_MS
            if (candidate?.pager == burst.node) candidate = null
            if (active != null) endSession("feed-like scroll")
            return
        }
        // Snapped back to where it started: an aborted swipe. No evidence either way.
        if (pages == 0) return

        container.feedUntil = 0L
        if (container.pageHeight == 0) container.pageHeight = abs(burst.netDeltaY) / abs(pages)
        container.position += pages
        container.furthest = maxOf(container.furthest, container.position)

        if (active != null) {
            active.scrollDriven = true
            if (container.sessionId != active.id) {
                // First swipe in this container this session. The page it started on is the
                // one the session already counted, when it opened or on an earlier visit.
                container.sessionId = active.id
                container.countedThrough =
                    maxOf(container.countedThrough, container.position - pages)
            }
            countThrough(container)
            return
        }

        container.snappedStreak++
        val recentCandidate = candidate?.let {
            it.packageName == pkg && now - it.at < CANDIDATE_TTL_MS
        } == true
        // One snapped swipe is enough when something else already says "pager". Otherwise a
        // feed fling landing on a page boundary by chance (~4%) would start a session, so two
        // in a row are required (well under 1%).
        val strong = burst.hasPageActions || recentCandidate ||
            (burst.fromIndex >= 0 && burst.fromIndex == burst.toIndex)
        if (container.snappedStreak >= if (strong) 1 else CONFIRM_STREAK) {
            startSession(pkg, if (strong) "one snapped swipe" else "two snapped swipes")
            val opened = session ?: return
            opened.scrollDriven = true
            container.sessionId = opened.id
            // countedThrough is still -1 for a container never counted before, so the reel the
            // user started on is counted along with the ones swiped through.
            countThrough(container)
        }
    }

    private fun countThrough(container: Container) {
        val gained = container.furthest - container.countedThrough
        if (gained <= 0) return
        container.countedThrough = container.furthest
        Log.d(TAG, "${session?.packageName}: swiped to a new reel, +$gained")
        count(gained)
    }

    // ── sessions ──────────────────────────────────────────────────────────────────────────────

    private fun startSession(packageName: String, reason: String) {
        if (session != null) endSession("replaced by $packageName")
        session = Session(packageName, SystemClock.uptimeMillis())
        candidate = null
        Log.d(TAG, "$packageName: session started ($reason)")
        listener.onSessionStarted(packageName)
    }

    private fun count(reels: Int) {
        val current = session ?: return
        listener.onReelsWatched(current.packageName, reels, takeWatchTime(current))
    }

    private fun endSession(reason: String) {
        val ended = session ?: return
        session = null
        ended.recentPages.lastOrNull()?.let { endedPages[ended.packageName] = it to SystemClock.uptimeMillis() }
        Log.d(TAG, "${ended.packageName}: session ended ($reason)")
        val watched = takeWatchTime(ended)
        if (watched > 0L) listener.onReelsWatched(ended.packageName, 0, watched)
        listener.onSessionEnded(ended.packageName)
    }

    private fun takeWatchTime(session: Session): Long {
        val now = SystemClock.uptimeMillis()
        val watched = (now - session.lastMark).coerceIn(0L, MAX_REEL_MS)
        session.lastMark = now
        return watched
    }

    companion object {
        const val TAG = "ReelTracker"

        private var nextSessionId = 0

        /** Wait after a content change before scanning, so the screen has settled. */
        private const val SCAN_DELAY_MS = 250L

        /**
         * Minimum spacing between scans. Content changes never stop while a video plays, so
         * this is effectively the scan rate while in a video app: about one cheap tree walk a
         * second on a background thread.
         */
        private const val SCAN_INTERVAL_MS = 800L

        /**
         * A session ends after this many scans in a row find no viewer, the first and last at
         * least END_AFTER_MS apart. Two misses one SCAN_INTERVAL_MS apart is about the
         * quickest that can't be fooled by a single swipe caught mid-settle.
         */
        private const val END_AFTER_MISSES = 2
        private const val END_AFTER_MS = 700L

        /**
         * How long a scanner-seen page change waits for a measured swipe to claim it. It must
         * cover SETTLE_MS plus the scan that saw the change landing before the burst ended.
         */
        private const val PAGE_CHANGE_GRACE_MS = 1_200L

        /**
         * Pages remembered to recognise swiping back (the current one included). Kept short
         * because RecyclerView reuses a page's view, and so its node, a few pages later for a
         * different reel.
         */
        private const val RECENT_PAGES = 3

        /** How long reopening on the same reel is "the same reel" rather than a new watch. */
        private const val REVISIT_MS = 10 * 60_000L

        /** How long an unsure scanner match lets a single swipe confirm. */
        private const val CANDIDATE_TTL_MS = 60_000L

        /** How long a container that scrolled like a feed is ignored by the scanner. */
        private const val FEED_COOLDOWN_MS = 30_000L

        /**
         * Fit of a swipe to a whole number of pages. 2% is tight enough that a feed fling
         * passes only ~4% of the time. 8% is used once a session is open, to absorb system bars.
         */
        private const val STRICT_SNAP = 0.02f
        private const val LENIENT_SNAP = 0.08f

        /** Swipes chained within SETTLE_MS move a few pages; more is a fling through a feed. */
        private const val MAX_PAGES_PER_BURST = 3

        private const val CONFIRM_STREAK = 2

        /** Longest a single reel can add to watch time. YouTube Shorts run up to 3 minutes. */
        private const val MAX_REEL_MS = 3 * 60_000L

        private const val MAX_TRACKED_CONTAINERS = 8
    }
}
