package com.blockapp.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.ui.theme.StatusColors
import com.blockapp.android.util.FocusModeSelection
import com.blockapp.android.util.InstalledAppsProvider
import com.blockapp.android.util.LaunchableApp
import kotlinx.coroutines.flow.collectLatest

/**
 * Locks a chosen set of apps for one duration. The starting set is whatever
 * [com.blockapp.android.util.FocusModeApps] flags as social/entertainment/games (WhatsApp carved
 * out), then the user can tick extras in or drop suggested ones before starting. Edits persist
 * via [FocusModeSelection] so a process kill doesn't restore the auto-detect list under them.
 *
 * Locking still goes through [com.blockapp.android.data.BlockRepository.lockApp] once per target,
 * so every existing guarantee applies unchanged: a shorter Focus Mode run on an already-locked
 * app only ever extends it, and [com.blockapp.android.util.ProtectedPackages] stays enforced as
 * a backstop because extras are chosen from [InstalledAppsProvider.listLaunchableApps].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusModeScreen(onBack: () -> Unit, onLocked: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    val allApps = remember { InstalledAppsProvider.listLaunchableApps(context) }

    var targets by remember { mutableStateOf(FocusModeSelection.resolve(context)) }
    var picking by remember { mutableStateOf(false) }

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

    fun refresh() {
        targets = FocusModeSelection.resolve(context)
    }

    if (picking) {
        BackHandler { picking = false }
        AddFocusAppList(
            candidates = allApps.filter { candidate ->
                targets.none { it.packageName == candidate.packageName }
            },
            onBack = { picking = false },
            onPick = { launchable ->
                FocusModeSelection.include(context, launchable.packageName)
                refresh()
                picking = false
            },
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Focus") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    "How long",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
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
            if (durationMs > 0L && targets.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(22.dp))
                    Text(
                        formatUnlockAt(blockUntil),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "${formatDuration(blockUntil - System.currentTimeMillis())} · " +
                            "${targets.size} app${if (targets.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Spacer(Modifier.height(22.dp))
                Button(
                    onClick = { confirming = true },
                    enabled = durationMs > 0L && targets.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                ) {
                    Text("Start focus", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Once it starts it can't be cut short from inside the app.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Spacer(Modifier.height(28.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Apps  ${targets.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { picking = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
                if (FocusModeSelection.hasCustomisation(context)) {
                    TextButton(
                        onClick = {
                            FocusModeSelection.resetToSuggested(context)
                            refresh()
                        },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text("Reset to suggested")
                    }
                }
                Text(
                    "Suggested social, games and entertainment — uncheck to leave one out, " +
                        "or add anything else before you start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (targets.isEmpty()) {
                item {
                    Text(
                        "Nothing selected. Add an app, or reset to the suggested set.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            } else {
                items(targets, key = { it.packageName }) { launchable ->
                    FocusAppRow(
                        launchable = launchable,
                        isLocked = launchable.packageName in activeLocks,
                        checked = true,
                        onToggle = {
                            FocusModeSelection.exclude(context, launchable.packageName)
                            refresh()
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFocusAppList(
    candidates: List<LaunchableApp>,
    onBack: () -> Unit,
    onPick: (LaunchableApp) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(candidates, query) {
        if (query.isBlank()) {
            candidates
        } else {
            candidates.filter { it.label.contains(query.trim(), ignoreCase = true) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Add an app") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (candidates.isEmpty()) {
                            "Every installed app is already in the list."
                        } else {
                            "No app matches \"$query\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    items(filtered, key = { it.packageName }) { launchable ->
                        val context = LocalContext.current
                        val identity = remember(launchable.packageName) {
                            loadAppIdentity(context, launchable.packageName)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(launchable) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(identity, size = 36)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                launchable.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusAppRow(
    launchable: LaunchableApp,
    isLocked: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val context = LocalContext.current
    val identity = remember(launchable.packageName) {
        loadAppIdentity(context, launchable.packageName)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
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
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
}

@Composable
private fun ConfirmFocusModeDialog(
    targets: List<LaunchableApp>,
    blockUntil: Long,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start focus?") },
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
