package com.blockapp.android.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Same status red as the protection banner on OnboardingScreen — "needs attention" is a status,
// not a brand colour, so it stays literal rather than coming from the colour scheme.
private val NeedsAttention = Color(0xFFB71C1C)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddLock: () -> Unit,
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
                    TextButton(onClick = onEnterKey) { Text("🔑") }
                    TextButton(onClick = onSettings) { Text("⚙") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Lock an app") },
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
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
            .background(NeedsAttention)
            .padding(14.dp),
    ) {
        Text(
            "⚠️  Protection incomplete",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
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
                contentColor = NeedsAttention,
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
                if (identity.icon != null) {
                    Image(
                        bitmap = identity.icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("🔒")
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
            Text("🌱", style = MaterialTheme.typography.displaySmall)
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
