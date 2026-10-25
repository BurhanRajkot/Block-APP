package com.blockapp.android.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.BlockApplication
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.EmberWash
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.util.PlanProgress
import com.blockapp.android.util.ReelLimit
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The running reel plan, as a programme rather than a setting: where the user is in it, the
 * streak of days they stopped on their own, what it has given back, every week's step against
 * what really happened, milestones, and the user's own reason and alternatives.
 *
 * All figures come from PlanProgress over Room (reel_counts + plan_days), so they agree with
 * Home and the reel wall. Editing the reason or the alternatives changes no limit. Reel-free
 * hours go through ReelPlan.setQuietHours (wider now, narrower tomorrow), and rebuilding or
 * stopping keep the rule in ReelPlan's class doc: nothing here loosens today.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(onBack: () -> Unit, onRebuild: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    var refreshes by remember { mutableIntStateOf(0) }
    var confirmingStop by remember { mutableStateOf(false) }
    var editingWhy by remember { mutableStateOf(false) }
    var editingQuiet by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        refreshes++
        onPauseOrDispose {}
    }
    val today = remember(refreshes) { LocalDate.now() }
    val plan = remember(refreshes) { ReelPlan.current(context) }
    val stopPending = remember(refreshes) { ReelPlan.stopPending(context) }
    val limit = remember(refreshes) { ReelLimit.todayLimit(context) }
    val quiet = remember(refreshes) { ReelPlan.quietHours(context) }
    val (pendingQuiet, hasPendingQuiet) = remember(refreshes) { ReelPlan.pendingQuietHours(context) }
    val why = remember(refreshes) { ReelPlan.why(context) }
    var alternatives by remember(refreshes) { mutableStateOf(ReelPlan.alternatives(context).toSet()) }

    var summary by remember { mutableStateOf<PlanProgress.Summary?>(null) }
    LaunchedEffect(plan, today) {
        if (plan == null) return@LaunchedEffect
        app.repository.observePlanProgress(plan, today).collectLatest { summary = it }
    }

    if (confirmingStop) {
        AlertDialog(
            onDismissRequest = { confirmingStop = false },
            title = { Text("Stop the plan?") },
            text = {
                Text(
                    "It stops tomorrow, not now. Today's limit and reel-free hours stay as they " +
                        "are until midnight, so stopping can't be done on impulse. Your history and " +
                        "milestones are kept.",
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
    if (editingWhy) {
        WhyDialog(
            initial = why.orEmpty(),
            onDismiss = { editingWhy = false },
            onSave = {
                ReelPlan.setWhy(context, it)
                editingWhy = false
                refreshes++
            },
        )
    }
    if (editingQuiet) {
        QuietDialog(
            initial = quiet ?: ReelPlan.QuietHours(DEFAULT_QUIET_START, DEFAULT_QUIET_END),
            hasQuiet = quiet != null,
            onDismiss = { editingQuiet = false },
            onSave = { chosen ->
                val before = ReelPlan.quietHours(context)
                ReelPlan.setQuietHours(context, chosen)
                val after = ReelPlan.quietHours(context)
                if (after != chosen && before != chosen) {
                    Toast.makeText(context, "That change starts tomorrow.", Toast.LENGTH_SHORT).show()
                }
                editingQuiet = false
                refreshes++
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "Your plan",
            onBack = onBack,
            subtitle = summary?.phase?.blurb,
        )
        Column(Modifier.padding(horizontal = ScreenPadding)) {
            if (plan == null) {
                NoPlan(onRebuild)
                return@Column
            }
            val s = summary

            Reveal(0) { PlanHero(plan, s, limit, today, stopPending) }
            Spacer(Modifier.height(12.dp))

            if (s != null) {
                Reveal(1) { StreakPanel(s) }
                Spacer(Modifier.height(12.dp))
                Reveal(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ImpactTile(
                            value = formatCount(s.reelsSkipped),
                            label = "reels skipped",
                            icon = Icons.Outlined.SkipNext,
                            modifier = Modifier.weight(1f),
                        )
                        ImpactTile(
                            value = formatHoursMinutes(s.minutesBack * 60_000L),
                            label = "of your time back",
                            icon = Icons.Outlined.Schedule,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Reveal(3) { InsightPanel(PlanProgress.insights(s)) }

                SectionHeader("The steps")
                Reveal(4) {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${plan.baseline} → ${plan.targetLimit} a day",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    "Goal by ${plan.startDay.plusWeeks((plan.weeks - 1).toLong()).format(DAY_MONTH)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            s.dropPercent?.takeIf { it > 0 }?.let {
                                StatusPill("−$it%", Lime)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        JourneyChart(weeks = s.weeks, baseline = plan.baseline)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Bars are each week's limit, dots what you really watched on average. " +
                                "The dashed line is where you started.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SectionHeader("Milestones · ${s.milestones.count { it.reached }} of ${s.milestones.size}")
                Reveal(5) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        s.milestones.chunked(2).forEach { pair ->
                            // Same height across the pair, whichever title wraps to two lines.
                            Row(
                                Modifier.height(IntrinsicSize.Min),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                pair.forEach { milestone ->
                                    MilestoneTile(
                                        milestone,
                                        milestoneIcon(milestone.title),
                                        Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            SectionHeader("Why you're doing this", action = "Edit", onAction = { editingWhy = true })
            Panel(onClick = { editingWhy = true }) {
                Icon(Icons.Outlined.Favorite, contentDescription = null, tint = Ember)
                Spacer(Modifier.height(10.dp))
                Text(
                    why?.let { "“$it”" } ?: "Add a reason in your own words. The reel wall reads it back to you.",
                    style = if (why != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                    fontStyle = if (why != null) FontStyle.Normal else FontStyle.Italic,
                    color = if (why != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Instead of the next reel")
            Panel {
                Text(
                    "Shown on the reel wall when it stops you. Pick a few.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReelPlan.Alternative.entries.forEach { alternative ->
                        val selected = alternative in alternatives
                        ToggleChip(alternative.title, selected) {
                            alternatives = if (selected) alternatives - alternative else alternatives + alternative
                            ReelPlan.setAlternatives(context, alternatives)
                        }
                    }
                }
            }

            SectionHeader("Reel-free hours", action = if (quiet == null) "Add" else "Change", onAction = { editingQuiet = true })
            Panel(onClick = { editingQuiet = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Outlined.Bedtime, tint = Ember, background = EmberWash)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(quiet?.label() ?: "None", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (quiet != null) "Every day, any reel viewer closes at once" else "A daily window with no reels at all",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (hasPendingQuiet) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "From tomorrow: ${pendingQuiet?.label() ?: "none"}",
                                style = MaterialTheme.typography.labelLarge,
                                color = Ember,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))
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
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Phase, day of the plan, and today's numbers against the step. */
@Composable
private fun PlanHero(
    plan: ReelPlan.Plan,
    summary: PlanProgress.Summary?,
    limit: Int?,
    today: LocalDate,
    stopPending: Boolean,
) {
    val dayNumber = summary?.dayNumber ?: 1
    val totalDays = plan.weeks * 7
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(summary?.phase?.title ?: plan.goal.title, color = Ember)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (dayNumber > totalDays) "Plan complete" else "Day $dayNumber",
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    if (dayNumber > totalDays) {
                        "Holding at ${plan.targetLimit} a day"
                    } else {
                        "of $totalDays · week ${plan.weekOf(today) + 1} of ${plan.weeks}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ProgressRing(
                fraction = (dayNumber.toFloat() / totalDays).coerceAtMost(1f),
                size = 92.dp,
                stroke = 9.dp,
            ) {
                Text(
                    "${((dayNumber.toFloat() / totalDays).coerceAtMost(1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        val reels = summary?.today?.reels ?: 0
        Row(Modifier.fillMaxWidth()) {
            MiniStat("$reels", "reels today", Modifier.weight(1f))
            MiniStat(
                when (limit) {
                    null -> "—"
                    0 -> "0"
                    else -> "$limit"
                },
                "today's limit",
                Modifier.weight(1f),
                color = Ember,
            )
            MiniStat(if (plan.targetLimit == 0) "0" else "${plan.targetLimit}", "the goal", Modifier.weight(1f))
        }
        if (limit != null && limit < plan.scheduledLimit(today)) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Ahead of schedule: you've been watching less than this week's step, so the limit followed you down.",
                style = MaterialTheme.typography.bodySmall,
                color = Lime,
            )
        }
        if (stopPending) {
            Spacer(Modifier.height(14.dp))
            Text("This plan stops tomorrow.", style = MaterialTheme.typography.labelLarge, color = Ember)
        }
    }
}

@Composable
private fun StreakPanel(summary: PlanProgress.Summary) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                Icons.Outlined.LocalFireDepartment,
                tint = if (summary.streak > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                background = if (summary.streak > 0) Lime else MaterialTheme.colorScheme.surfaceContainerHigh,
                size = 52.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    AnimatedCount(summary.streak, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (summary.streak == 1) "clean day" else "clean days",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(
                    "Best ${summary.bestStreak} · ${summary.cleanDays} of ${summary.scoredDays} days clean",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        DayStrip(summary.days)
        Spacer(Modifier.height(12.dp))
        OutcomeLegend()
        Spacer(Modifier.height(12.dp))
        Text(
            "A clean day is one you stopped before the wall had to. Days nothing was counting are skipped, not held against you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImpactTile(value: String, label: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Panel(modifier = modifier) {
        Icon(icon, contentDescription = null, tint = Lime)
        Spacer(Modifier.height(12.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InsightPanel(insights: List<String>) {
    Panel(color = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow("How it's going", color = Lime)
        Spacer(Modifier.height(8.dp))
        insights.take(MAX_INSIGHTS).forEachIndexed { index, line ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Text(
                line,
                style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = if (index == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun milestoneIcon(title: String): ImageVector = when {
    "reel-free" in title -> Icons.Outlined.WbSunny
    "skipped" in title -> Icons.Outlined.SkipNext
    "back" in title -> Icons.Outlined.Schedule
    "goal" in title -> Icons.Outlined.Flag
    "complete" in title -> Icons.Outlined.EmojiEvents
    "week" in title -> Icons.Outlined.CalendarMonth
    "row" in title -> Icons.Outlined.LocalFireDepartment
    else -> Icons.Outlined.Star
}

@Composable
private fun WhyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why you're doing this") },
        text = {
            Column {
                Text(
                    "One line, in your words. It's what the reel wall shows you when it stops you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= MAX_WHY_CHARS) text = it },
                    placeholder = { Text("I want my evenings back") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun QuietDialog(
    initial: ReelPlan.QuietHours,
    hasQuiet: Boolean,
    onDismiss: () -> Unit,
    onSave: (ReelPlan.QuietHours?) -> Unit,
) {
    var quiet by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reel-free hours") },
        text = {
            Column {
                QuietHoursEditor(quiet, onChange = { if (it.start != it.end) quiet = it })
                Spacer(Modifier.height(12.dp))
                Text(
                    "Wider hours start now. Narrower ones, or turning them off, start tomorrow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(quiet) }) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                if (hasQuiet) TextButton(onClick = { onSave(null) }) { Text("Turn off") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun NoPlan(onBuild: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(12.dp))
        StepsArt(height = 140.dp)
        Spacer(Modifier.height(24.dp))
        Text("No plan yet", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "A few questions, then a limit that steps down each week, follows you down when you " +
                "beat it, and keeps score of the days you stop on your own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Build my plan", onClick = onBuild, modifier = Modifier.fillMaxWidth())
    }
}

/** "1,240" rather than "1240": these totals run into the thousands. */
internal fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())

private const val MAX_INSIGHTS = 3
private const val MAX_WHY_CHARS = 120

/** Offered when reel-free hours are first added here: the window most people pick in the quiz. */
private const val DEFAULT_QUIET_START = 23 * 60
private const val DEFAULT_QUIET_END = 7 * 60
