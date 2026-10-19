package com.blockapp.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.EmberWash
import com.blockapp.android.util.ReelLimit
import com.blockapp.android.util.ReelPlan
import java.time.LocalDate

/**
 * The running reel plan: where the user is in it, today's limit, the weeks ahead, and the
 * reel-free hours. Rebuilding goes through [onRebuild] (the quiz), and stopping only takes effect
 * from tomorrow, both for the reason in ReelPlan's class doc.
 */
@Composable
fun PlanScreen(onBack: () -> Unit, onRebuild: () -> Unit) {
    val context = LocalContext.current
    var refreshes by remember { mutableIntStateOf(0) }
    var confirmingStop by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        refreshes++
        onPauseOrDispose {}
    }
    val plan = remember(refreshes) { ReelPlan.current(context) }
    val stopPending = remember(refreshes) { ReelPlan.stopPending(context) }
    val limit = remember(refreshes) { ReelLimit.todayLimit(context) }
    val quiet = remember(refreshes) { ReelPlan.quietHours(context) }
    val (pendingQuiet, hasPendingQuiet) = remember(refreshes) { ReelPlan.pendingQuietHours(context) }

    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text("Stop the plan?") },
            text = {
                Text(
                    "It stops tomorrow, not now. Today's limit and reel-free hours stay as they " +
                        "are until midnight, so stopping can't be done on impulse.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ReelPlan.stopFromTomorrow(context)
                    confirmingStop = false
                    refreshes++
                }) { Text("Stop from tomorrow", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmingStop = false }) { Text("Keep going") } },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "Reel plan", onBack = onBack)
        Column(Modifier.padding(horizontal = ScreenPadding)) {
            if (plan == null) {
                NoPlan(onRebuild)
                return@Column
            }
            val today = LocalDate.now()
            val week = plan.weekOf(today)
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(
                        fraction = (week + 1) / plan.weeks.toFloat(),
                        size = 112.dp,
                        stroke = 10.dp,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${week + 1}/${plan.weeks}", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "weeks",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Eyebrow(plan.goal.title, color = Ember)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when (limit) {
                                null -> "No limit today"
                                0 -> "No reels today"
                                else -> "$limit reels today"
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            if (limit != null && limit < plan.scheduledLimit(today)) {
                                "Ahead of plan: you're beating the schedule."
                            } else {
                                "Goal: ${plan.targetLimit} a day"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                BarChart(
                    values = plan.steps.map { it.toLong() },
                    color = Ember,
                    highlight = week,
                    labels = List(plan.steps.size) { "W${it + 1}" },
                    chartHeight = 96.dp,
                )
                if (stopPending) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "This plan stops tomorrow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ember,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Outlined.Bedtime, tint = Ember, background = EmberWash)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Reel-free hours", style = MaterialTheme.typography.titleMedium)
                        Text(
                            quiet?.let { "${it.label()}, every day" } ?: "None",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hasPendingQuiet) {
                            Text(
                                "From tomorrow: ${pendingQuiet?.label() ?: "none"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Ember,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton("Rebuild my plan", onClick = onRebuild, modifier = Modifier.fillMaxWidth())
            if (!stopPending) {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Stop the plan", onClick = { confirmingStop = true }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "A rebuilt plan can only make today stricter. Anything looser starts tomorrow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NoPlan(onBuild: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        StepsArt(height = 140.dp)
        Spacer(Modifier.height(24.dp))
        Text("No plan yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Answer five questions and get a reel limit that steps down week by week.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Build my plan", onClick = onBuild, modifier = Modifier.fillMaxWidth())
    }
}
