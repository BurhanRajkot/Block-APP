package com.blockapp.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.usage.AppUsage
import com.blockapp.android.usage.UsageStatsProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenTimeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(false) }
    var usages by remember { mutableStateOf<List<AppUsage>>(emptyList()) }

    // Usage access is a special app-op with no grant callback, so — like the other permission
    // checks in this app — it's re-polled every time this screen resumes (e.g. coming back from
    // the settings screen below) rather than checked once.
    LifecycleResumeEffect(Unit) {
        hasAccess = UsageStatsProvider.hasUsageAccess(context)
        usages = if (hasAccess) UsageStatsProvider.todayUsageByApp(context) else emptyList()
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screen time") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (!hasAccess) {
                UsageAccessRationale(
                    onGrant = {
                        context.startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            } else {
                UsageBreakdown(usages)
            }
        }
    }
}

@Composable
private fun UsageAccessRationale(onGrant: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            "See how your time is actually split across apps today: a running total plus a " +
                "per-app breakdown, ranked by time spent.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This needs \"Usage access\", a separate system permission Android keeps outside " +
                "the normal permission prompts. It only reads how long each app was in the " +
                "foreground — never what's on screen or what you typed — and nothing here " +
                "leaves this device.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
            Text("Grant usage access")
        }
    }
}

@Composable
private fun UsageBreakdown(usages: List<AppUsage>) {
    val totalMillis = usages.sumOf { it.foregroundMillis }
    val maxMillis = usages.maxOfOrNull { it.foregroundMillis } ?: 0L

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Today's screen time", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            formatHoursMinutes(totalMillis),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(24.dp))

        if (usages.isEmpty()) {
            Text("No app usage recorded yet today.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("By app", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(usages) { usage ->
                    UsageBarRow(usage, maxMillis)
                }
            }
        }
    }
}

/**
 * One ranked bar per app: length is proportional to the longest-used app today, not to a fixed
 * scale — the point is comparing apps against each other, not reading an absolute axis. Rounded
 * only at the tip (the "data end"); square at the baseline where the bar originates, per the
 * bar-mark spec.
 */
@Composable
private fun UsageBarRow(usage: AppUsage, maxMillis: Long) {
    val fraction = if (maxMillis > 0) {
        (usage.foregroundMillis.toFloat() / maxMillis.toFloat()).coerceIn(0.04f, 1f)
    } else {
        0f
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                usage.label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                formatHoursMinutes(usage.foregroundMillis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(18.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(
                        RoundedCornerShape(
                            topStart = 0.dp,
                            bottomStart = 0.dp,
                            topEnd = 6.dp,
                            bottomEnd = 6.dp,
                        ),
                    )
                    .background(MaterialTheme.colorScheme.primary)
                    .align(Alignment.CenterStart),
            )
        }
    }
}

private fun formatHoursMinutes(ms: Long): String {
    val totalMinutes = ms / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
