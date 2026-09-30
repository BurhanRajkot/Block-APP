package com.blockapp.android.accessibility

import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Counts short-form videos (Reels, Shorts, TikToks, Spotlight, …) swiped through in any app, from
 * the TYPE_VIEW_SCROLLED events AppBlockAccessibilityService forwards to [onScrolled].
 *
 * There is deliberately no per-app list of view IDs. Those are obfuscated or renamed with every
 * app update, so they would quietly stop counting. The detector keys on what every short-form
 * viewer has in common instead: a **full-screen vertical pager that always comes to rest exactly
 * one screen-height per swipe**. A normal feed, like Instagram's home feed or Reddit's front page,
 * also fills the screen, but a fling there stops at an arbitrary offset. So:
 *
 *  - A *burst* is one run of scroll events from one container, ended by [SETTLE_MS] of quiet.
 *    Its summed scrollDeltaY is the exact distance moved, because the framework accumulates
 *    deltas between the events it throttles.
 *  - A burst is *snapped* when that distance is a whole number of container heights (within
 *    [SNAP_TOLERANCE]) and, if the container reports adapter indices, it came to rest with exactly
 *    one item visible.
 *  - A container is only trusted as a reel viewer after [CONFIRM_STREAK] snapped bursts in a row.
 *    One snapped burst in a feed is a ~5% coincidence, but two in a row is well under 1%. Any
 *    un-snapped burst drops that trust again, so a feed that got lucky once stops counting on its
 *    next ordinary fling.
 *
 * Counting is by furthest position reached, not by swipes. Swiping back to rewatch a reel and
 * then forward again doesn't count it twice. On confirmation the reel the user started on is
 * counted too. The cost is that a session of one or two reels never confirms and isn't counted.
 * That undercount is accepted over counting feed scrolls.
 *
 * Known blind spots, all verified only by reasoning, not on-device: horizontal pagers are
 * ignored on purpose (they are tab bars and photo galleries, not reels), so an app that fakes a
 * vertical pager by rotating a horizontal one is missed. A pager whose pages are shorter than
 * the container (peeking previews, content padding) never snaps. A Compose app that doesn't
 * populate scroll deltas is invisible.
 *
 * Owned by AppBlockAccessibilityService and only ever touched on the main thread. [onScrolled]
 * needs API 28 for [AccessibilityEvent.getScrollDeltaY]; below that the service never creates one.
 */
