package com.blockapp.android.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.ui.theme.StatusColors
import com.blockapp.android.util.FocusModeApps
import com.blockapp.android.util.LaunchableApp
import kotlinx.coroutines.flow.collectLatest

/**
 * One-tap variant of [AppPickerScreen]: instead of choosing one app, this locks every installed
 * app [FocusModeApps] flags as social media, entertainment, or a game — with WhatsApp carved out
 * — for a single chosen duration. Locking still goes through [com.blockapp.android.data
 * .BlockRepository.lockApp] once per target, so every existing guarantee applies unchanged: a
 * shorter Focus Mode run on an already-locked app only ever extends it, and
 * [com.blockapp.android.util.ProtectedPackages] stays enforced as a backstop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusModeScreen(onBack: () -> Unit, onLocked: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication

    val targets = remember { FocusModeApps.findTargets(context) }

    // Same reasoning as AppPickerScreen: a lock can expire or be applied elsewhere while this
    // screen is open, and the confirm dialog's "already locked" framing should reflect that.
    var activeLocks by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) {
        app.repository.activeLocks.collectLatest { activeLocks = it }
    }

    var presetMs by remember { mutableStateOf<Long?>(null) }
    var isCustom by remember { mutableStateOf(false) }
    var hoursText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var secondsText by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    val durationMs = resolveDurationMs(isCustom, presetMs, hoursText, minutesText, secondsText)
    val blockUntil = System.currentTimeMillis() + durationMs

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Focus Mode", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (targets.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                NoTargetsState()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                item { SummaryCard(count = targets.size) }
                item {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "LOCK FOR",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    DurationPresetPicker(
                        presetMs = presetMs,
                        isCustom = isCustom,
                        onPresetSelected = { isCustom = false; presetMs = it },
                        onCustomSelected = { isCustom = true; presetMs = null },
                        hoursText = hoursText,
                        onHoursChange = { hoursText = it },
                        minutesText = minutesText,
                        onMinutesChange = { minutesText = it },
                        secondsText = secondsText,
                        onSecondsChange = { secondsText = it },
                    )
                }
                if (durationMs > 0L) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                .padding(16.dp),
                        ) {
                            Text(
                                "Everything unlocks at",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatUnlockAt(blockUntil),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "That's ${formatDuration(blockUntil - System.currentTimeMillis())} " +
                                    "from now, for ${targets.size} " +
                                    "app${if (targets.size == 1) "" else "s"}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { confirming = true },
                        enabled = durationMs > 0L,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.CenterFocusStrong, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Focus Mode", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Once it starts there is no in-app way to end it early — only an unlock " +
                            "key generated on your PC can cut it short.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "APPS INCLUDED · ${targets.size}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(targets, key = { it.packageName }) { launchable ->
                    AppPreviewRow(launchable, isLocked = launchable.packageName in activeLocks)
                    Spacer(Modifier.height(6.dp))
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    if (confirming) {
        ConfirmFocusModeDialog(
            targets = targets,
            blockUntil = blockUntil,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                targets.forEach { app.repository.lockApp(it.packageName, it.label, blockUntil) }
                onLocked()
            },
        )
    }
}

// ── summary ────────────────────────────────────────────────────────────────────
@Composable
private fun SummaryCard(count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
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
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "$count app${if (count == 1) "" else "s"} will be locked",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Social, entertainment & games — WhatsApp stays reachable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun NoTargetsState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 32.dp),
    ) {
        Icon(
            Icons.Filled.SelfImprovement,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Nothing to focus away from",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "No installed app looks like social media, entertainment, or a game right now.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AppPreviewRow(launchable: LaunchableApp, isLocked: Boolean) {
    val context = LocalContext.current
    val identity = remember(launchable.packageName) {
        loadAppIdentity(context, launchable.packageName)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(identity, size = 36)
        Spacer(Modifier.width(12.dp))
        Text(
            launchable.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isLocked) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Already locked",
                tint = StatusColors.Success,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Mirrors AppPickerScreen's ConfirmLockDialog, but names how many apps rather than one. */
@Composable
private fun ConfirmFocusModeDialog(
    targets: List<LaunchableApp>,
    blockUntil: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start Focus Mode?") },
        text = {
            Text(
                "${previewNames(targets)} will be blocked until ${formatUnlockAt(blockUntil)} — " +
                    "${formatDuration(blockUntil - System.currentTimeMillis())} from now.\n\n" +
                    "You won't be able to shorten or cancel this from inside the app.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Start", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun previewNames(targets: List<LaunchableApp>): String {
    val shown = targets.take(3).joinToString(", ") { it.label }
    val remaining = targets.size - 3
    return if (remaining > 0) "$shown, and $remaining more app${if (remaining == 1) "" else "s"}" else shown
}
