package com.blockapp.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blockapp.android.ui.theme.BoneFaint
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.ui.theme.StatusColors
import com.blockapp.android.util.PlanProgress
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale

// The reel plan's own visuals: the day strip, the week chart, milestones, the reel-free-hours
// editor and the hold-to-commit button. Used by PlanScreen, QuizScreen and Home's plan card.
// All static or finite: see Motion.kt for why nothing here may animate forever.

/** The colour a day's outcome is drawn in. Lime is a clean day, Ember a day the wall held. */
@Composable
internal fun outcomeColor(outcome: PlanProgress.Outcome): Color = when (outcome) {
    PlanProgress.Outcome.CLEAN, PlanProgress.Outcome.REEL_FREE -> Lime
    PlanProgress.Outcome.HELD -> Ember
    PlanProgress.Outcome.OVER -> StatusColors.Danger
    PlanProgress.Outcome.NO_DATA -> MaterialTheme.colorScheme.surfaceContainerHigh
    PlanProgress.Outcome.TODAY -> MaterialTheme.colorScheme.surfaceContainerHigh
}

internal fun outcomeLabel(outcome: PlanProgress.Outcome): String = when (outcome) {
    PlanProgress.Outcome.CLEAN -> "Clean day"
    PlanProgress.Outcome.REEL_FREE -> "No reels at all"
    PlanProgress.Outcome.HELD -> "The wall stepped in"
    PlanProgress.Outcome.OVER -> "Over the limit"
    PlanProgress.Outcome.NO_DATA -> "Not counted"
    PlanProgress.Outcome.TODAY -> "Today"
}

/**
 * The last [count] days as a row of rounded cells, today last. Clean days fill Lime, held days
 * Ember, days with no data stay empty, and today is an outline, so the row reads as a streak at
 * a glance.
 */
