package com.blockapp.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.admin.DeviceAdminHelper

// ── status colours ────────────────────────────────────────────────────────────
// Everything else in this screen (background, surfaces, text, accent) comes from
// MaterialTheme.colorScheme so it matches the rest of the app. These two are the only
// screen-specific colours — the same green/red semantics already used on HomeScreen's
// protection banner — kept literal because "granted / needs attention" is a status,
// not a brand colour.
private val GrantedGreen   = Color(0xFF2E7D32)
private val NeedsAttention = Color(0xFFB71C1C)

// ── data model ────────────────────────────────────────────────────────────────
private data class PermissionStep(
    val icon: String,
    val title: String,
    val rationale: String,
    val buttonLabel: String,
    val isGranted: Boolean,
    val onClick: () -> Unit,
)

// ── screen ────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit, onRemoveProtection: () -> Unit) {
    val context = LocalContext.current

    var isAccessibilityActive  by remember { mutableStateOf(false) }
    var isAdminActive          by remember { mutableStateOf(false) }
    var isBatteryUnrestricted  by remember { mutableStateOf(false) }
    var canScheduleExactAlarms by remember { mutableStateOf(false) }

    // Re-check every time this screen resumes (e.g. coming back from Settings).
    LifecycleResumeEffect(Unit) {
        isAccessibilityActive  = DeviceAdminHelper.isAccessibilityActive(context)
        isAdminActive          = DeviceAdminHelper.isAdminActive(context)
        isBatteryUnrestricted  = DeviceAdminHelper.isIgnoringBatteryOptimizations(context)
        canScheduleExactAlarms = DeviceAdminHelper.canScheduleExactAlarms(context)
        onPauseOrDispose {}
    }

    val steps = remember(isAccessibilityActive, isAdminActive, isBatteryUnrestricted, canScheduleExactAlarms) {
        listOf(
            PermissionStep(
                icon        = "♿",
                title       = "Accessibility Service",
                rationale   = "Detects when a locked app comes to the foreground so it can redirect you away — entirely on-device. It does not read what you type or capture your screen.",
                buttonLabel = "Open Accessibility Settings",
                isGranted   = isAccessibilityActive,
                onClick     = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ),
            PermissionStep(
                icon        = "🛡️",
                title       = "Device Admin",
                rationale   = "Prevents the app from being uninstalled while a lock is active. To disable it later, use \"Remove protection\" at the top of this screen — not the system settings.",
                buttonLabel = "Activate Device Admin",
                isGranted   = isAdminActive,
                onClick     = {
                    context.startActivity(
                        DeviceAdminHelper.requestAdminIntent(
                            context,
                            "Prevents the app being uninstalled while a lock is active.",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ),
            PermissionStep(
                icon        = "🔋",
                title       = "Ignore Battery Optimizations",
                rationale   = "Some manufacturers silently kill background services to save power. This keeps the blocking service alive so locks never slip through unexpectedly.",
                buttonLabel = "Open Battery Settings",
                isGranted   = isBatteryUnrestricted,
                onClick     = { requestIgnoreBatteryOptimizations(context) },
            ),
            PermissionStep(
                icon        = "⏰",
                title       = "Exact Alarms",
                rationale   = "Lifts a lock at the precise moment its timer ends instead of drifting late. Only used for lock expiry — no other alarms or reminders are ever scheduled.",
                buttonLabel = "Allow Exact Alarms",
                isGranted   = canScheduleExactAlarms,
                onClick     = { requestScheduleExactAlarm(context) },
            ),
        )
    }

    val grantedCount = steps.count { it.isGranted }
    val allGranted   = grantedCount == steps.size
    val progress by animateFloatAsState(
        targetValue   = grantedCount.toFloat() / steps.size,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "progress",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Permission Setup", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    TextButton(onClick = onRemoveProtection) {
                        Text("Remove protection", color = NeedsAttention)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── progress header ───────────────────────────────────────────
            ProgressHeader(
                grantedCount = grantedCount,
                total        = steps.size,
                progress     = progress,
            )

            Spacer(Modifier.height(8.dp))

            // ── permission cards ──────────────────────────────────────────
            steps.forEachIndexed { index, step ->
                PermissionCard(
                    step  = step,
                    index = index + 1,
                    total = steps.size,
                )
                Spacer(Modifier.height(12.dp))
            }

            // ── all-done banner ───────────────────────────────────────────
            AnimatedVisibility(
                visible = allGranted,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    AllDoneBanner()
                    Spacer(Modifier.height(12.dp))
                }
            }

            // ── continue button ───────────────────────────────────────────
            Button(
                onClick  = onDone,
                enabled  = allGranted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (allGranted) "Continue →" else "Grant all permissions to continue",
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── privacy footer ────────────────────────────────────────────
            Text(
                "🔒  Everything runs entirely on this device. No usage data, screen content, " +
                    "or app list is ever transmitted anywhere.",
                style     = MaterialTheme.typography.labelSmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier  = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── progress header ────────────────────────────────────────────────────────────
@Composable
private fun ProgressHeader(grantedCount: Int, total: Int, progress: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                "Grant required permissions",
                style      = MaterialTheme.typography.titleSmall,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "$grantedCount / $total",
                style      = MaterialTheme.typography.titleSmall,
                color      = if (grantedCount == total) GrantedGreen else MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress   = { progress },
            modifier   = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
            color      = if (grantedCount == total) GrantedGreen else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap  = StrokeCap.Round,
        )
    }
}

// ── permission card ────────────────────────────────────────────────────────────
@Composable
private fun PermissionCard(step: PermissionStep, index: Int, total: Int) {
    val cardBg by animateColorAsState(
        targetValue   = if (step.isGranted) GrantedGreen.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 500),
        label         = "cardBg",
    )

    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = cardBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ── header row ────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                // icon badge
                Box(
                    modifier         = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (step.isGranted) GrantedGreen.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        step.icon,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Step $index of $total",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        step.title,
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // status chip
                AnimatedContent(
                    targetState    = step.isGranted,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label          = "statusChip",
                ) { granted ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (granted) GrantedGreen.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            if (granted) "✓ Done" else "Pending",
                            style      = MaterialTheme.typography.labelSmall,
                            color      = if (granted) GrantedGreen else MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold,
                            modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── rationale ─────────────────────────────────────────────
            Text(
                step.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── action button (only when pending) ─────────────────────
            AnimatedVisibility(
                visible = !step.isGranted,
                enter   = expandVertically(tween(300)) + fadeIn(),
                exit    = shrinkVertically(tween(300)) + fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick  = step.onClick,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(10.dp),
                    ) {
                        Text(step.buttonLabel, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ── all-done banner ────────────────────────────────────────────────────────────
@Composable
private fun AllDoneBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GrantedGreen.copy(alpha = 0.1f)),
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🎉", style = MaterialTheme.typography.headlineMedium)
            Column {
                Text(
                    "Protection fully armed!",
                    style      = MaterialTheme.typography.titleSmall,
                    color      = GrantedGreen,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "All permissions granted. Use \"Remove protection\" at the top if you ever need to disable this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── helpers ────────────────────────────────────────────────────────────────────
private fun requestScheduleExactAlarm(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val directIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(directIntent)
    } catch (e: ActivityNotFoundException) {
        // A few OEM builds don't resolve the direct per-app request — fall back to the
        // general battery optimization list so the user can still find this app manually.
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
