package com.blockapp.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.util.InstalledAppsProvider
import com.blockapp.android.util.LaunchableApp
import kotlinx.coroutines.flow.collectLatest

// Same status green as OnboardingScreen and SettingsScreen — a running lock is a "this is on"
// state, so it reads with the same colour as a granted permission rather than as a warning.
private val LockedGreen = Color(0xFF2E7D32)

/**
 * Two steps in one composable: pick an app, then choose how long. Split by the local [selected]
 * state rather than by a navigation entry, matching how the rest of the app avoids a real back
 * stack — [onBack] walks back a step first and only leaves the screen from the list.
 *
 * The duration step is where the commitment is actually made, so it is deliberately the slower
 * half: it names the wall-clock unlock time before the confirm dialog, and again inside it.
 * "2h" is easy to pick without registering what it means; "unlocks at 11:47 PM" isn't.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(onBack: () -> Unit, onLocked: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication

    val apps = remember { InstalledAppsProvider.listLaunchableApps(context) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }

    // Drives the "Locked" chip in the list and the extend-vs-lock wording on the duration step.
    // Observed rather than sampled once: a lock can expire while this screen is open, and a stale
    // "Locked" chip would send the user to a duration step whose copy talks about extending a
    // lock that no longer exists.
    var activeLocks by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) {
        app.repository.activeLocks.collectLatest { activeLocks = it }
    }

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
            existingUntil = activeLocks[target.packageName],
            onBack        = { selected = null },
            onConfirm     = { blockUntil ->
                app.repository.lockApp(target.packageName, target.label, blockUntil)
                onLocked()
            },
        )
    }
}

// ── step 1: pick an app ────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Lock an app", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value        = query,
                onValueChange = onQuery,
                label        = { Text("Search apps") },
                singleLine   = true,
                shape        = RoundedCornerShape(12.dp),
                modifier     = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
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
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(identity, size = 40)
        Spacer(Modifier.width(12.dp))
        Text(
            launchable.label,
            style      = MaterialTheme.typography.bodyLarge,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f),
        )
        if (isLocked) {
            Spacer(Modifier.width(8.dp))
            Surface(shape = RoundedCornerShape(50), color = LockedGreen.copy(alpha = 0.15f)) {
                Text(
                    "🔒 Locked",
                    style      = MaterialTheme.typography.labelSmall,
                    color      = LockedGreen,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ── step 2: choose a duration ──────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

    val customMs = (hoursText.toLongOrNull() ?: 0L) * 3_600_000L +
        (minutesText.toLongOrNull() ?: 0L) * 60_000L +
        (secondsText.toLongOrNull() ?: 0L) * 1_000L
    val durationMs = if (isCustom) customMs else (presetMs ?: 0L)

    // An already-locked app counts its new duration from the current unlock time, not from now.
    // Counting from now would let "1h" on a lock with 2h left resolve to no change at all
    // (BlockRepository.lockApp only ever extends), so the button would look like it did nothing.
    val base = existingUntil ?: System.currentTimeMillis()
    val blockUntil = base + durationMs
    val isExtending = existingUntil != null

    Scaffold(
        topBar = {
            TopAppBar(
                title          = {
                    Text(
                        if (isExtending) "Extend lock" else "Lock for how long?",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ── what's being locked ───────────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier          = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(identity, size = 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            identity.label,
                            style      = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (existingUntil != null) {
                                "Locked until ${formatUnlockAt(existingUntil)}"
                            } else {
                                target.packageName
                            },
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                if (isExtending) "ADD TO THE LOCK" else "LOCK FOR",
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PRESETS.forEach { (label, ms) ->
                    FilterChip(
                        selected = !isCustom && presetMs == ms,
                        onClick  = {
                            isCustom = false
                            presetMs = ms
                        },
                        label    = { Text(label) },
                    )
                }
                FilterChip(
                    selected = isCustom,
                    onClick  = {
                        isCustom = true
                        presetMs = null
                    },
                    label    = { Text("Custom") },
                )
            }

            if (isCustom) {
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth()) {
                    DurationField(hoursText, { hoursText = it }, "Hours", 3, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DurationField(minutesText, { minutesText = it }, "Min", 2, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    DurationField(secondsText, { secondsText = it }, "Sec", 2, Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(20.dp))

            // The consequence, spelled out before the button rather than only in the dialog.
            if (durationMs > 0L) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        .padding(16.dp),
                ) {
                    Text(
                        "Unlocks at",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatUnlockAt(blockUntil),
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "That's ${formatDuration(blockUntil - System.currentTimeMillis())} from now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            Button(
                onClick  = { confirming = true },
                enabled  = durationMs > 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(14.dp),
            ) {
                Text(
                    if (isExtending) "Extend lock" else "Lock ${identity.label}",
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Once it starts there is no in-app way to end it early — only an unlock key " +
                    "generated on your PC can cut it short.",
                style     = MaterialTheme.typography.labelSmall,
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

@Composable
private fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value           = value,
        onValueChange   = { onValueChange(it.filter(Char::isDigit).take(maxLength)) },
        label           = { Text(label) },
        singleLine      = true,
        shape           = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier        = modifier,
    )
}

@Composable
private fun AppIcon(identity: AppIdentity, size: Int) {
    if (identity.icon != null) {
        Image(
            bitmap             = identity.icon,
            contentDescription = null,
            modifier           = Modifier
                .size(size.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier         = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("📱")
        }
    }
}

/**
 * Covers the durations actually reached for, so the common case is one tap instead of three
 * number fields. Custom stays available because sub-minute durations are the only practical way
 * to rehearse the expiry path on a real device (see the verification walkthrough in CLAUDE.md).
 */
private val PRESETS = listOf(
    "15m" to 15 * 60_000L,
    "30m" to 30 * 60_000L,
    "1h" to 60 * 60_000L,
    "2h" to 2 * 60 * 60_000L,
    "4h" to 4 * 60 * 60_000L,
    "8h" to 8 * 60 * 60_000L,
)