@Composable
internal fun DayStrip(days: List<PlanProgress.Day>, modifier: Modifier = Modifier, count: Int = 14) {
    val shown = days.takeLast(count)
    val padded = List(count - shown.size) { null } + shown
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            padded.forEach { day ->
                val fill = day?.let { outcomeColor(it.outcome) } ?: Color.Transparent
                val isToday = day?.outcome == PlanProgress.Outcome.TODAY
                Box(
                    Modifier
                        .weight(1f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (day == null) MaterialTheme.colorScheme.surfaceContainerLow else fill)
                        .then(
                            if (isToday) {
                                Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(7.dp))
                            } else {
                                Modifier
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (day?.outcome == PlanProgress.Outcome.REEL_FREE) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            padded.forEach { day ->
                Text(
                    day?.date?.dayOfWeek?.getDisplayName(TextStyle.NARROW, Locale.getDefault()).orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The legend under a [DayStrip]. */
@Composable
internal fun OutcomeLegend(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendDot(Lime, "Clean")
        LegendDot(Ember, "Wall")
        LegendDot(MaterialTheme.colorScheme.surfaceContainerHigh, "No data")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The plan's weeks: a bar per week at its scheduled limit, and a dot at the week's real average
 * where there is one. A dot under its bar is the plan working; the current week is drawn full
 * colour and the rest dimmed. Bars share one axis with the baseline, drawn as a dashed line, so
 * the whole way down from "where I started" reads in one picture.
 */
@Composable
internal fun JourneyChart(
    weeks: List<PlanProgress.Week>,
    baseline: Int,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 132.dp,
) {
    val max = maxOf(baseline, weeks.maxOfOrNull { maxOf(it.scheduled, it.average ?: 0) } ?: 0, 1)
    val grow by animateFloatAsState(
        if (rememberAppeared()) 1f else 0f,
        tween(JOURNEY_GROW_MS, easing = FastOutSlowInEasing),
        label = "journey",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val dot = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(chartHeight),
        ) {
            if (weeks.isEmpty()) return@Canvas
            val slot = size.width / weeks.size
            val barWidth = (slot * 0.56f).coerceAtMost(30.dp.toPx())
            val baseY = size.height * (1f - baseline / max.toFloat())
            drawLine(
                color = line,
                start = Offset(0f, baseY),
                end = Offset(size.width, baseY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
            weeks.forEachIndexed { index, week ->
                val left = index * slot + (slot - barWidth) / 2f
                val h = (size.height * week.scheduled / max.toFloat() * grow).coerceAtLeast(3.dp.toPx())
                val color = when {
                    week.isCurrent -> Lime
                    week.average != null -> Lime.copy(alpha = 0.38f)
                    else -> track
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                )
                week.average?.let { avg ->
                    val y = size.height - (size.height * avg / max.toFloat() * grow)
                    val center = Offset(left + barWidth / 2f, y.coerceIn(4.dp.toPx(), size.height - 4.dp.toPx()))
                    drawCircle(color = if (avg > week.scheduled) Ember else dot, radius = 4.5.dp.toPx(), center = center)
                    drawCircle(color = track, radius = 4.5.dp.toPx(), center = center, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            weeks.forEach { week ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${week.scheduled}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (week.isCurrent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        "W${week.index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        color = BoneFaint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** A milestone: a badge that fills Lime when reached, or a progress arc while it isn't. */
@Composable
internal fun MilestoneTile(milestone: PlanProgress.Milestone, icon: ImageVector, modifier: Modifier = Modifier) {
    val reached = milestone.reached
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val progress by animateFloatAsState(
        if (rememberAppeared()) milestone.progress else 0f,
        tween(JOURNEY_GROW_MS, easing = FastOutSlowInEasing),
        label = "milestone",
    )
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (reached) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(40.dp)) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2f
                val arc = Size(size.width - stroke, size.height - stroke)
                if (reached) {
                    drawCircle(Lime)
                } else {
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
                    drawArc(Lime, -90f, 360f * progress, false, Offset(inset, inset), arc, style = Stroke(stroke))
                }
            }
            Icon(
                icon,
                contentDescription = null,
                tint = if (reached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                milestone.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (reached) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            Text(
                if (reached) "Done" else "${(milestone.progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Start and end of the reel-free hours, each moved in half-hour steps. Writes nothing itself:
 * the caller decides when a change applies (ReelPlan.setQuietHours: tighter now, looser
 * tomorrow).
 */
@Composable
internal fun QuietHoursEditor(quiet: ReelPlan.QuietHours, onChange: (ReelPlan.QuietHours) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeStepper("Starts", quiet.start) { onChange(quiet.copy(start = it)) }
        TimeStepper("Ends", quiet.end) { onChange(quiet.copy(end = it)) }
        Text(
            "${quiet.minutes / 60}h${if (quiet.minutes % 60 != 0) " ${quiet.minutes % 60}m" else ""} without reels, every day",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun TimeStepper(label: String, minute: Int, onChange: (Int) -> Unit) {
    fun shift(by: Int) = onChange(Math.floorMod(minute + by, MINUTES_PER_DAY))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Text(
            ReelPlan.clockLabel(minute),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = { shift(-STEP_MINUTES) }) { Icon(Icons.Outlined.Remove, contentDescription = "Earlier") }
        IconButton(onClick = { shift(STEP_MINUTES) }) { Icon(Icons.Outlined.Add, contentDescription = "Later") }
    }
}

/**
 * A button that only fires once it has been held for [HOLD_MS]. For committing to a plan: a
 * plain tap is too easy to make without reading what it agrees to, and the fill gives the
 * moment some weight. Releasing early drains the fill and does nothing.
 */
@Composable
internal fun HoldToConfirmButton(text: String, onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val fill = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        val job = scope.launch {
                            fill.animateTo(1f, tween((HOLD_MS * (1f - fill.value)).toInt(), easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onConfirm()
                        }
                        val released = tryAwaitRelease()
                        if (fill.value < 1f) {
                            job.cancel()
                            scope.launch { fill.animateTo(0f, tween(DRAIN_MS)) }
                        } else if (!released) {
                            job.cancel()
                        }
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fill.value)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            if (fill.value > 0f && fill.value < 1f) "Keep holding…" else text,
            style = MaterialTheme.typography.titleSmall,
            color = if (fill.value > 0.5f) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A tappable chip that toggles, for multi-pick lists like the user's alternatives. */
@Composable
internal fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

private const val MINUTES_PER_DAY = 24 * 60
private const val STEP_MINUTES = 30

/** Long enough to be a decision, short enough not to feel like a chore. */
private const val HOLD_MS = 1_400
private const val DRAIN_MS = 250
private const val JOURNEY_GROW_MS = 900
