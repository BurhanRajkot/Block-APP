package com.blockapp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.BlockApplication
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.data.BlockedAppEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun HomeScreen(onAddLock: () -> Unit, onEnterKey: () -> Unit, onOnboarding: () -> Unit) {
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
        onPauseOrDispose {}
    }

    val isProtected = isAdminActive && isAccessibilityActive

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddLock) { Text("+") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {

            // ── Protection status banner ────────────────────────────────────────────
            if (!isProtected) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = Color(0xFFB71C1C),
                            shape = MaterialTheme.shapes.medium,
                        )
                        .padding(12.dp),
                ) {
                    Text(
                        "⚠️ Protection Incomplete",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!isAdminActive) {
                        Text(
                            "• Device Admin is not active — the app can be uninstalled.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!isAccessibilityActive) {
                        Text(
                            "• Accessibility Service is off — locked apps can be opened.",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onOnboarding,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Fix now", color = Color(0xFFB71C1C))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            // ────────────────────────────────────────────────────────────────────────

            Text("Active locks", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            if (locks.isEmpty()) {
                Text("Nothing locked right now.")
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(locks) { lock ->
                        ListItem(
                            headlineContent = { Text(lock.appLabel) },
                            supportingContent = {
                                val remaining = (lock.blockUntil - now).coerceAtLeast(0L)
                                Text(
                                    if (remaining > 0L) {
                                        "Unlocks in ${formatDuration(remaining)}"
                                    } else {
                                        "Unlocking…"
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onEnterKey, modifier = Modifier.fillMaxWidth()) {
                Text("Enter unlock key")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOnboarding, modifier = Modifier.fillMaxWidth()) {
                Text("Permissions setup")
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm %02ds".format(hours, minutes, seconds)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}
