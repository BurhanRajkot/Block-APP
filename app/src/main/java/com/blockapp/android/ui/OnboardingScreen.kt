package com.blockapp.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.ui.theme.StatusColors
import com.blockapp.android.usage.UsageStatsProvider

// ── data model ────────────────────────────────────────────────────────────────
private data class SetupStep(
    val icon: ImageVector,
    val title: String,
    val rationale: String,
    /**
     * What to tap once the button lands them in Settings. Every deep link below drops the user on
     * a *list* screen, never on the toggle itself, and on a stock ROM the app is several taps
     * down an unlabelled hierarchy — leaving that unsaid is where first-time setup actually
     * stalls, so the taps are spelled out rather than left to be discovered.
     */
    val howTo: List<String>,
    val buttonLabel: String,
    val isGranted: Boolean,
    /** Non-null disables the action and says why; used to hold steps to a workable order. */
    val blockedReason: String? = null,
    val onClick: () -> Unit,
)

// ── screen ────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onRemoveProtection: () -> Unit,
) {
    val context = LocalContext.current

    var isAccessibilityActive  by remember { mutableStateOf(false) }
    var isAdminActive          by remember { mutableStateOf(false) }
    var isBatteryUnrestricted  by remember { mutableStateOf(false) }
    var canScheduleExactAlarms by remember { mutableStateOf(false) }
    var hasNotifications       by remember { mutableStateOf(false) }
    var hasUsageAccess         by remember { mutableStateOf(false) }

    /**
     * Device Admin is launched through an activity-result launcher rather than
     * `context.startActivity(...)` like every other step on this screen, and that difference is
     * load-bearing: Settings' `DeviceAdminAdd` calls `finish()` immediately if the intent that
     * started it carries `FLAG_ACTIVITY_NEW_TASK` (see DeviceAdminHelper.requestAdminIntent).
     * Launching through the registry starts it for a result from MainActivity itself, so the
     * intent reaches Settings with no task flags at all and the activation dialog actually shows.
     *
     * The result is ignored — the user can back out of the system dialog without a cancel result
     * on some builds — so admin state is re-read from DevicePolicyManager instead of trusted from
     * the result code.
     */
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        isAdminActive = DeviceAdminHelper.isAdminActive(context)
    }

    // Re-check every time this screen resumes (e.g. coming back from Settings).
    LifecycleResumeEffect(Unit) {
        isAccessibilityActive  = DeviceAdminHelper.isAccessibilityActive(context)
        isAdminActive          = DeviceAdminHelper.isAdminActive(context)
        isBatteryUnrestricted  = DeviceAdminHelper.isIgnoringBatteryOptimizations(context)
        canScheduleExactAlarms = DeviceAdminHelper.canScheduleExactAlarms(context)
        hasNotifications       = DeviceAdminHelper.areNotificationsEnabled(context)
        hasUsageAccess         = UsageStatsProvider.hasUsageAccess(context)
        onPauseOrDispose {}
    }

    // ── required: without these the app does not block anything ──────────────
    val requiredSteps = remember(isAccessibilityActive, isAdminActive) {
        listOf(
            SetupStep(
                icon        = Icons.Filled.AccessibilityNew,
                title       = "Accessibility Service",
                rationale   = "Detects when a locked app comes to the foreground so it can " +
                    "redirect you away — entirely on-device. It does not read what you type or " +
                    "capture your screen.",
                howTo       = listOf(
                    "Find \"App Blocker\" — usually under Installed apps / Downloaded apps",
                    "Turn the toggle on",
                    "Confirm on the system dialog that appears",
                ),
                buttonLabel = "Open Accessibility settings",
                isGranted   = isAccessibilityActive,
                onClick     = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            ),
            SetupStep(
                icon        = Icons.Filled.Shield,
                title       = "Device Admin",
                rationale   = "Makes Android require deactivating admin before the app can be " +
                    "uninstalled, so it can't be removed in a couple of taps mid-lock. To turn " +
                    "it off later use \"Remove protection\" at the top of this screen — not " +
                    "system settings.",
                howTo       = listOf(
                    "Read the warning Android shows",
                    "Tap \"Activate this device admin app\"",
                ),
                buttonLabel = "Activate Device Admin",
                isGranted   = isAdminActive,
                // Order matters, and getting it wrong is confusing rather than harmful: once
                // Device Admin is active the accessibility service's Tier 1 guard arms, and it
                // bounces the Accessibility settings screen. Activating admin first means the
                // moment the user finally flips the Accessibility toggle on, the service starts
                // and immediately kicks them out of the screen they're standing in.
                blockedReason = if (isAccessibilityActive) {
                    null
                } else {
                    "Turn on the Accessibility Service first — once admin is active, this app " +
                        "starts guarding the Accessibility screen and you'd be bounced out of it " +
                        "mid-setup."
                },
                onClick     = {
                    val intent = DeviceAdminHelper.requestAdminIntent(
                        context,
                        "Prevents the app being uninstalled while a lock is active.",
                    )
                    try {
                        adminLauncher.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        // A handful of OEM builds don't expose DeviceAdminAdd to third-party
                        // apps. Nothing to fall back to — there is no other intent that adds an
                        // admin — so say where to do it by hand rather than letting the tap
                        // throw and take the setup screen down with it.
                        Toast.makeText(
                            context,
                            "This device doesn't offer the Device Admin screen directly. Add " +
                                "\"App Blocker\" under Settings → Security → Device admin apps.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ),
        )
    }

    // ── recommended: blocking still works without these, less reliably ───────
    val recommendedSteps = remember(
        isBatteryUnrestricted, canScheduleExactAlarms, hasNotifications, hasUsageAccess,
    ) {
        listOf(
            SetupStep(
                icon        = Icons.Filled.BatteryChargingFull,
                title       = "Ignore battery optimisations",
                rationale   = "Some manufacturers silently kill background services to save " +
                    "power. Without this a lock can stop being enforced partway through.",
                howTo       = emptyList(),
                buttonLabel = "Allow",
                isGranted   = isBatteryUnrestricted,
                onClick     = { requestIgnoreBatteryOptimizations(context) },
            ),
            SetupStep(
                icon        = Icons.Filled.Alarm,
                title       = "Exact alarms",
                rationale   = "Lifts a lock at the moment its timer ends instead of drifting " +
                    "late. Without it locks still expire, just not always punctually.",
                howTo       = emptyList(),
                buttonLabel = "Allow",
                isGranted   = canScheduleExactAlarms,
                onClick     = { requestScheduleExactAlarm(context) },
            ),
            SetupStep(
                icon        = Icons.Filled.Notifications,
                title       = "Notifications",
                rationale   = "Shows the ongoing \"blocking is active\" notification. Hidden, " +
                    "the keep-alive service is a quieter target for battery managers to kill.",
                howTo       = emptyList(),
                buttonLabel = "Allow",
                isGranted   = hasNotifications,
                onClick     = { openNotificationSettings(context) },
            ),
            SetupStep(
                icon        = Icons.Filled.BarChart,
                title       = "Usage access",
                rationale   = "Only used by the Screen time screen. Nothing else reads it, and " +
                    "no usage data leaves the device.",
                howTo       = emptyList(),
                buttonLabel = "Allow",
                isGranted   = hasUsageAccess,
                onClick     = { openUsageAccessSettings(context) },
            ),
        )
    }

    val requiredGranted = requiredSteps.count { it.isGranted }
    val allRequired     = requiredGranted == requiredSteps.size
    val extrasGranted   = recommendedSteps.count { it.isGranted }
    val progress by animateFloatAsState(
        targetValue   = requiredGranted.toFloat() / requiredSteps.size,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "progress",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setup", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onRemoveProtection) {
                        Text("Remove protection", color = StatusColors.Danger)
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
            ProgressHeader(
                grantedCount = requiredGranted,
                total        = requiredSteps.size,
                progress     = progress,
            )

            // Only while the Accessibility toggle is still off — that's the exact moment the
            // "Restricted setting" wall appears, and it's the single most common reason
            // first-time setup dies before it starts on a sideloaded build.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isAccessibilityActive) {
                RestrictedSettingsCard(onOpenAppInfo = { openAppInfo(context) })
                Spacer(Modifier.height(12.dp))
            }

            SectionHeader("Required", "Blocking does nothing without these")

            requiredSteps.forEachIndexed { index, step ->
                RequiredStepCard(step = step, index = index + 1, total = requiredSteps.size)
                Spacer(Modifier.height(12.dp))
            }

            AnimatedVisibility(
                visible = allRequired,
                enter   = expandVertically() + fadeIn(),
                exit    = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    AllDoneBanner()
                    Spacer(Modifier.height(12.dp))
                }
            }

            SectionHeader(
                "Recommended",
                "$extrasGranted of ${recommendedSteps.size} on — locks work without these, " +
                    "just less reliably",
            )

            recommendedSteps.forEach { step ->
                RecommendedStepRow(step)
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(12.dp))

            // Gated on the required steps only. Holding the user here until every optional
            // reliability extra is granted was the old behaviour and it made the screen feel
            // like a dead end on OEMs that don't expose one of them at all.
            Button(
                onClick  = onDone,
                enabled  = allRequired,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (allRequired) "Done →" else "Finish the required steps to continue",
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Everything runs entirely on this device. No usage data, screen content, " +
                        "or app list is ever transmitted anywhere.",
                    style     = MaterialTheme.typography.labelSmall,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
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
                "Required permissions",
                style      = MaterialTheme.typography.titleSmall,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "$grantedCount / $total",
                style      = MaterialTheme.typography.titleSmall,
                color      = if (grantedCount == total) StatusColors.Success else MaterialTheme.colorScheme.primary,
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
            color      = if (grantedCount == total) StatusColors.Success else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap  = StrokeCap.Round,
        )
    }
}

// ── section header ─────────────────────────────────────────────────────────────
@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title.uppercase(),
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── restricted-settings explainer ──────────────────────────────────────────────
/**
 * Android 13+ refuses to let a sideloaded app turn on an Accessibility Service until "Allow
 * restricted settings" has been tapped in that app's App info — the toggle is simply inert and
 * the only feedback is a one-line dialog most people dismiss. There is no API to query that
 * state, so this can't be a verifiable step with a tick; it's shown as a heads-up for as long as
 * Accessibility is off and quietly disappears once the toggle takes.
 *
 * Opening App info from here is safe specifically because it's only shown while Accessibility is
 * off: the service isn't running, so the Tier 1 guard that normally bounces this app's App info
 * screen (see AppBlockAccessibilityService) can't fire.
 */
@Composable
private fun RestrictedSettingsCard(onOpenAppInfo: () -> Unit) {
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = StatusColors.Warning.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = StatusColors.Warning)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Accessibility toggle greyed out?",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = StatusColors.Warning,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Android blocks sideloaded apps from turning on an Accessibility Service until " +
                    "you allow it once. If you see \"Restricted setting\", do this first:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            listOf(
                "Open App info below",
                "Tap ⋮ in the top-right corner",
                "Tap \"Allow restricted settings\"",
                "Come back and turn on Accessibility",
            ).forEachIndexed { index, line ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        "${index + 1}.",
                        style      = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color      = StatusColors.Warning,
                        modifier   = Modifier.width(20.dp),
                    )
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick  = onOpenAppInfo,
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
            ) {
                Text("Open App info", fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── required step card ─────────────────────────────────────────────────────────
@Composable
private fun RequiredStepCard(step: SetupStep, index: Int, total: Int) {
    val cardBg by animateColorAsState(
        targetValue   = if (step.isGranted) StatusColors.Success.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier         = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (step.isGranted) StatusColors.Success.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        step.icon,
                        contentDescription = null,
                        tint = if (step.isGranted) StatusColors.Success else MaterialTheme.colorScheme.primary,
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
                AnimatedContent(
                    targetState    = step.isGranted,
                    transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                    label          = "statusChip",
                ) { granted ->
                    StatusChip(granted)
                }
            }

            Spacer(Modifier.height(10.dp))

            Text(
                step.rationale,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(
                visible = !step.isGranted,
                enter   = expandVertically(tween(300)) + fadeIn(),
                exit    = shrinkVertically(tween(300)) + fadeOut(),
            ) {
                Column {
                    if (step.blockedReason != null) {
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                step.blockedReason,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (step.howTo.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "In the screen that opens:",
                            style      = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color      = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        step.howTo.forEachIndexed { i, line ->
                            Row(Modifier.padding(vertical = 1.dp)) {
                                Text(
                                    "${i + 1}.",
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(18.dp),
                                )
                                Text(
                                    line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick  = step.onClick,
                        enabled  = step.blockedReason == null,
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

// ── recommended step row ───────────────────────────────────────────────────────
@Composable
private fun RecommendedStepRow(step: SetupStep) {
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(enabled = !step.isGranted, onClick = step.onClick),
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (step.isGranted) StatusColors.Success.copy(alpha = 0.06f)
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                step.icon,
                contentDescription = null,
                tint = if (step.isGranted) StatusColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    step.title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    step.rationale,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(step.isGranted, pendingLabel = step.buttonLabel)
        }
    }
}

// ── status chip ────────────────────────────────────────────────────────────────
@Composable
private fun StatusChip(granted: Boolean, pendingLabel: String = "Pending") {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (granted) StatusColors.Success.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            if (granted) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = StatusColors.Success,
                    modifier = Modifier.height(12.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (granted) "On" else pendingLabel,
                style      = MaterialTheme.typography.labelSmall,
                color      = if (granted) StatusColors.Success else MaterialTheme.colorScheme.onTertiaryContainer,
                fontWeight = FontWeight.Bold,
            )
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
        colors = CardDefaults.cardColors(containerColor = StatusColors.Success.copy(alpha = 0.1f)),
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Celebration, contentDescription = null, tint = StatusColors.Success)
            Column {
                Text(
                    "Protection armed",
                    style      = MaterialTheme.typography.titleSmall,
                    color      = StatusColors.Success,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Locked apps will now be bounced. Use \"Remove protection\" at the top if " +
                        "you ever need to undo this.",
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

/**
 * Sends the user to this app's notification settings rather than firing the POST_NOTIFICATIONS
 * runtime request. After two dismissals Android stops showing that dialog entirely and the
 * request becomes a silent no-op — a button that visibly does nothing is worse than one extra
 * screen, and this route keeps working however many times it's been declined.
 */
private fun openNotificationSettings(context: Context) {
    // No SDK_INT guard: ACTION_APP_NOTIFICATION_SETTINGS landed in API 26, which is this app's
    // minSdk, so a version check here would be permanently true and its else branch dead code.
    // App info stays as a runtime fallback because some OEM builds don't resolve this action.
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(appInfoIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openUsageAccessSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        // Usage access isn't exposed as its own screen on every OEM build; App info is the one
        // place it can always be reached from.
        context.startActivity(appInfoIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openAppInfo(context: Context) {
    context.startActivity(appInfoIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun appInfoIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )
