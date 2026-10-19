package com.blockapp.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** What was just locked, for [LockedCelebration]. */
data class LockedSummary(
    val title: String,
    val blockUntil: Long,
    val packageNames: List<String>,
)

/**
 * The moment after a lock starts: the padlock snaps shut, a ring and a burst of sparks go out,
 * the phone ticks, and the unlock time is restated one last time. Committing to a lock is the
 * one thing this app asks of the user, so it gets a beat of acknowledgement instead of a silent
 * jump back to Home.
 *
 * Purely visual. The lock was already written by BlockRepository.lockApp before this appears,
 * so dismissing early (a tap anywhere) or the process dying mid-animation changes nothing.
 */
@Composable
internal fun LockedCelebration(summary: LockedSummary, onFinished: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scrim = remember { Animatable(0f) }
    val badge = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val text = remember { Animatable(0f) }
    var shut by remember { mutableStateOf(false) }

    LaunchedEffect(summary) {
        coroutineScope {
            launch { scrim.animateTo(1f, tween(220)) }
            launch { badge.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)) }
            delay(SHUT_DELAY_MS)
            shut = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            launch { burst.animateTo(1f, tween(BURST_MS, easing = LinearOutSlowInEasing)) }
            launch { text.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
        }
        delay(HOLD_MS)
        scrim.animateTo(0f, tween(240))
        onFinished()
    }

    val volt = MaterialTheme.colorScheme.primary
    val spark = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = scrim.value }
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onFinished),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = burst.value
                    if (p > 0f) {
                        val c = Offset(size.width / 2, size.height / 2)
                        val maxR = size.minDimension / 2
                        // Expanding ring.
                        drawCircle(
                            color = volt.copy(alpha = (1f - p) * 0.6f),
                            radius = maxR * (0.35f + 0.65f * p),
                            center = c,
                            style = Stroke(width = (6f * (1f - p) + 1f).dp.toPx()),
                        )
                        // Sparks flying out and fading.
                        repeat(SPARKS) { i ->
                            val angle = (i * 360f / SPARKS + 12f) * (Math.PI / 180.0)
                            val r = maxR * (0.3f + 0.7f * p) * if (i % 2 == 0) 1f else 0.78f
                            drawCircle(
                                color = (if (i % 3 == 0) spark else volt).copy(alpha = 1f - p),
                                radius = (5f * (1f - p) + 1f).dp.toPx(),
                                center = Offset(c.x + r * cos(angle).toFloat(), c.y + r * sin(angle).toFloat()),
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .size(112.dp)
                        .graphicsLayer {
                            scaleX = badge.value
                            scaleY = badge.value
                        }
                        .clip(CircleShape)
                        .background(volt),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (shut) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(52.dp)
                            .graphicsLayer {
                                // The shackle "drops" as it closes.
                                translationY = if (shut) 0f else (-4).dp.toPx()
                            },
                    )
                }
            }
            Column(
                Modifier
                    .padding(horizontal = 32.dp)
                    .graphicsLayer {
                        alpha = text.value
                        translationY = (1f - text.value) * 16.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Locked in.", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${summary.title} until ${formatUnlockAt(summary.blockUntil)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (summary.packageNames.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                        summary.packageNames.take(MAX_ICONS).forEach { pkg ->
                            val identity = rememberAppIdentity(pkg)
                            AppIcon(identity, size = 32)
                        }
                    }
                }
            }
        }
    }
}

private const val SHUT_DELAY_MS = 380L
private const val BURST_MS = 900
private const val HOLD_MS = 1_500L
private const val SPARKS = 14
private const val MAX_ICONS = 6
