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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.blockapp.android.util.InstalledAppsProvider
import com.blockapp.android.util.LaunchableApp
import kotlinx.coroutines.flow.collectLatest

/**
 * Two steps in one composable: pick an app, then choose how long. Split by the local [selected]
 * state rather than by a navigation entry, matching how the rest of the app avoids a real back
 * stack — [onBack] walks back a step first and only leaves the screen from the list.
 *
 * [initialPackage] opens straight on the duration step for that app (the lock buttons on usage
 * rows). Back from there leaves the screen, since the user never saw the list. A package that
 * isn't launchable, or is protected, isn't in [InstalledAppsProvider.listLaunchableApps] and
 * falls back to the list, so this entry point can't get around ProtectedPackages either.
 *
 * The duration step is where the commitment is actually made, so it is deliberately the slower
 * half: it names the wall-clock unlock time before the confirm dialog, and again inside it.
 * "2h" is easy to pick without registering what it means; "unlocks at 11:47 PM" isn't.
 */
@Composable
fun AppPickerScreen(
    initialPackage: String? = null,
    onBack: () -> Unit,
    onLocked: (LockedSummary) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication

    val apps = remember { InstalledAppsProvider.listLaunchableApps(context) }
    var query by remember { mutableStateOf("") }
    var selected by remember {
        mutableStateOf(initialPackage?.let { pkg -> apps.firstOrNull { it.packageName == pkg } })
    }
    val openedOnApp = remember { selected != null }

    // Drives the "Locked" chip in the list and the extend-vs-lock wording on the duration step.
    // Observed rather than sampled once: a lock can expire while this screen is open, and a stale
    // "Locked" chip would send the user to a duration step whose copy talks about extending a
    // lock that no longer exists.
    var activeLocks by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) {
        app.repository.activeLocks.collectLatest { activeLocks = it }
    }

    var confirmed by remember { mutableStateOf(false) }
    var frozenUntil by remember { mutableStateOf<Long?>(null) }

    val target = selected
    if (target == null) {
        AppList(
            apps        = apps,
            query       = query,
            onQuery     = { query = it },
            activeLocks = activeLocks,
            onBack      = onBack,
            onSelect    = { selected = it },
        )
    } else {
        DurationStep(
            target        = target,
            // Frozen at confirm: the screen stays composed while it slides away, and without
            // this the lock it just created flips its title to "Extend the lock" mid-exit.
            existingUntil = if (confirmed) frozenUntil else activeLocks[target.packageName],
            onBack        = { if (openedOnApp) onBack() else selected = null },
            onConfirm     = { blockUntil ->
                frozenUntil = activeLocks[target.packageName]
                confirmed = true
                app.repository.lockApp(target.packageName, target.label, blockUntil)
                onLocked(LockedSummary(target.label, blockUntil, listOf(target.packageName)))
            },
        )
    }
}

