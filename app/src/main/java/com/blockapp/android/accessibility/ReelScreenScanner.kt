package com.blockapp.android.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import kotlin.math.abs

/**
 * Looks at what is on screen right now and answers "is this a short-form video viewer?",
 * without waiting for the user to swipe. This is what lets the reel bubble appear on the very
 * first reel, and what counts reels in apps whose pager never sends scroll events.
 *
 * A reel viewer is recognised by shape, not by app: a scrollable container that fills the
 * screen and shows exactly one child, which fills the container edge to edge. A feed is also a
 * full-screen scrollable, but at rest it shows several items.
 *
 * Shape alone can't tell a reel viewer from a feed paused on one very tall post, because
 * on-screen bounds are clipped and a clipped post looks exactly like a page. So a match is only
 * [Match.confident] with one more signal:
 *  - the container has vertical pager semantics (page up/down actions, see
 *    [hasVerticalPageActions]), which ViewPager2 and Compose's VerticalPager both expose, or
 *  - it is known to scroll vertically and a view ID on the way down names short-form video
 *    ([VERTICAL_HINTS]), or
 *  - its orientation is unknown but a view ID is unambiguous about reels ([STRONG_HINTS]).
 *
 * A match without one of those is still returned, and ReelTracker treats it as a candidate that
 * a single page-sized swipe confirms.
 *
 * Compose pagers need the exception in [Walk.evaluate]: their pages usually have no semantics, so
 * there is no page node at all, only the page's content directly under the pager (seen on an
 * emulator with a plain VerticalPager). Their declared page actions carry the match instead.
 *
 * Video surfaces can't be used as a signal: SurfaceView and TextureView don't override
 * getAccessibilityClassName(), so they report as plain android.view.View like every other view.
 *
 * Needs the service's flagIncludeNotImportantViews (pager internals are often marked
 * unimportant) and flagReportViewIds (for the hints). Runs on ReelTracker's background thread,
 * because every node fetch can block on the target app's UI thread, and that must never delay
 * lock enforcement on the service's main thread.
 */
internal object ReelScreenScanner {

    class Match(
        val packageName: String,
        /** The scrolling container. Equal to a ScrollBurst.node for the same view. */
        val pager: AccessibilityNodeInfo,
        /** The single child on screen. A different node means a different reel. */
        val page: AccessibilityNodeInfo,
        /**
         * The page's visible text (caption, username, …), for recognising a reel seen a moment
         * ago. Node identity can't do that: Compose disposes a page that scrolls away and
         * builds a new node when it comes back. Empty when the page shows no text.
         */
        val pageSignature: String,
        val confident: Boolean,
    )

    fun scan(root: AccessibilityNodeInfo, screenWidth: Int, screenHeight: Int): Match? {
        val packageName = root.packageName?.toString() ?: return null
        val walk = Walk(packageName, screenWidth, screenHeight)
        walk.visit(root, depth = 0)
        return walk.best
    }

