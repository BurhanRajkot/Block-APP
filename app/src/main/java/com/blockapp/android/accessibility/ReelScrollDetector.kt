package com.blockapp.android.accessibility

import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

/**
 * One swipe's worth of movement in one full-screen vertical scroller: the run of scroll events
 * from a single container, ended by [ReelScrollDetector.SETTLE_MS] of quiet.
 */
internal class ScrollBurst(
    val packageName: String,
    /** The scrolling view. Its equals() is (window, view, virtual view), so it keys per-container state. */
    val node: AccessibilityNodeInfo,
    /**
     * Summed scrollDeltaY. It is the exact distance moved, because the framework accumulates
     * deltas between the events it throttles (View.SendViewScrolledAccessibilityEvent).
     */
    val netDeltaY: Int,
    /** The container's on-screen height when the burst started. */
    val containerHeight: Int,
    /** First/last visible adapter position at rest, or -1 when the container doesn't report them. */
    val fromIndex: Int,
    val toIndex: Int,
    /** The container declares vertical pager semantics (page up/down actions). */
    val hasPageActions: Boolean,
)

/**
 * Turns the TYPE_VIEW_SCROLLED stream into [ScrollBurst]s for [ReelTracker], which decides what
 * a burst means. This class only measures: which scroller moved, how far, and where it came to
 * rest.
 *
 * Only full-screen scrollers are considered. Small ones (a comment sheet, a caption, a carousel
 * inside a post) return before touching the open burst; otherwise a nested scroll mid-swipe
 * would cut the pager's burst short and it would read as a feed fling. Horizontal movement is
 * ignored: horizontal pagers are tab bars, photo galleries and story viewers, not reels.
 *
 * Owned by ReelTracker and only touched on the main thread. Needs API 28 for
 * [AccessibilityEvent.getScrollDeltaY]; below that the service never creates one.
 */
internal class ReelScrollDetector(
    private val handler: Handler,
    private val ignoredPackages: Set<String>,
    private val onBurst: (ScrollBurst) -> Unit,
) {
    private var node: AccessibilityNodeInfo? = null
    private var packageName = ""
    private var containerHeight = 0
    private var netDeltaY = 0
    private var fromIndex = -1
    private var toIndex = -1
    private var hasPageActions = false
    private val bounds = Rect()
    private val endBurst = Runnable { finishBurst() }

    @RequiresApi(Build.VERSION_CODES.P)
    fun onScrolled(event: AccessibilityEvent, screenWidth: Int, screenHeight: Int) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignoredPackages) return
        val deltaY = event.scrollDeltaY
        // Zero-delta events carry no distance. Returning before getSource() also skips a binder
        // call per horizontal scroll event.
        if (deltaY == 0) return

        val source = event.source ?: return
        if (source != node) {
            source.getBoundsInScreen(bounds)
            if (bounds.width() < screenWidth * MIN_WIDTH_FRACTION ||
                bounds.height() < screenHeight * MIN_HEIGHT_FRACTION
            ) {
                return
            }
            finishBurst()
            node = source
            packageName = pkg
            containerHeight = bounds.height()
            netDeltaY = 0
            hasPageActions = hasVerticalPageActions(source)
        }

        netDeltaY += deltaY
        fromIndex = event.fromIndex
        toIndex = event.toIndex
        handler.removeCallbacks(endBurst)
        handler.postDelayed(endBurst, SETTLE_MS)
    }

    /** Drops any open burst without reporting it, for screen-off and service unbind. */
    fun cancel() {
        handler.removeCallbacks(endBurst)
        node = null
    }

    private fun finishBurst() {
        handler.removeCallbacks(endBurst)
        val source = node ?: return
        node = null
        if (containerHeight <= 0) return
        onBurst(
            ScrollBurst(
                packageName = packageName,
                node = source,
                netDeltaY = netDeltaY,
                containerHeight = containerHeight,
                fromIndex = fromIndex,
                toIndex = toIndex,
                hasPageActions = hasPageActions,
            ),
        )
    }

    companion object {
        /**
         * Quiet time that ends a burst. Scroll events arrive at most every 100ms while moving,
         * and a pager's settle animation runs ~250-300ms. Shorter risks splitting one swipe
         * into two un-snapped halves. Longer merges quick consecutive swipes into one burst,
         * which is harmless since a multi-page burst still snaps.
         */
        const val SETTLE_MS = 400L

        /**
         * Reel viewers fill the screen, but some sit between a top bar and a bottom nav, so
         * height gets slack. It must stay well above a comment sheet's height (about 60% in
         * YouTube Shorts), since a scroll in a container this size that isn't a page swipe
         * ends the reel session. Width barely varies, and requiring near-full width keeps out
         * split-screen and side-panel scrollers.
         */
        const val MIN_WIDTH_FRACTION = 0.9f
        const val MIN_HEIGHT_FRACTION = 0.7f
    }
}

/**
 * ViewPager2 and Compose's VerticalPager both expose ACTION_PAGE_UP/DOWN (API 29). Feeds
 * (RecyclerView, LazyColumn) only expose the plain scroll actions, so this is the one standard
 * signal that separates a pager from a list before it has moved.
 */
internal fun hasVerticalPageActions(node: AccessibilityNodeInfo): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    val down = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id
    val up = AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id
    return node.actionList.orEmpty().any { it.id == down || it.id == up }
}
