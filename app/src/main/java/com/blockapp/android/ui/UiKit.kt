package com.blockapp.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.ui.theme.BoneFaint
import com.blockapp.android.ui.theme.Ink950
import java.time.LocalDate
import java.time.YearMonth

// The handful of building blocks every screen is assembled from. Keeping them here is what
// keeps the screens consistent with each other: one card radius, one header, one button height.

internal val ScreenPadding = 20.dp
/**
 * 20dp, down from 28. The very round cards were half of the soft, generated look; this keeps
 * them friendly without every panel reading as a pill.
 */
internal val PanelShape = RoundedCornerShape(20.dp)

/** The card everything sits on. Clickable only when [onClick] is given. */
@Composable
internal fun Panel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.98f)
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(PanelShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), PanelShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, LocalIndication.current, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(contentPadding),
        content = content,
    )
}

/** Small all-caps label above a figure or a group. */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else color,
        modifier = modifier,
    )
}

/** Title of a group of content on a page, with an optional text action on the right. */
@Composable
internal fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 28.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Header for every screen that isn't a bottom-bar tab: back arrow, then a large left-aligned
 * title. Replaces Material's TopAppBar so sub-screens read as part of the same app as the tabs.
 */
@Composable
internal fun ScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The one filled button style: full width, pill, 56dp. Lime unless a [color] is given. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.95f)
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        enabled = enabled,
        interactionSource = interaction,
        modifier = modifier
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = color,
            contentColor = contentColor,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = BoneFaint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

/** Secondary to [PrimaryButton]: same shape, raised-surface fill, normal text colour. */
@Composable
internal fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    PrimaryButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
}

/** A rounded icon tile, for settings rows and stat labels. */
@Composable
internal fun IconTile(icon: ImageVector, tint: Color, background: Color, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A dot plus a word, for at-a-glance state ("Protected", "Off"). [live] makes the dot breathe. */
@Composable
internal fun StatusPill(text: String, color: Color, onClick: (() -> Unit)? = null, live: Boolean = false) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = if (live) 6.dp else 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            PulsingDot(color)
            Spacer(Modifier.width(2.dp))
        } else {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** A small figure with a caption under it, for rows of secondary stats. */
@Composable
internal fun MiniStat(value: String, label: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** [MiniStat] whose figure counts up on arrival. */
@Composable
internal fun AnimatedMiniStat(value: Int, label: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Column(modifier) {
        AnimatedCount(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Thin horizontal meter: [fraction] of the track filled in [color]. Used for "share of the
 * top app" in usage lists and for lock progress.
 */
@Composable
internal fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "meter")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

/**
 * Vertical bar chart. Bars are scaled to the largest value, not to a fixed axis: these charts
 * compare parts of a day or days of a week against each other. Zero bars still draw a short
 * stub, so an empty hour reads as "nothing" rather than as missing data.
 *
 * [highlight] draws that bar in [color] and the rest dimmed. Without one, all bars are full
 * colour. [onSelect], when given, makes bars tappable.
 */
@Composable
internal fun BarChart(
    values: List<Long>,
    color: Color,
    modifier: Modifier = Modifier,
    highlight: Int? = null,
    labels: List<String>? = null,
    onSelect: ((Int) -> Unit)? = null,
    chartHeight: Dp = 96.dp,
) {
    val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    // Bars grow from the baseline on arrival, left to right, then glide to new heights when
    // the data changes (another day picked, the minute refresh) instead of jumping.
    val appeared = rememberAppeared()
    val fractions = values.mapIndexed { index, value ->
        animateFloatAsState(
            targetValue = if (appeared) value.toFloat() / max else 0f,
            animationSpec = tween(
                durationMillis = BAR_GROW_MS,
                delayMillis = if (appeared) index * BAR_STAGGER_MS / values.size.coerceAtLeast(1) else 0,
                easing = FastOutSlowInEasing,
            ),
            label = "bar",
        )
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val dim = color.copy(alpha = 0.35f)
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .then(
                    if (onSelect != null && values.isNotEmpty()) {
                        Modifier.pointerInput(values.size) {
                            detectTapGestures { offset ->
                                val slot = size.width / values.size.toFloat()
                                onSelect((offset.x / slot).toInt().coerceIn(0, values.lastIndex))
                            }
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (values.isEmpty()) return@Canvas
            val slot = size.width / values.size
            val barWidth = (slot * BAR_FILL).coerceAtMost(28.dp.toPx())
            val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
            val stub = 3.dp.toPx()
            values.forEachIndexed { index, value ->
                val left = index * slot + (slot - barWidth) / 2f
                val fraction = fractions[index].value
                val barHeight = if (value <= 0L) {
                    stub
                } else {
                    (size.height * fraction).coerceAtLeast(stub)
                }
                val barColor = when {
                    value <= 0L -> track
                    highlight == null || highlight == index -> color
                    else -> dim
                }
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = radius,
                )
            }
        }
        if (labels != null && labels.size == values.size) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { index, label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (highlight == index) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Hour ticks under a 24-bar day chart: only every sixth hour, or they collide at phone width. */
@Composable
internal fun HourAxis(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("12a", "6a", "12p", "6p", "").forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Pill-shaped search box used above app lists. */
@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(50),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Share of each bar's slot the bar fills; the rest is the gap between bars. */
private const val BAR_FILL = 0.62f

/** One bar's grow time, and the total spread of start times across a chart. */
private const val BAR_GROW_MS = 650
private const val BAR_STAGGER_MS = 420

/**
 * Stand-in rows for a list that is still loading off the main thread (rememberLaunchableApps).
 * Static on purpose: a shimmer would be an animation that runs for as long as the load does,
 * and see the Motion.kt rule on what looping animations cost here.
 */
@Composable
internal fun LoadingRows(count: Int = 8, modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        repeat(count) { index ->
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(block),
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.55f else 0.4f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(50))
                        .background(block),
                )
            }
        }
    }
}

/**
 * The hero ring: [fraction] of a circle in Lime over a faint track, with
 * [content] centred inside. Past 1 the ring is simply full and turns solid Ember, since "over the
 * limit" should read as a different state, not a slightly longer arc.
 */
@Composable
internal fun ProgressRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    stroke: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val animated by animateFloatAsState(
        if (rememberAppeared()) fraction.coerceIn(0f, 1f) else 0f,
        tween(RING_GROW_MS, easing = FastOutSlowInEasing),
        label = "ring",
    )
    val over = fraction >= 1f
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = stroke.toPx()
            val inset = width / 2f
            val arcSize = Size(this.size.width - width, this.size.height - width)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width),
            )
            if (animated > 0f) {
                rotate(-90f) {
                    drawArc(
                        color = if (over) Ember else accent,
                        startAngle = 0f,
                        sweepAngle = 360f * animated,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width, cap = StrokeCap.Round),
                    )
                }
            }
        }
        content()
    }
}

