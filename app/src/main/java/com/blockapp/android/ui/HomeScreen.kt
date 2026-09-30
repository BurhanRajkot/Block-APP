package com.blockapp.android.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.BlockApplication
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.data.BlockedAppEntity
import com.blockapp.android.data.ReelCountEntity
import com.blockapp.android.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddLock: () -> Unit,
    onFocusMode: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    var locks by remember { mutableStateOf<List<BlockedAppEntity>>(emptyList()) }
    var isAdminActive by remember { mutableStateOf(true) }
    var isAccessibilityActive by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var reelCounts by remember { mutableStateOf<List<ReelCountEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        app.repository.activeEntities.collectLatest { locks = it }
    }

    // Keyed on the date, derived from the 1s ticker below. Without that, a screen left open past
    // midnight would keep showing yesterday's total. LocalDate compares by value, so the
    // collector only restarts when the day actually changes, not every tick.
    val today = remember(now) { LocalDate.now() }
    LaunchedEffect(today) {
        app.repository.observeReelCounts(today).collectLatest { reelCounts = it }
    }

    // Ticks once a second so the remaining-time figure stays live instead of showing a stale
    // duration computed only when the lock list last changed.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            now = System.currentTimeMillis()
        }
    }

    // Re-check protection status every time this screen resumes (e.g. after returning from
    // Settings). This composable stays alive in the background while the user is off in
    // Settings, so a one-shot LaunchedEffect(Unit) would never see the updated state —
    // ON_RESUME is what actually fires when the user comes back.
    LifecycleResumeEffect(Unit) {
        isAdminActive = DeviceAdminHelper.isAdminActive(context)
        isAccessibilityActive = DeviceAdminHelper.isAccessibilityActive(context)
        // One-shot sweep, not a poll: catches a lock whose expiry alarm was missed (see
        // BlockApplication's cold-start sweep for why that can happen) every time the user
        // looks at this screen, at zero background cost since it only runs on resume.
        app.applicationScope.launch { app.repository.expireAllDue() }
        onPauseOrDispose {}
    }

    val isProtected = isAdminActive && isAccessibilityActive

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Block") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            ReelCounter(counts = reelCounts, isCounting = isAccessibilityActive)
            Spacer(Modifier.height(16.dp))

            if (!isProtected) {
                ProtectionBanner(
                    isAdminActive = isAdminActive,
                    isAccessibilityActive = isAccessibilityActive,
                    onFix = onSettings,
                )
                Spacer(Modifier.height(20.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }

            Text(
                "Quiet the usual suspects, or lock one app by name.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onFocusMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("Focus mode", fontWeight = FontWeight.SemiBold)
            }
            TextButton(
                onClick = onAddLock,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Lock one app")
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))
            Text(
                if (locks.isEmpty()) "Nothing locked" else "${locks.size} locked",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (locks.isEmpty()) {
                Text(
                    "Pick a duration. After it starts, it runs until the time you chose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(locks, key = { it.packageName }) { lock ->
                        LockRow(lock = lock, now = now)
                    }
                }
            }
        }
    }
}

/**
 * Today's short-form video total, fed by ReelScrollDetector through the accessibility service,
 * with the top few apps underneath so it's clear where the number came from. Kept visible at
 * zero rather than hidden, because the point is to see the number before opening a feed.
 */
@Composable
private fun ReelCounter(counts: List<ReelCountEntity>, isCounting: Boolean) {
    val total = counts.sumOf { it.reels }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            "REELS WATCHED TODAY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "$total",
            style = MaterialTheme.typography.displaySmall,
            color = if (total > 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        val note = when {
            // Mirrors the SDK gate in AppBlockAccessibilityService.onServiceConnected.
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P ->
                "Counting needs Android 9 or newer."
            !isCounting -> "Counting is paused while Accessibility is off."
            total == 0 -> "Swipe-through videos in any app — Reels, Shorts, TikTok — add up here."
            else -> null
        }
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (counts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                counts.take(MAX_REEL_APPS_SHOWN).forEach { entry ->
                    ReelAppCount(entry)
                }
            }
        }
    }
}

@Composable
private fun ReelAppCount(entry: ReelCountEntity) {
    val context = LocalContext.current
    val identity = remember(entry.packageName) { loadAppIdentity(context, entry.packageName) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppIcon(identity, size = 20)
        Spacer(Modifier.width(6.dp))
        Text(
            "${entry.reels}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProtectionBanner(
    isAdminActive: Boolean,
    isAccessibilityActive: Boolean,
    onFix: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StatusColors.Danger)
            .padding(16.dp)
            .clickable(onClick = onFix),
    ) {
        Text(
            "Not armed",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        if (!isAccessibilityActive) {
            Text(
                "Accessibility is off — locked apps can still be opened.",
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!isAdminActive) {
            Text(
                "Device Admin is off — the app can be uninstalled.",
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Finish setup  →",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LockRow(lock: BlockedAppEntity, now: Long) {
    val context = LocalContext.current
    val identity = remember(lock.packageName) { loadAppIdentity(context, lock.packageName) }
    val remaining = (lock.blockUntil - now).coerceAtLeast(0L)
    val total = (lock.blockUntil - lock.blockedAt).coerceAtLeast(1L)
    val elapsedFraction = ((total - remaining).toFloat() / total).coerceIn(0f, 1f)

    Column(Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(identity, size = 40)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    identity.label,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (remaining > 0L) {
                        "until ${formatUnlockAt(lock.blockUntil)}"
                    } else {
                        "Unlocking…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (remaining > 0L) formatDuration(remaining) else "—",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { elapsedFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Butt,
        )
    }
}

/**
 * Per-app chips under the reel total. More than four stops fitting one row at phone width, and
 * the long tail of apps with a reel or two tells the user nothing.
 */
private const val MAX_REEL_APPS_SHOWN = 4
