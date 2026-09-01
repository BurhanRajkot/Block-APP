package com.blockapp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.blockapp.android.BlockApplication
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.data.BlockedAppEntity
import com.blockapp.android.ui.theme.StatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddLock: () -> Unit,
    onFocusMode: () -> Unit,
    onEnterKey: () -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    var locks by remember { mutableStateOf<List<BlockedAppEntity>>(emptyList()) }
    var isAdminActive by remember { mutableStateOf(true) }
    var isAccessibilityActive by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        app.repository.activeEntities.collectLatest { locks = it }
    }

    // Ticks once a second so the "unlocks in" countdown below stays live instead of showing a
    // stale duration computed only when the lock list last changed.
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
        topBar = {
            TopAppBar(
                title = { Text("App Blocker", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onEnterKey) {
                        Icon(Icons.Filled.VpnKey, contentDescription = "Enter unlock key")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Lock an app") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = onAddLock,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (!isProtected) {
                Spacer(Modifier.height(8.dp))
                ProtectionBanner(
                    isAdminActive = isAdminActive,
                    isAccessibilityActive = isAccessibilityActive,
                    onFix = onSettings,
                )
            }

            Spacer(Modifier.height(16.dp))
            FocusModeCard(onClick = onFocusMode)

            Spacer(Modifier.height(20.dp))
            Text(
                if (locks.isEmpty()) "Active locks" else "Active locks · ${locks.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))

            if (locks.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // Clears the extended FAB, which floats over the list rather than reserving
                    // space for itself — without this the last row's countdown sits under it.
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(locks, key = { it.packageName }) { lock ->
                        LockCard(lock = lock, now = now)
                    }
                }
            }
        }
    }
}

// ── focus mode hero card ────────────────────────────────────────────────────────
/**
 * The front-page entry point for [FocusModeScreen] — locking a whole class of app (social,
 * entertainment, games) in one tap is the headline feature, so it sits above the active-locks
 * list rather than being buried in the app picker.
 */
@Composable
private fun FocusModeCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CenterFocusStrong,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Focus Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Lock social, games & entertainment in one tap",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

// ── protection banner ──────────────────────────────────────────────────────────
@Composable
private fun ProtectionBanner(
    isAdminActive: Boolean,
    isAccessibilityActive: Boolean,
    onFix: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(StatusColors.Danger)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(
                "Protection incomplete",
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        if (!isAccessibilityActive) {
            Text(
                "• Accessibility Service is off — locked apps can be opened.",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!isAdminActive) {
            Text(
                "• Device Admin is not active — the app can be uninstalled.",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onFix,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = StatusColors.Danger,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Finish setup", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── lock card ──────────────────────────────────────────────────────────────────
@Composable
private fun LockCard(lock: BlockedAppEntity, now: Long) {
    val context = LocalContext.current
    val identity = remember(lock.packageName) { loadAppIdentity(context, lock.packageName) }
    val remaining = (lock.blockUntil - now).coerceAtLeast(0L)
    val total = (lock.blockUntil - lock.blockedAt).coerceAtLeast(1L)
    val elapsedFraction = ((total - remaining).toFloat() / total).coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AppIcon(identity, size = 40)
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(StatusColors.Success),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        identity.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (remaining > 0L) {
                            "Unlocks at ${formatUnlockAt(lock.blockUntil)}"
                        } else {
                            "Unlocking…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (remaining > 0L) formatDuration(remaining) else "—",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { elapsedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(50)),
                trackColor = MaterialTheme.colorScheme.surface,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

// ── empty state ────────────────────────────────────────────────────────────────
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 48.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SelfImprovement,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Nothing locked right now",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Pick an app and a duration. Once it starts, it can't be cut short from inside " +
                    "the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
