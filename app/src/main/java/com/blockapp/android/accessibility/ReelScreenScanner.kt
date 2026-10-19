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
 * Within the apps ReelTracker watches (ReelApps.KNOWN; this scanner is never run on any other app,
 * because the shape below also fits browser image viewers and galleries), a reel viewer is
 * recognised by shape: a scrollable container that fills the
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
 * **The action rail.** When no pager can be recognised, which is the case for YouTube Shorts,
 * TikTok and most apps other than Instagram (custom views, pagers that don't report
 * themselves as scrollable), [RailScan] looks for what every short-video viewer draws on top of
 * the video instead: a column of small buttons stacked down the right edge (like, comment,
 * share, …), at least two of them labelled with a reaction word or a count like "12K". Feeds put
 * those actions in a row under each post, never in a right-hand column, so the rail alone is a
 * confident match. Its pages have no node identity, so a new reel is told apart by its visible
 * text ([Match.pageTokens]): the caption and username change from one reel to the next.
 *
 * Video surfaces can't be used as a signal: SurfaceView and TextureView don't override
 * getAccessibilityClassName(), so they report as plain android.view.View like every other view.
 *
 * **Video evidence.** With `requireVideo` (ReelApps.NEEDS_VIDEO_EVIDENCE: Reddit, LinkedIn, X,
 * Pinterest, Tumblr), a match only counts if [VideoCheck] finds a player around it: an
 * ExoPlayer/media3 view ID (`exo_content_frame`, `exo_shutter`, …), a player or video view class,
 * a seek bar, or a mute/pause/play control. Those apps show photos in the same full-screen,
 * swipe-through viewers as their videos, and without the check swiping through pictures counted
 * as reels. Visible caption text is deliberately not used: a photo's caption can say "video".
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
        /**
         * The single child on screen; a different node means a different reel. Null for an
         * action-rail match, which has no page node and relies on [pageTokens] alone.
         */
        val page: AccessibilityNodeInfo?,
        /**
         * Words of the page's visible text (caption, username, …), digits stripped, for telling
         * reels apart. Node identity can't always do that: Compose disposes a page that scrolls
         * away and builds a new node when it comes back, and a rail match has no page node.
         * Empty when the page shows no text.
         */
        val pageTokens: Set<String>,
        /**
         * For an action-rail match, the rail's own labels as shown ("12k", "like", "1,204"),
         * digits kept. A new reel brings new counts; the same reel keeps them while its caption
         * zone changes under auto-captions or an expanded "…more". Null for a pager match.
         */
        val railLabels: Set<String>?,
        val confident: Boolean,
        /** Which signal matched, for the debug log: "pager" or "rail". */
        val kind: String,
    )

    /** [note] explains a near miss, for the debug log; null when there is nothing to say. */
    class Result(val match: Match?, val note: String?)

    fun scan(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        requireVideo: Boolean = false,
    ): Result {
        val packageName = root.packageName?.toString() ?: return Result(null, null)
        val walk = Walk(packageName, screenWidth, screenHeight)
        walk.visit(root, depth = 0)
        walk.best?.let { best ->
            if (best.confident) {
                return if (!requireVideo || VideoCheck.find(root)) Result(best, null) else noVideo(best)
            }
        }

        // The full walk is only paid for when the cheap pager walk came up short. It also looks
        // for video evidence on the way, since it visits every node anyway.
        val rail = RailScan(screenWidth, screenHeight)
        rail.visit(root, depth = 0)
        if (rail.isRail()) {
            val match = Match(
                packageName,
                root,
                null,
                rail.tokens,
                rail.columnLabels(),
                confident = true,
                kind = "rail",
            )
            return if (!requireVideo || rail.sawVideo) Result(match, null) else noVideo(match)
        }
        val unsure = walk.best
        if (unsure != null && requireVideo && !rail.sawVideo && !VideoCheck.find(root)) return noVideo(unsure)
        return Result(unsure, walk.rejection ?: rail.describe())
    }

    private fun noVideo(match: Match) = Result(null, "${match.kind} shape but no video player on screen")

    private class Walk(
        val packageName: String,
        val screenWidth: Int,
        val screenHeight: Int,
    ) {
        var best: Match? = null
        /** Why the last full-screen scrollable didn't qualify, for the debug log. */
        var rejection: String? = null
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
            val name = hintText(pager)?.trim().orEmpty()
            if (isHorizontal(pager)) {
                rejection = "scroller [$name] scrolls horizontally"
                return null
            }
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
                if (!pageActions) {
                    rejection = "scroller [$name] shows ${if (page == null) "no full-size page" else "several items"}"
                    return null
                }
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
            val words = tokens(pageContent, screenWidth, screenHeight)
            return Match(packageName, pager, resolvedPage, words, null, confident, kind = "pager")
        }
    }

    /**
     * One full walk of the window for the action rail (see the class doc). Unlike [Walk] it
     * can't prune narrow nodes, since the rail's buttons are narrow, so it is capped at
     * [RAIL_MAX_NODES] and only runs when [Walk] found nothing confident.
     */
    private class RailScan(val screenWidth: Int, val screenHeight: Int) {
        private class Item(
            val centerX: Int,
            val centerY: Int,
            val label: String?,
            /** Which reaction the label names (see [reactionKind]), or null for a count or none. */
            val kind: String?,
        ) {
            val labelled get() = kind != null || label?.let { COUNT.matches(it) } == true
        }

        private val items = ArrayList<Item>()
        val tokens = LinkedHashSet<String>()
        /** Whether any visited node looked like part of a video player; see VideoCheck. */
        var sawVideo = false
        private var hasBackdrop = false
        private var budget = RAIL_MAX_NODES
        private val rect = Rect()

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (budget-- <= 0 || depth > MAX_DEPTH || !node.isVisibleToUser) return
            if (!sawVideo && VideoCheck.isVideoNode(node)) sawVideo = true
            node.getBoundsInScreen(rect)
            if (rect.width() >= screenWidth * MIN_WIDTH_FRACTION &&
                rect.height() >= screenHeight * BACKDROP_HEIGHT_FRACTION
            ) {
                hasBackdrop = true
            }
            val label = (node.contentDescription ?: node.text)?.toString()
            if (label != null && inCaptionZone(rect, screenWidth, screenHeight)) addTokens(tokens, label)
            val inRail = rect.width() in 1..(screenWidth * RAIL_MAX_WIDTH).toInt() &&
                rect.height() in 1..(screenHeight * RAIL_MAX_HEIGHT).toInt() &&
                rect.centerX() >= screenWidth * RAIL_MIN_X &&
                rect.centerY() >= screenHeight * RAIL_MIN_Y &&
                rect.centerY() <= screenHeight * RAIL_MAX_Y
            if (inRail) {
                val trimmed = label?.trim()?.lowercase()
                val item = Item(rect.centerX(), rect.centerY(), trimmed, trimmed?.let(::reactionKind))
                if (item.labelled || node.isClickable) items += item
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }

        private var column: List<Item> = emptyList()

        fun isRail(): Boolean {
            if (!hasBackdrop || items.size < RAIL_MIN_BUTTONS) return false
            val medianX = items.map { it.centerX }.sorted()[items.size / 2]
            val column = items.filter { abs(it.centerX - medianX) <= screenWidth * RAIL_COLUMN_SLACK }
            this.column = column
            if (column.count { it.labelled } < RAIL_MIN_LABELLED) return false
            // A comments list has a "Like" heart down the right edge of every comment, which is
            // a right-hand column of labelled buttons too, and a regular post's comments screen
            // was counted as reels, one more for each scroll. A real rail is different buttons
            // (like, comment, share), never one button repeated.
            if (column.mapNotNull { it.kind }.distinct().size < RAIL_MIN_KINDS) return false
            val ys = column.map { it.centerY }
            // The rail is one compact cluster. A column spread down most of the screen is a list.
            if (ys.max() - ys.min() > screenHeight * RAIL_MAX_SPAN) return false
            // Distinct rows: an icon and its count label sit at nearly the same height and
            // count once, so this measures stacked buttons, not nodes.
            var rows = 0
            var lastY = Int.MIN_VALUE
            for (y in column.map { it.centerY }.sorted()) {
                if (y - lastY >= screenHeight * RAIL_ROW_GAP) rows++
                lastY = y
            }
            return rows >= RAIL_MIN_BUTTONS
        }

        /** The labels of the rail's buttons; see [Match.railLabels]. */
        fun columnLabels(): Set<String> = column.mapNotNull { it.label }.toSet()

        fun describe(): String? = when {
            items.isEmpty() -> null
            !hasBackdrop -> "right-edge buttons but no full-screen backdrop"
            else -> "right-edge buttons: ${items.size}, labelled ${items.count { it.labelled }}, " +
                "kinds ${items.mapNotNull { it.kind }.distinct()}"
        }
    }

    /**
     * Is a video player on screen? See "Video evidence" in the class doc. Only identifiers the
     * app itself sets for its player count (view IDs, class names, control labels), never the
     * text being shown.
     */
    private object VideoCheck {
        fun find(root: AccessibilityNodeInfo): Boolean {
            var budget = VIDEO_CHECK_MAX_NODES
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.addLast(root)
            while (queue.isNotEmpty() && budget-- > 0) {
                val node = queue.removeFirst()
                if (!node.isVisibleToUser) continue
                if (isVideoNode(node)) return true
                for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            }
            return false
        }

        fun isVideoNode(node: AccessibilityNodeInfo): Boolean {
            val id = node.viewIdResourceName?.substringAfter('/')?.lowercase()
            if (id != null && VIDEO_ID_HINTS.any { it in id }) return true
            val cls = node.className?.toString()?.substringAfterLast('.')?.lowercase()
            if (cls != null && VIDEO_CLASS_HINTS.any { it in cls }) return true
            val label = node.contentDescription?.toString() ?: return false
            return VIDEO_CONTROL_WORDS.containsMatchIn(label)
        }
    }

    private fun tokens(roots: List<AccessibilityNodeInfo>, screenWidth: Int, screenHeight: Int): Set<String> {
        val words = LinkedHashSet<String>()
        val queue = ArrayDeque(roots)
        val rect = Rect()
        var budget = SIGNATURE_NODES
        while (queue.isNotEmpty() && budget-- > 0 && words.size < MAX_TOKENS) {
            val node = queue.removeFirst()
            node.getBoundsInScreen(rect)
            if (inCaptionZone(rect, screenWidth, screenHeight)) {
                node.text?.let { addTokens(words, it.toString()) }
                node.contentDescription?.let { addTokens(words, it.toString()) }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return words
    }

    /**
     * Where a reel's own text sits: the username and caption, bottom-left, above any bottom
     * navigation bar. Only text there goes into [Match.pageTokens]. Everything else on a reel
     * screen is the same from one reel to the next ("Like", "Share", "Subscribe", YouTube's
     * Home/Shorts/You tabs), and counted in, it made two different reels look ~70% alike, so
     * they read as the same one and weren't counted.
     */
    private fun inCaptionZone(rect: Rect, screenWidth: Int, screenHeight: Int): Boolean =
        rect.centerX() < screenWidth * RAIL_MIN_X &&
            rect.centerY() >= screenHeight * CAPTION_MIN_Y &&
            rect.centerY() <= screenHeight * CAPTION_MAX_Y

    /**
     * Lower-cased words of [text], digits dropped: like counts and a playing video's elapsed
     * time change between scans of the same reel and must not make it look like a new one.
     */
    private fun addTokens(into: MutableSet<String>, text: String) {
        if (into.size >= MAX_TOKENS) return
        for (word in WORD.findAll(text.lowercase())) {
            if (word.value.length >= 2 && word.value !in CHROME_WORDS) into += word.value
            if (into.size >= MAX_TOKENS) return
        }
    }

    /** The reaction a rail label names, collapsed so "Like" and "Liked" are one kind. */
    private fun reactionKind(label: String): String? {
        val word = RAIL_WORDS.find(label)?.value?.lowercase() ?: return null
        return REACTION_KINDS[word] ?: word
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

    /** Vertical band of the caption zone; see inCaptionZone. */
    private const val CAPTION_MIN_Y = 0.45f
    private const val CAPTION_MAX_Y = 0.93f

    /** Bounds on the text behind [Match.pageTokens]. A caption and a username is plenty. */
    private const val SIGNATURE_NODES = 40
    private const val MAX_TOKENS = 80
    private val WORD = Regex("""\p{L}+""")

    // ── action rail (see RailScan) ──
    /** Reaction words in a rail button's label, as YouTube, TikTok, Instagram, Facebook and Snapchat word them. */
    private val RAIL_WORDS = Regex(
        """\b(like|likes|liked|dislike|comment|comments|share|send|save|saved|remix|repost|reply|favou?rite|bookmark|gift|sound|audio)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Synonyms in [RAIL_WORDS] that are the same button, for [RAIL_MIN_KINDS]. */
    private val REACTION_KINDS = mapOf(
        "likes" to "like", "liked" to "like",
        "comments" to "comment", "reply" to "comment",
        "send" to "share", "repost" to "share",
        "saved" to "save", "bookmark" to "save", "favorite" to "save", "favourite" to "save",
        "audio" to "sound",
    )

    /**
     * Words every reel's caption zone shows whoever posted it ("Follow", "Original audio",
     * "Sponsored"). Left in, two short-captioned reels by different people shared most of their
     * words and read as the same reel.
     */
    private val CHROME_WORDS = setOf(
        "follow", "following", "subscribe", "subscribed", "original", "audio", "sound", "sponsored",
        "ad", "more", "less", "see", "translation", "paid", "partnership", "with", "and", "the",
        "remix", "reels", "reel", "shorts", "suggested", "for", "you", "liked", "by", "others",
    )

    /** A bare count under a rail button: "12", "1,204", "1.2K", "3M", "4.5 lakh". */
    private val COUNT = Regex("""[\d.,]+\s*(k|m|b|lakh|cr)?""", RegexOption.IGNORE_CASE)

    /** A rail button's centre is at least this far across, and within this band vertically. */
    private const val RAIL_MIN_X = 0.75f
    private const val RAIL_MIN_Y = 0.25f
    private const val RAIL_MAX_Y = 0.97f

    /** Rail buttons are small. Anything bigger is content, not a button. */
    private const val RAIL_MAX_WIDTH = 0.25f
    private const val RAIL_MAX_HEIGHT = 0.15f

    /** How far from the column's median x a button may sit and still be in the column. */
    private const val RAIL_COLUMN_SLACK = 0.08f

    /** Vertical gap that makes two buttons separate rows rather than an icon and its label. */
    private const val RAIL_ROW_GAP = 0.03f

    /**
     * Three stacked buttons, two of them labelled as reactions or counts. Google Maps-style
     * floating buttons stack on the right too, but carry no reaction words or counts.
     */
    private const val RAIL_MIN_BUTTONS = 3
    private const val RAIL_MIN_LABELLED = 2

    /** Distinct reactions in the column; see the comments-list note in RailScan.isRail. */
    private const val RAIL_MIN_KINDS = 2

    /**
     * Furthest apart the top and bottom rail buttons may be, as a share of the screen. A real
     * rail (like to sound) spans about a third; a comments list's hearts run the whole sheet.
     */
    private const val RAIL_MAX_SPAN = 0.55f

    /** The video behind a rail fills most of the screen. */
    private const val BACKDROP_HEIGHT_FRACTION = 0.55f

    /** Cap on [RailScan]: a reel viewer's whole tree, which in YouTube runs to several hundred nodes. */
    private const val RAIL_MAX_NODES = 900

    // ── video evidence (see VideoCheck) ──
    /**
     * View ID fragments of video players. `exo_` covers ExoPlayer and media3's PlayerView, which
     * Reddit, LinkedIn and X build on, and its content frame is there even with controls hidden.
     */
    private val VIDEO_ID_HINTS = listOf(
        "exo_", "video", "player", "playback", "mute", "seek", "scrubber", "media_control",
    )

    /** Class name fragments: VideoView, PlayerView, StyledPlayerView, SeekBar. */
    private val VIDEO_CLASS_HINTS = listOf("videoview", "playerview", "seekbar")

    /** Labels of player controls, as screen readers announce them. */
    private val VIDEO_CONTROL_WORDS = Regex(
        """\b(mute|muted|unmute|sound (on|off)|turn (on|off) sound|volume|pause|paused|play|replay|captions?|subtitles?|video)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Cap on one VideoCheck walk, about the size of a reel viewer's tree. */
    private const val VIDEO_CHECK_MAX_NODES = 600

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
