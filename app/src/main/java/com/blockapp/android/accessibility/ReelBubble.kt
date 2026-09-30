package com.blockapp.android.accessibility

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.blockapp.android.data.BlockRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The floating reel counter: a small pill at the top of the screen, just right of the front
 * camera, showing today's reel total across all apps while a reel viewer is on screen. It hops
 * each time the total goes up, so the number is noticed at the moment it grows rather than
 * only later on HomeScreen.
 *
 * Shown by AppBlockAccessibilityService whenever ReelScrollDetector counts a reel, and hidden
 * when the detector sees that app scroll like a feed or a different app comes to the front.
 * Like the detector, it only observes and never acts on the foreground app.
 *
 * Drawn as a TYPE_ACCESSIBILITY_OVERLAY window, which an accessibility service may add without
 * the SYSTEM_ALERT_WINDOW permission. That only works through the service's own
 * WindowManager, so this must be constructed with the AccessibilityService itself. Any other
 * context's WindowManager rejects that window type. The window is neither touchable nor
 * focusable. It sits over the host app's top bar and must never eat a tap meant for that app.
 *
 * Owned by the service and only touched on the main thread. Every WindowManager call is
 * guarded: this is decoration, and an exception out of it must not reach the service that
 * enforces locks (CLAUDE.md invariant 9).
 *
 * The service only creates one from API 28, alongside the detector, but nothing here depends
 * on that except the cutout placement, which is guarded where it's used.
 */
internal class ReelBubble(
    private val service: AccessibilityService,
    private val repository: BlockRepository,
    private val scope: CoroutineScope,
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var label: TextView? = null
    private var countJob: Job? = null
    private var countDay: LocalDate? = null
    private var shownTotal = -1

    /** The app the bubble is currently shown over, or null while hidden. */
    var shownFor: String? = null
        private set

    fun show(packageName: String) {
        shownFor = packageName
        if (root == null && !attach()) {
            shownFor = null
            return
        }
        // Re-keyed on the date so a session running past midnight drops to today's total on
        // the next reel instead of adding to yesterday's.
        val today = LocalDate.now()
        if (countJob == null || today != countDay) {
            countJob?.cancel()
            countDay = today
            countJob = scope.launch {
                repository.observeReelTotal(today).collect { total -> render(total) }
            }
        }
    }

    fun hide() {
        countJob?.cancel()
        countJob = null
        countDay = null
        shownTotal = -1
        shownFor = null
        val view = root ?: return
        root = null
        label = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (e: IllegalArgumentException) {
            // Already detached, e.g. the system tore the window down with the service.
        }
    }

    private fun attach(): Boolean {
        val heightPx = dp(BUBBLE_HEIGHT_DP)
        val marginPx = dp(BOUNCE_ROOM_DP)
        val text = TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = heightPx
            includeFontPadding = false
            setPadding(dp(PADDING_H_DP), 0, dp(PADDING_H_DP), 0)
            background = GradientDrawable().apply {
                cornerRadius = heightPx / 2f
                setColor(BUBBLE_COLOR)
            }
        }
        // The pill hops by scaling past its own size. A window clips to its bounds, so the
        // wrapper leaves margin all round; without it the hop is cut off square at the edges.
        val wrapper = FrameLayout(service).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(
                text,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, heightPx).apply {
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                },
            )
            alpha = 0f
        }
        val anchor = anchor(heightPx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Display cutouts were introduced in API 28 (P). Without this the window is pushed
            // below the cutout and lands under the status bar instead of beside the camera.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            x = anchor.x - marginPx
            y = anchor.y - marginPx
        }
        try {
            windowManager.addView(wrapper, params)
        } catch (e: RuntimeException) {
            // BadTokenException or IllegalStateException if the service is mid-unbind. The
            // counter itself still works, and the next counted reel tries again.
            return false
        }
        wrapper.animate().alpha(1f).setDuration(FADE_MS).start()
        root = wrapper
        label = text
        return true
    }

    // A glyph and a bare number, so there's nothing to translate or reorder.
    @SuppressLint("SetTextI18n")
    private fun render(total: Int) {
        val text = label ?: return
        text.text = "$PLAY_GLYPH $total"
        // Only hop on an increase seen while showing, not on the first value after appearing.
        // Otherwise every reappearance would hop even though nothing new was counted.
        if (shownTotal in 0 until total) {
            text.animate().cancel()
            text.animate()
                .scaleX(HOP_SCALE).scaleY(HOP_SCALE)
                .setDuration(HOP_MS)
                .withEndAction {
                    text.animate().scaleX(1f).scaleY(1f).setDuration(HOP_MS).start()
                }
                .start()
        }
        shownTotal = total
    }

    /**
     * Top-left corner for the pill, in screen pixels: vertically centred on the front camera
     * cutout and just to its right. Falls back to just right of top-centre, where most
     * punch-hole cameras sit, when there is no top cutout, or below API 29, where Display has
     * no getCutout().
     */
    private fun anchor(heightPx: Int): Point {
        val gap = dp(CAMERA_GAP_DP)
        val screen = service.resources.displayMetrics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // DisplayManager rather than service.display: an accessibility service is not a
            // visual context, and getDisplay() on one throws from API 30.
            val display = service.getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val camera = display?.cutout?.boundingRects
                ?.filter { it.top <= 0 && it.bottom < screen.heightPixels / 4 }
                ?.minByOrNull { it.left }
            if (camera != null) {
                return Point(
                    camera.right + gap,
                    (camera.centerY() - heightPx / 2).coerceAtLeast(0),
                )
            }
        }
        return Point(screen.widthPixels / 2 + dp(FALLBACK_OFFSET_DP), dp(FALLBACK_TOP_DP))
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        /**
         * Small enough to fit beside the camera inside a typical 24-32dp status bar, big enough
         * for three digits at [TEXT_SIZE_SP].
         */
        const val BUBBLE_HEIGHT_DP = 20
        const val TEXT_SIZE_SP = 10f
        const val PADDING_H_DP = 7

        /** Space between the camera cutout's right edge and the pill. */
        const val CAMERA_GAP_DP = 6

        /** Used when the camera position is unknown. See [anchor]. */
        const val FALLBACK_OFFSET_DP = 16
        const val FALLBACK_TOP_DP = 4

        /**
         * How far the pill grows on each new reel. More reads as a proper hop, but the window
         * must reserve [BOUNCE_ROOM_DP] on every side for it, and that room is dead space
         * next to the status bar icons.
         */
        const val HOP_SCALE = 1.3f
        const val HOP_MS = 120L
        const val BOUNCE_ROOM_DP = 8

        const val FADE_MS = 180L

        /**
         * U+25B6 plus the text-presentation selector. Bare U+25B6 renders as a colour emoji
         * on most devices, which is too big and too loud at this size.
         */
        const val PLAY_GLYPH = "\u25B6\uFE0E"

        /** Near-black at ~80% opacity, so it reads over both a bright video and a dark one. */
        const val BUBBLE_COLOR = 0xCC111111.toInt()
    }
}
