package com.blockapp.android.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.usage.UsageStatsProvider

// Status colours, matching HomeScreen's banner and OnboardingScreen's cards.
private val GrantedGreen   = Color(0xFF2E7D32)
private val NeedsAttention = Color(0xFFB71C1C)

/**
 * The app's one hub for everything that isn't "lock an app": protection status, setup, screen
 * time, unlock keys and removal.
 *
 * Note for invariant 1 (the escape hatch must always work): this screen is one tap from
 * HomeScreen and "Remove protection" is a top-level row on it, so a user who has locked
 * themselves out of Settings' Accessibility and Device Admin screens still reaches
 * RemoveProtectionScreen in two taps from launch. Don't bury that row behind an expander.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onPermissions: () -> Unit,
    onScreenTime: () -> Unit,
    onEnterKey: () -> Unit,
    onRemoveProtection: () -> Unit,
) {
    val context = LocalContext.current

    var isAccessibilityActive  by remember { mutableStateOf(false) }
    var isAdminActive          by remember { mutableStateOf(false) }
    var isBatteryUnrestricted  by remember { mutableStateOf(false) }
    var canScheduleExactAlarms by remember { mutableStateOf(false) }
    var hasNotifications       by remember { mutableStateOf(false) }
    var hasUsageAccess         by remember { mutableStateOf(false) }
    var showInstallNotes       by remember { mutableStateOf(false) }

    // Every one of these can be revoked while the app sits in the background, so they're re-read
    // on resume rather than sampled once — same reason HomeScreen re-checks its banner state.
    LifecycleResumeEffect(Unit) {
        isAccessibilityActive  = DeviceAdminHelper.isAccessibilityActive(context)
        isAdminActive          = DeviceAdminHelper.isAdminActive(context)
        isBatteryUnrestricted  = DeviceAdminHelper.isIgnoringBatteryOptimizations(context)
        canScheduleExactAlarms = DeviceAdminHelper.canScheduleExactAlarms(context)
        hasNotifications       = DeviceAdminHelper.areNotificationsEnabled(context)
        hasUsageAccess         = UsageStatsProvider.hasUsageAccess(context)
        onPauseOrDispose {}
    }

    val isArmed = isAccessibilityActive && isAdminActive
    val extras = listOf(
        "Battery" to isBatteryUnrestricted,
        "Exact alarms" to canScheduleExactAlarms,
        "Notifications" to hasNotifications,
        "Usage access" to hasUsageAccess,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            ProtectionSummaryCard(
                isArmed               = isArmed,
                isAccessibilityActive = isAccessibilityActive,
                isAdminActive         = isAdminActive,
                extras                = extras,
                onOpenSetup           = onPermissions,
            )

            SettingsSection("Tools") {
                SettingsRow(
                    icon      = "📊",
                    title     = "Screen time",
                    subtitle  = if (hasUsageAccess) {
                        "Today's usage per app"
                    } else {
                        "Needs usage access — grant it in Setup"
                    },
                    onClick   = onScreenTime,
                )
                HorizontalDivider()
                SettingsRow(
                    icon     = "🔑",
                    title    = "Enter unlock key",
                    subtitle = "Apply a signed key to end or extend a lock early",
                    onClick  = onEnterKey,
                )
                HorizontalDivider()
                SettingsRow(
                    icon     = "🧩",
                    title    = "Permissions & setup",
                    subtitle = "Grant, re-check, or repair what protection needs",
                    onClick  = onPermissions,
                )
            }

            SettingsSection("Installing & updating") {
                SettingsRow(
                    icon     = "📦",
                    title    = "Sideload notes",
                    subtitle = "Play Protect and restricted settings, in order",
                    trailing = if (showInstallNotes) "▲" else "▼",
                    onClick  = { showInstallNotes = !showInstallNotes },
                )
                AnimatedVisibility(
                    visible = showInstallNotes,
                    enter   = expandVertically() + fadeIn(),
                    exit    = shrinkVertically() + fadeOut(),
                ) {
                    InstallNotes()
                }
            }

            SettingsSection("Protection") {
                SettingsRow(
                    icon      = "🚪",
                    title     = "Remove protection",
                    subtitle  = "Deactivate Device Admin so the app can be uninstalled",
                    titleTint = NeedsAttention,
                    onClick   = onRemoveProtection,
                )
            }

            AboutFooter(versionName = remember { versionName(context) })
        }
    }
}

// ── protection summary ─────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProtectionSummaryCard(
    isArmed: Boolean,
    isAccessibilityActive: Boolean,
    isAdminActive: Boolean,
    extras: List<Pair<String, Boolean>>,
    onOpenSetup: () -> Unit,
) {
    val accent = if (isArmed) GrantedGreen else NeedsAttention
    Card(
        modifier  = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable(onClick = onOpenSetup),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isArmed) "🛡️" else "⚠️",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isArmed) "Protection armed" else "Protection incomplete",
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color      = accent,
                    )
                    Text(
                        if (isArmed) {
                            "Locked apps get bounced, and the app can't be uninstalled mid-lock."
                        } else {
                            "Tap to finish setup — locks aren't fully enforced yet."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = accent)
            }

            Spacer(Modifier.height(12.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CapabilityPill("Accessibility", isAccessibilityActive, required = true)
                CapabilityPill("Device Admin", isAdminActive, required = true)
                extras.forEach { (label, granted) ->
                    CapabilityPill(label, granted, required = false)
                }
            }
        }
    }
}

/**
 * A missing *required* capability reads red because blocking genuinely doesn't work without it; a
 * missing extra reads neutral grey, not red, because locks still hold without it and colouring it
 * as a fault trains the user to ignore the ones that matter.
 */
