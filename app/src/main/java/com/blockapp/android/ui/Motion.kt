package com.blockapp.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Motion shared by every screen. The rule throughout: movement explains a change (a number
// growing, a screen arriving, a press landing) and settles fast. Nothing loops except the two
// "alive" signals, the protection dot and the block wall's glow, and nothing here gates input.

/**
 * Fades and lifts [content] into place the first time it is shown. [index] staggers siblings,
 * so a screen's cards arrive one after another instead of all at once.
 *
 * Remembered as saveable so a LazyColumn item that scrolls away and back doesn't replay it. A
 * whole screen that is navigated away from and back to does replay, which is the intent.
 */
@Composable
internal fun Reveal(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var done by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (done) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (done) return@LaunchedEffect
        delay(index.coerceAtMost(MAX_STAGGERED) * STAGGER_MS)
        progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
        done = true
    }
    Box(
        modifier.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * REVEAL_LIFT_DP.dp.toPx()
            val scale = 0.97f + 0.03f * p
            scaleX = scale
            scaleY = scale
        },
    ) { content() }
}

/** Scale for a pressable: shrinks a little while pressed and springs back on release. */
@Composable
internal fun rememberPressScale(interactionSource: MutableInteractionSource, pressedScale: Float = 0.965f): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return scale
}

/**
 * True one frame after first composition. Feeding `if (appeared) value else 0` into an
 * animate*AsState makes a figure count up or a bar grow on arrival, then simply animate between
 * values after that.
 */
@Composable
internal fun rememberAppeared(): Boolean {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    return appeared
}

/** An integer that counts up on arrival and rolls to each new value after that. */
@Composable
internal fun AnimatedCount(
    value: Int,
    style: TextStyle,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val appeared = rememberAppeared()
    val shown by animateFloatAsState(
        if (appeared) value.toFloat() else 0f,
        tween(COUNT_MS, easing = FastOutSlowInEasing),
        label = "count",
    )
    // Pops a little each time the number goes up after arriving, the in-app twin of the reel
    // bubble's hop.
    val bump = remember { Animatable(1f) }
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value > last && appeared) {
            bump.snapTo(1.18f)
            bump.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
        }
        last = value
    }
    Text(
        "${shown.toInt()}",
        style = style,
        color = color,
        modifier = modifier.graphicsLayer {
            scaleX = bump.value
            scaleY = bump.value
            transformOrigin = TransformOrigin(0f, 1f)
        },
    )
}

/** A time-spent figure ("3h 42m") that counts up on arrival like [AnimatedCount]. */
@Composable
internal fun AnimatedDuration(
    millis: Long,
    style: TextStyle,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    val appeared = rememberAppeared()
    val shown by animateFloatAsState(
        if (appeared) millis.toFloat() else 0f,
        tween(COUNT_MS, easing = FastOutSlowInEasing),
        label = "duration",
    )
    Text(formatHoursMinutes(shown.toLong()), style = style, color = color, modifier = modifier)
}

/**
 * Text whose characters roll vertically when they change, like a mechanical counter. Meant
 * for countdowns: with tabular figures every character keeps its width, so only the changed
 * digits move.
 */
@Composable
internal fun RollingText(text: String, style: TextStyle, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Row(modifier) {
        text.forEachIndexed { index, char ->
            // Keyed by position from the right, so "10:00" -> "9:59" rolls the digits that
            // changed instead of shifting everything one slot left.
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        (slideInVertically(tween(ROLL_MS)) { -it } + fadeIn(tween(ROLL_MS))) togetherWith
                            (slideOutVertically(tween(ROLL_MS)) { it } + fadeOut(tween(ROLL_MS)))
                    },
                    label = "roll",
                ) { c ->
                    Text(c.toString(), style = style, color = color)
                }
            }
        }
    }
}

/** A status dot with a soft halo that breathes, so "protected" reads as live, not static. */
@Composable
internal fun PulsingDot(color: Color, size: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val halo by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "halo",
    )
    // Drawn, never transformed. Compose reports any change to a node's on-screen bounds to
    // accessibility, and graphicsLayer scale counts as one. Animating size() or a layer here
    // fired ~8 TYPE_WINDOW_CONTENT_CHANGED events a second from an idle Home screen, waking
    // every accessibility service on the phone (this app's own included) for nothing. A Canvas
    // only repaints, so the halo costs no events at all.
    Canvas(Modifier.size(size * 2.6f)) {
        val dot = size.toPx() / 2f
        drawCircle(color.copy(alpha = (1f - halo) * 0.55f), radius = dot * (1f + 1.6f * halo))
        drawCircle(color, radius = dot)
    }
}

/** Delay between staggered siblings. Short enough that a full screen lands in under half a second. */
private const val STAGGER_MS = 55L
private const val MAX_STAGGERED = 7
private const val REVEAL_LIFT_DP = 22
private const val COUNT_MS = 900
private const val ROLL_MS = 260
private const val PULSE_MS = 1_800