internal class ReelScrollDetector(
    private val handler: Handler,
    private val ignoredPackages: Set<String>,
    private val onReelsWatched: (packageName: String, count: Int) -> Unit,
    /**
     * A full-screen scroller in [packageName] just moved like a feed, not a pager. ReelBubble
     * uses this to get off the screen once the user leaves the reel viewer for the app's feed.
     */
    private val onFeedScrolled: (packageName: String) -> Unit,
) {
    private class Container(val packageName: String) {
        var pageHeight = 0
        var burstDeltaY = 0
        var lastFromIndex = -1
        var lastToIndex = -1
        var snappedStreak = 0
        var confirmed = false
        var position = 0
        var furthest = 0

        fun resetSession() {
            snappedStreak = 0
            confirmed = false
            position = 0
            furthest = 0
        }
    }

    /**
     * Keyed by node, whose equals() is (window, view, virtual view). That identifies the one
     * scrolling view instance, so a fresh reels feed opened later starts its own session instead
     * of inheriting the furthest position of the last one. Access-ordered and capped, so apps
     * and screens that scroll once and are never seen again don't accumulate.
     */
    private val containers = object : LinkedHashMap<AccessibilityNodeInfo, Container>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<AccessibilityNodeInfo, Container>,
        ) = size > MAX_TRACKED_CONTAINERS
    }

    private var burstNode: AccessibilityNodeInfo? = null
    private var burstContainer: Container? = null
    private val bounds = Rect()
    private val endBurst = Runnable { finishBurst() }

    @RequiresApi(Build.VERSION_CODES.P)
    fun onScrolled(event: AccessibilityEvent, screenWidth: Int, screenHeight: Int) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName in ignoredPackages) return
        val deltaY = event.scrollDeltaY
        // Zero-delta events carry no distance, and horizontal ones are tab pagers and galleries
        // (see class doc). Returning before getSource() also skips a binder call per event.
        if (deltaY == 0) return

        val node = event.source ?: return
        if (node != burstNode) {
            node.getBoundsInScreen(bounds)
            // Small scrollers (a comment sheet, a caption, a carousel inside a post) must return
            // before touching the open burst. Otherwise a nested scroll mid-swipe would cut the
            // pager's burst short and it would read as un-snapped, resetting a confirmed session.
            if (bounds.width() < screenWidth * MIN_WIDTH_FRACTION ||
                bounds.height() < screenHeight * MIN_HEIGHT_FRACTION
            ) {
                return
            }
            finishBurst()
            val container = containers.getOrPut(node) { Container(packageName) }
            container.pageHeight = bounds.height()
            container.burstDeltaY = 0
            burstNode = node
            burstContainer = container
        }

        val container = burstContainer ?: return
        container.burstDeltaY += deltaY
        container.lastFromIndex = event.fromIndex
        container.lastToIndex = event.toIndex
        handler.removeCallbacks(endBurst)
        handler.postDelayed(endBurst, SETTLE_MS)
    }

    /** Drops any open burst without scoring it, for when the service unbinds. */
    fun cancel() {
        handler.removeCallbacks(endBurst)
        burstNode = null
        burstContainer = null
    }

    private fun finishBurst() {
        handler.removeCallbacks(endBurst)
        val container = burstContainer ?: return
        burstNode = null
        burstContainer = null

        val page = container.pageHeight
        val net = container.burstDeltaY
        val pages = (net.toFloat() / page).roundToInt()
        val offBoundary = abs(net - pages * page) > page * SNAP_TOLERANCE
        // RecyclerView/ViewPager report first/last visible adapter position. Two different
        // values at rest means several items share the screen, which is a feed, even if the
        // distance happened to land on a page boundary.
        val severalVisible = container.lastFromIndex >= 0 &&
            container.lastToIndex >= 0 &&
            container.lastFromIndex != container.lastToIndex
        if (offBoundary || severalVisible || abs(pages) > MAX_PAGES_PER_BURST) {
            container.resetSession()
            onFeedScrolled(container.packageName)
            return
        }
        // Snapped back to where it started: an aborted swipe. No evidence either way.
        if (pages == 0) return

        container.position += pages
        val gained = (container.position - container.furthest).coerceAtLeast(0)
        container.furthest = maxOf(container.furthest, container.position)

        if (container.confirmed) {
            if (gained > 0) onReelsWatched(container.packageName, gained)
        } else if (++container.snappedStreak >= CONFIRM_STREAK) {
            container.confirmed = true
            // +1 for the reel the session started on, which was watched before the first swipe.
            onReelsWatched(container.packageName, container.furthest + 1)
        }
    }

    private companion object {
        /**
         * Quiet time that ends a burst. Scroll events arrive at most every 100ms while moving,
         * and a pager's settle animation runs ~250-300ms. Shorter risks splitting one swipe
         * into two un-snapped halves. Longer merges quick consecutive swipes into one burst,
         * which is harmless since a multi-page burst still snaps (up to MAX_PAGES_PER_BURST).
         */
        const val SETTLE_MS = 400L

        /**
         * How far off a whole page a burst may end and still count as snapped. Views land
         * exactly on the boundary, and Compose pagers land within a pixel or so of rounding per
         * event. Each extra percent here raises the chance a feed fling passes as a swipe by
         * about two percent.
         */
        const val SNAP_TOLERANCE = 0.02f

        /**
         * A fling through a feed can land on a page multiple by chance, and long flings cover
         * many pages. A pager moves one page per swipe, or a few when swipes chain inside
         * SETTLE_MS, so anything longer is treated as a feed.
         */
        const val MAX_PAGES_PER_BURST = 3

        /** Consecutive snapped bursts before a container is trusted to be a reel viewer. */
        const val CONFIRM_STREAK = 2

        /**
         * Reel viewers fill the screen, but some sit between a top bar and a bottom nav, so
         * height gets real slack. Width barely varies, and requiring near-full width keeps out
         * split-screen and side-panel scrollers.
         */
        const val MIN_WIDTH_FRACTION = 0.9f
        const val MIN_HEIGHT_FRACTION = 0.55f

        const val MAX_TRACKED_CONTAINERS = 8
    }
}