// ── step 1: pick an app ────────────────────────────────────────────────────────
@Composable
private fun AppList(
    apps: List<LaunchableApp>,
    query: String,
    onQuery: (String) -> Unit,
    activeLocks: Map<String, Long>,
    onBack: () -> Unit,
    onSelect: (LaunchableApp) -> Unit,
) {
    val filtered = remember(apps, query) {
        if (query.isBlank()) {
            apps
        } else {
            apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title    = "Lock an app",
            onBack   = onBack,
            subtitle = "Pick the one you keep reaching for.",
        )
        SearchField(
            value         = query,
            onValueChange = onQuery,
            placeholder   = "Search apps",
            modifier      = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No app matches \"$query\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(filtered, key = { it.packageName }) { launchable ->
                    AppRow(
                        launchable = launchable,
                        isLocked   = launchable.packageName in activeLocks,
                        onClick    = { onSelect(launchable) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(launchable: LaunchableApp, isLocked: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val identity = remember(launchable.packageName) {
        loadAppIdentity(context, launchable.packageName)
    }

    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(identity, size = 44)
        Spacer(Modifier.width(14.dp))
        Text(
            launchable.label,
            style      = MaterialTheme.typography.titleSmall,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f),
        )
        if (isLocked) {
            Spacer(Modifier.width(8.dp))
            StatusPill("Locked", MaterialTheme.colorScheme.primary)
        }
    }
}

// ── step 2: choose a duration ──────────────────────────────────────────────────
@Composable
private fun DurationStep(
    target: LaunchableApp,
    existingUntil: Long?,
    onBack: () -> Unit,
    onConfirm: (blockUntil: Long) -> Unit,
) {
    val context = LocalContext.current
    val identity = remember(target.packageName) { loadAppIdentity(context, target.packageName) }

    var presetMs by remember { mutableStateOf<Long?>(null) }
    var isCustom by remember { mutableStateOf(false) }
    var hoursText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var secondsText by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }

    // Registered after MainActivity's dispatcher-level handler, so it wins while this step is
    // composed: without it, system back would skip the app list entirely and drop the user on
    // Home, undoing their selection with no way back short of starting over.
    BackHandler { onBack() }

    val durationMs = resolveDurationMs(isCustom, presetMs, hoursText, minutesText, secondsText)

    // An already-locked app counts its new duration from the current unlock time, not from now.
    // Counting from now would let "1h" on a lock with 2h left resolve to no change at all
    // (BlockRepository.lockApp only ever extends), so the button would look like it did nothing.
    val base = existingUntil ?: System.currentTimeMillis()
    val blockUntil = base + durationMs
    val isExtending = existingUntil != null

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title  = if (isExtending) "Extend the lock" else "Lock for how long?",
            onBack = onBack,
        )
        Column(Modifier.padding(horizontal = ScreenPadding)) {
            // ── what's being locked ───────────────────────────────────────────
            Panel(contentPadding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(identity, size = 48)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(identity.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (existingUntil != null) {
                                "Locked until ${formatUnlockAt(existingUntil)}"
                            } else {
                                "Not locked"
                            },
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Eyebrow(if (isExtending) "Add to the lock" else "Lock for")
            Spacer(Modifier.height(12.dp))

            DurationPresetPicker(
                presetMs         = presetMs,
                isCustom         = isCustom,
                onPresetSelected = { isCustom = false; presetMs = it },
                onCustomSelected = { isCustom = true; presetMs = null },
                hoursText        = hoursText,
                onHoursChange    = { hoursText = it },
                minutesText      = minutesText,
                onMinutesChange  = { minutesText = it },
                secondsText      = secondsText,
                onSecondsChange  = { secondsText = it },
            )

            Spacer(Modifier.height(24.dp))

            // The consequence, spelled out before the button rather than only in the dialog.
            if (durationMs > 0L) {
                UnlockPreview(blockUntil)
                Spacer(Modifier.height(16.dp))
            }

            PrimaryButton(
                text     = if (isExtending) "Extend lock" else "Lock ${identity.label}",
                onClick  = { confirming = true },
                enabled  = durationMs > 0L,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "Once it starts there is no in-app way to end it early.",
                style     = MaterialTheme.typography.bodySmall,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier  = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirming) {
        ConfirmLockDialog(
            label       = identity.label,
            blockUntil  = blockUntil,
            isExtending = isExtending,
            onDismiss   = { confirming = false },
            onConfirm   = {
                confirming = false
                onConfirm(blockUntil)
            },
        )
    }
}

/**
 * The last point at which this is reversible, so it restates the unlock time rather than asking
 * a bare "are you sure?". A mistyped custom duration is not a small mistake here — there is no
 * in-app undo, and the only remedy is minting a key on the dev machine.
 */
@Composable
private fun ConfirmLockDialog(
    label: String,
    blockUntil: Long,
    isExtending: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(if (isExtending) "Extend this lock?" else "Lock $label?") },
        text             = {
            Text(
                "$label will be blocked until ${formatUnlockAt(blockUntil)} — " +
                    "${formatDuration(blockUntil - System.currentTimeMillis())} from now.\n\n" +
                    "You won't be able to shorten or cancel this from inside the app.",
            )
        },
        confirmButton    = {
            TextButton(onClick = onConfirm) {
                Text(if (isExtending) "Extend" else "Lock it", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