/** A pill switch between [options]. The selected pill slides rather than jumps. */
@Composable
internal fun <T> SegmentedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = options.indexOf(selected).coerceAtLeast(0)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(4.dp),
    ) {
        val optionWidth = maxWidth / options.size
        val offset by animateDpAsState(
            optionWidth * index,
            spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
            label = "segment",
        )
        Box(
            Modifier
                .offset(x = offset)
                .width(optionWidth)
                .height(40.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
        Row {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .width(optionWidth)
                        .height(40.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** One answer in the quiz: a full-width card that fills with Lime when chosen. */
@Composable
internal fun ChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, pressedScale = 0.97f)
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    // The fill and edge fade between states rather than snapping, so a tap reads as the card
    // being chosen, not the screen redrawing.
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        tween(CHOICE_FADE_MS),
        label = "choiceFill",
    )
    val edge by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        tween(CHOICE_FADE_MS),
        label = "choiceEdge",
    )
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(fill)
            .border(if (selected) 1.5.dp else 1.dp, edge, shape)
            .clickable(interaction, LocalIndication.current) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                .border(
                    2.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
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

/**
 * A month as a heat map: one rounded cell per day, tinted by [valueOf] against the month's
 * busiest day in [color]. Days outside [enabled] (before install, after today) are drawn faint
 * and can't be tapped. Weeks start on Monday.
 */
@Composable
internal fun HeatCalendar(
    month: YearMonth,
    valueOf: (LocalDate) -> Long,
    color: Color,
    selected: LocalDate?,
    enabled: (LocalDate) -> Boolean,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value - 1
    val days = month.lengthOfMonth()
    val max = (1..days).maxOf { valueOf(month.atDay(it)) }.coerceAtLeast(1L)
    val today = LocalDate.now()
    val empty = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val cells = leading + days
        val rows = (cells + 6) / 7
        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val dayNumber = row * 7 + column - leading + 1
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .padding(3.dp),
                    ) {
                        if (dayNumber in 1..days) {
                            val day = month.atDay(dayNumber)
                            val isEnabled = enabled(day)
                            val value = if (isEnabled) valueOf(day) else 0L
                            val share = value.toFloat() / max
                            val fill = when {
                                !isEnabled -> Color.Transparent
                                value <= 0L -> empty
                                else -> color.copy(alpha = 0.22f + 0.78f * share)
                            }
                            val isSelected = day == selected
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(fill)
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(10.dp))
                                        } else if (day == today) {
                                            Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .then(if (isEnabled) Modifier.clickable { onSelect(day) } else Modifier),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$dayNumber",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                        share > 0.55f -> Ink950
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How long a [ChoiceCard] takes to fill when chosen. */
private const val CHOICE_FADE_MS = 180

/** One full sweep of a [ProgressRing] on arrival. */
private const val RING_GROW_MS = 1_000
