package com.blockapp.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.ui.theme.Bone
import com.blockapp.android.ui.theme.Ink700
import com.blockapp.android.ui.theme.Ink950
import com.blockapp.android.ui.theme.Ink800

// The app's illustrations, drawn rather than shipped as images: they stay sharp at any density,
// cost nothing in APK size, and pick up the palette from Color.kt. All of them are static. See
// Motion.kt for why nothing here may loop.

/**
 * A fanned stack of reel cards with a play mark: "the endless scroll", for the start of the quiz.
 * Flat on purpose. The glow, moon and stars it used to have were the generated-art look the
 * redesign moved away from.
 */
@Composable
internal fun ReelStackArt(modifier: Modifier = Modifier, height: Dp = 220.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val cx = size.width / 2f
        val cardH = size.height * 0.78f
        val cardW = cardH * 0.56f
        val top = size.height * 0.11f


        // Back cards, fanned out.
        listOf(-14f to Ink700, 14f to Ink700, -6f to Ink800).forEach { (angle, color) ->
            rotate(angle, pivot = Offset(cx, top + cardH)) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(cx - cardW / 2f, top),
                    size = Size(cardW, cardH),
                    cornerRadius = CornerRadius(cardW * 0.14f),
                )
            }
        }

        // Front card.
        val front = Offset(cx - cardW / 2f, top)
        drawRoundRect(
            color = Ember,
            topLeft = front,
            size = Size(cardW, cardH),
            cornerRadius = CornerRadius(cardW * 0.14f),
        )
        // Play mark.
        val r = cardW * 0.17f
        val center = Offset(cx, top + cardH * 0.45f)
        drawCircle(Ink950.copy(alpha = 0.18f), radius = r * 1.35f, center = center)
        val play = Path().apply {
            moveTo(center.x - r * 0.45f, center.y - r * 0.62f)
            lineTo(center.x + r * 0.7f, center.y)
            lineTo(center.x - r * 0.45f, center.y + r * 0.62f)
            close()
        }
        drawPath(play, Ink950)
        // Caption lines and a side rail of dots, so it reads as a reel and not a playing card.
        val lineX = front.x + cardW * 0.12f
        listOf(0.74f to 0.55f, 0.82f to 0.38f).forEach { (y, w) ->
            drawLine(
                Ink950.copy(alpha = 0.45f),
                Offset(lineX, top + cardH * y),
                Offset(lineX + cardW * w, top + cardH * y),
                strokeWidth = cardW * 0.035f,
                cap = StrokeCap.Round,
            )
        }
        repeat(3) { i ->
            drawCircle(
                Ink950.copy(alpha = 0.5f),
                radius = cardW * 0.035f,
                center = Offset(front.x + cardW * 0.87f, top + cardH * (0.56f + 0.08f * i)),
            )
        }

    }
}

/**
 * A staircase going down to a flag: "your plan steps down to a goal", for the plan reveal and the
 * plan screen. [steps] is how many treads to draw.
 */
@Composable
internal fun StepsArt(modifier: Modifier = Modifier, steps: Int = 5, height: Dp = 150.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val count = steps.coerceIn(2, 8)
        val gap = 6.dp.toPx()
        val usable = size.width * 0.78f
        val left = (size.width - usable) / 2f
        val w = (usable - gap * (count - 1)) / count
        for (i in 0 until count) {
            val share = 1f - i / count.toFloat()
            val h = size.height * (0.18f + 0.72f * share)
            val x = left + i * (w + gap)
            // Every tread the same quiet grey and only the goal in Lime: the colour marks where
            // this ends, not a gradient across the whole staircase.
            drawRoundRect(
                color = if (i == count - 1) Lime else Ink700,
                topLeft = Offset(x, size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w * 0.22f),
            )
        }
        // Flag on the last tread.
        val lastX = left + (count - 1) * (w + gap) + w / 2f
        val lastTop = size.height - size.height * (0.18f + 0.72f / count)
        val poleTop = lastTop - size.height * 0.38f
        drawLine(Bone, Offset(lastX, lastTop), Offset(lastX, poleTop), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        val flag = Path().apply {
            moveTo(lastX, poleTop)
            lineTo(lastX + size.height * 0.2f, poleTop + size.height * 0.07f)
            lineTo(lastX, poleTop + size.height * 0.14f)
            close()
        }
        drawPath(flag, Lime)
    }
}

/** A small calendar page with a heat-map of cells, for History's empty state. */
@Composable
internal fun CalendarArt(modifier: Modifier = Modifier, height: Dp = 120.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val pageH = size.height * 0.9f
        val pageW = pageH * 1.15f
        val left = (size.width - pageW) / 2f
        val top = (size.height - pageH) / 2f
        drawRoundRect(Ink800, Offset(left, top), Size(pageW, pageH), CornerRadius(pageW * 0.1f))
        drawRoundRect(
            Ink700,
            Offset(left, top),
            Size(pageW, pageH * 0.2f),
            CornerRadius(pageW * 0.1f),
        )
        val cols = 7
        val rows = 4
        val pad = pageW * 0.07f
        val cell = (pageW - pad * 2) / cols
        // A fixed pattern rather than random, so the art is the same on every open.
        val heat = listOf(0.2f, 0.5f, 0.9f, 0.3f, 0f, 0.6f, 0.4f, 0.7f, 0.1f, 0.3f, 0.8f, 0.5f, 0.2f, 0f)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val h = heat[(r * cols + c) % heat.size]
                drawRoundRect(
                    color = if (h == 0f) Ink700 else Ember.copy(alpha = 0.25f + 0.75f * h),
                    topLeft = Offset(left + pad + c * cell + cell * 0.12f, top + pageH * 0.28f + r * cell * 0.86f),
                    size = Size(cell * 0.76f, cell * 0.66f),
                    cornerRadius = CornerRadius(cell * 0.18f),
                )
            }
        }
    }
}