    private class Walk(
        val packageName: String,
        val screenWidth: Int,
        val screenHeight: Int,
    ) {
        var best: Match? = null
        private var budget = MAX_NODES
        private val path = ArrayList<AccessibilityNodeInfo>()
        private val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (budget-- <= 0 || depth > MAX_DEPTH || !node.isVisibleToUser) return
            node.getBoundsInScreen(rect)
            // Children are clipped to their parent's bounds, so nothing under a narrow node can
            // be full-width. That prunes almost the whole tree and keeps a scan to a few dozen
            // node fetches.
            if (rect.width() < screenWidth * MIN_WIDTH_FRACTION) return

            if (node.isScrollable && rect.height() >= screenHeight * MIN_HEIGHT_FRACTION) {
                evaluate(node, Rect(rect))?.let { match ->
                    // Confident beats unsure. Among equals the later one wins, and a depth-first
                    // walk reaches a nested pager after its wrapper, so the innermost scroller is
                    // kept. Its child is the page that actually changes per reel.
                    val current = best
                    if (current == null || match.confident || !current.confident) best = match
                }
            }

            path.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
            }
            path.removeAt(path.lastIndex)
        }

        private fun evaluate(pager: AccessibilityNodeInfo, pagerRect: Rect): Match? {
            if (isHorizontal(pager)) return null
            val pageActions = hasVerticalPageActions(pager) || path.any(::hasVerticalPageActions)
            val tolerance = pagerRect.height() * EDGE_TOLERANCE
            var page: AccessibilityNodeInfo? = null
            var several = false
            var largest: AccessibilityNodeInfo? = null
            var largestArea = 0
            val childRect = Rect()
            for (i in 0 until pager.childCount) {
                val child = pager.getChild(i) ?: continue
                if (!child.isVisibleToUser) continue
                child.getBoundsInScreen(childRect)
                val area = childRect.width() * childRect.height()
                if (area > largestArea) {
                    largestArea = area
                    largest = child
                }
                // A sliver of the neighbouring page mid-settle, or a pull-to-refresh header.
                if (childRect.height() < pagerRect.height() * SLIVER_FRACTION) continue
                val fills = abs(childRect.top - pagerRect.top) <= tolerance &&
                    abs(childRect.bottom - pagerRect.bottom) <= tolerance &&
                    childRect.width() >= pagerRect.width() * MIN_WIDTH_FRACTION
                // A second sizeable child means several items share the screen: a feed.
                if (!fills || page != null) several = true else page = child
            }

            val pageContent: List<AccessibilityNodeInfo>
            val resolvedPage = if (!several && page != null) {
                pageContent = listOf(page)
                page
            } else {
                // Compose drops nodes that carry no semantics, and a pager page usually carries
                // none, so the page's content (a caption, the video view) shows up as the
                // pager's direct children instead. The shape test can't work there. Declared
                // pager semantics are trusted on their own, and the largest child stands in for
                // the page's identity, since it is replaced along with the page.
                if (!pageActions) return null
                pageContent = (0 until pager.childCount)
                    .mapNotNull { pager.getChild(it) }
                    .filter { it.isVisibleToUser }
                largest ?: pager
            }
            val vertical = pageActions || isVerticalCollection(pager)
            val ids = (path + pager + resolvedPage).mapNotNull(::hintText)
            val confident = pageActions ||
                (vertical && ids.any { id -> VERTICAL_HINTS.any { it in id } }) ||
                ids.any { id -> STRONG_HINTS.any { it in id } }
            return Match(packageName, pager, resolvedPage, signature(pageContent), confident)
        }
    }

    private fun signature(roots: List<AccessibilityNodeInfo>): String {
        val text = StringBuilder()
        val queue = ArrayDeque(roots)
        var budget = SIGNATURE_NODES
        while (queue.isNotEmpty() && budget-- > 0 && text.length < SIGNATURE_CHARS) {
            val node = queue.removeFirst()
            node.text?.let { text.append(it).append('|') }
            node.contentDescription?.let { text.append(it).append('|') }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        // A playing video's elapsed/remaining time changes between scans of the same reel.
        return text.replace(CLOCK_TIME, "").take(SIGNATURE_CHARS)
    }

    /** View ID entry name plus class name, lower-cased, for hint matching. */
    private fun hintText(node: AccessibilityNodeInfo): String? {
        val id = node.viewIdResourceName?.substringAfter('/')
        val cls = node.className?.toString()?.substringAfterLast('.')
        if (id == null && cls == null) return null
        return "${id.orEmpty()} ${cls.orEmpty()}".lowercase()
    }

    private fun isVerticalCollection(node: AccessibilityNodeInfo): Boolean {
        val info = node.collectionInfo ?: return false
        // A vertical list reports one column. Compose reports rowCount -1 (unknown) for a lazy
        // column, so only rowCount == 1 (a horizontal strip) is ruled out.
        return info.columnCount == 1 && info.rowCount != 1
    }

    private fun isHorizontal(node: AccessibilityNodeInfo): Boolean {
        node.collectionInfo?.let { info ->
            if (info.rowCount == 1 && info.columnCount > 1) return true
        }
        val ids = node.actionList.orEmpty().map { it.id }.toSet()
        val horizontal = mutableListOf(
            AccessibilityAction.ACTION_SCROLL_LEFT.id,
            AccessibilityAction.ACTION_SCROLL_RIGHT.id,
        )
        val vertical = mutableListOf(
            AccessibilityAction.ACTION_SCROLL_UP.id,
            AccessibilityAction.ACTION_SCROLL_DOWN.id,
        )
        // Page actions were added in API 29 (Q).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            horizontal += AccessibilityAction.ACTION_PAGE_LEFT.id
            horizontal += AccessibilityAction.ACTION_PAGE_RIGHT.id
            vertical += AccessibilityAction.ACTION_PAGE_UP.id
            vertical += AccessibilityAction.ACTION_PAGE_DOWN.id
        }
        return horizontal.any { it in ids } && vertical.none { it in ids }
    }

    /**
     * Reel-specific words in view IDs or class names. Matched on any node from the window root
     * down to the page, since apps often put the name on a wrapper rather than the pager
     * itself: YouTube's `reel_recycler`, Instagram's `clips_viewer_view_pager`.
     *
     * "reel" is deliberately not strong on its own. Instagram uses it internally for Stories
     * (`reel_viewer`), a horizontal pager whose orientation may not be reported. Vertical
     * hints only count once the container is known to scroll vertically.
     */
    private val STRONG_HINTS = listOf("clips", "shorts", "spotlight", "short_video", "shortvideo")
    private val VERTICAL_HINTS = STRONG_HINTS + listOf("reel", "video", "player")

    /** Same width rule as ReelScrollDetector, so a pager found here is one it would also track. */
    private const val MIN_WIDTH_FRACTION = ReelScrollDetector.MIN_WIDTH_FRACTION
    private const val MIN_HEIGHT_FRACTION = ReelScrollDetector.MIN_HEIGHT_FRACTION

    /**
     * How far a page's top or bottom edge may sit from the container's and still "fill" it.
     * Pages land on the pixel, but some apps inset the page by a hairline divider.
     */
    private const val EDGE_TOLERANCE = 0.03f

    /** Bounds on the text walk behind [Match.pageSignature]. A caption and a username is plenty. */
    private const val SIGNATURE_NODES = 40
    private const val SIGNATURE_CHARS = 300
    private val CLOCK_TIME = Regex("""\d{1,2}:\d{2}""")

    /** Visible children shorter than this share of the container are ignored. */
    private const val SLIVER_FRACTION = 0.1f

    /**
     * Upper bounds on one scan. Pruning to full-width nodes normally keeps it far below
     * these. They only matter for a pathological tree, where a partial scan is better than a
     * slow one.
     */
    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 40
}