@Composable
private fun CapabilityPill(label: String, granted: Boolean, required: Boolean) {
    val bg = when {
        granted  -> GrantedGreen.copy(alpha = 0.15f)
        required -> NeedsAttention.copy(alpha = 0.15f)
        else     -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        granted  -> GrantedGreen
        required -> NeedsAttention
        else     -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            "${if (granted) "✓" else "○"} $label",
            style      = MaterialTheme.typography.labelSmall,
            color      = fg,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ── sections & rows ────────────────────────────────────────────────────────────
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            title.uppercase(),
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.primary,
            modifier   = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Card(
            modifier  = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape     = RoundedCornerShape(16.dp),
            colors    = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String,
    trailing: String = "›",
    titleTint: Color? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color      = titleTint ?: MaterialTheme.colorScheme.onSurface,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            trailing,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── install notes ──────────────────────────────────────────────────────────────
/**
 * The two hurdles that only exist because this is sideloaded rather than installed from Play,
 * both of which happen before the app can do anything about them itself. Written down here
 * because they recur on every reinstall and every device, and the failure mode both times is a
 * silent one — Play Protect refuses with a vague warning, and the Accessibility toggle simply
 * does nothing.
 */
@Composable
private fun InstallNotes() {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        NoteBlock(
            heading = "1 · Play Protect blocks the install",
            lines = listOf(
                "It scans sideloaded APKs and warns or refuses, because this app isn't " +
                    "distributed through the Play Store.",
                "Play Store → your profile → Play Protect → ⚙ → turn off \"Scan apps with " +
                    "Play Protect\", install the APK, then turn it back on.",
                "It only scans at install time, so re-enabling it afterwards changes nothing " +
                    "about this app.",
            ),
        )
        Spacer(Modifier.height(12.dp))
        NoteBlock(
            heading = "2 · Restricted settings block Accessibility",
            lines = listOf(
                "On Android 13 and newer, a sideloaded app can't be given Accessibility until " +
                    "you allow it once. The toggle looks normal but does nothing.",
                "App info → ⋮ (top-right) → \"Allow restricted settings\", then go back and " +
                    "turn Accessibility on.",
                "Setup shows this as a reminder for as long as Accessibility is still off.",
            ),
        )
        Spacer(Modifier.height(12.dp))
        NoteBlock(
            heading = "Updating later",
            lines = listOf(
                "Installing a newer build over the top works with Device Admin still active — " +
                    "an update isn't an uninstall.",
                "Locks live in the app's database and survive the update.",
            ),
        )
    }
}

@Composable
private fun NoteBlock(heading: String, lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(12.dp),
    ) {
        Text(
            heading,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        lines.forEach { line ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    "•",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(14.dp),
                )
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── about ──────────────────────────────────────────────────────────────────────
@Composable
private fun AboutFooter(versionName: String) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "App Blocker $versionName",
            style      = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // Deliberately not overstated: the README and the code comments make the same claim, and
        // promising more than "hard to do on impulse" here would be a lie the app can't keep.
        Text(
            "A deterrent, not a cage. Accessibility can still be disabled from Settings, and " +
                "Safe Mode turns off every third-party accessibility service — this makes " +
                "unlocking on impulse meaningfully harder, not impossible.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "No network permission is declared. Unlock keys are verified offline, on-device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun versionName(context: Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?.let { "v$it" }
            .orEmpty()
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }
