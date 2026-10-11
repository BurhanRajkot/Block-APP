package com.blockapp.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Covers the durations actually reached for, so the common case is one tap instead of three
 * number fields. Custom stays available because sub-minute durations are the only practical way
 * to rehearse the expiry path on a real device (see the verification walkthrough in CLAUDE.md).
 * Shared by [AppPickerScreen] (one target) and [FocusModeScreen] (many targets at once) — the
 * choice of "how long" is identical either way.
 */
internal val LOCK_DURATION_PRESETS = listOf(
    "15m" to 15 * 60_000L,
    "30m" to 30 * 60_000L,
    "1h" to 60 * 60_000L,
    "2h" to 2 * 60 * 60_000L,
    "4h" to 4 * 60 * 60_000L,
    "8h" to 8 * 60 * 60_000L,
)

/** Resolves the picker's state into a single duration, in millis. */
internal fun resolveDurationMs(
    isCustom: Boolean,
    presetMs: Long?,
    hoursText: String,
    minutesText: String,
    secondsText: String,
): Long {
    if (!isCustom) return presetMs ?: 0L
    return (hoursText.toLongOrNull() ?: 0L) * 3_600_000L +
        (minutesText.toLongOrNull() ?: 0L) * 60_000L +
        (secondsText.toLongOrNull() ?: 0L) * 1_000L
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DurationPresetPicker(
    presetMs: Long?,
    isCustom: Boolean,
    onPresetSelected: (Long) -> Unit,
    onCustomSelected: () -> Unit,
    hoursText: String,
    onHoursChange: (String) -> Unit,
    minutesText: String,
    onMinutesChange: (String) -> Unit,
    secondsText: String,
    onSecondsChange: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LOCK_DURATION_PRESETS.forEach { (label, ms) ->
            DurationChip(label, selected = !isCustom && presetMs == ms) { onPresetSelected(ms) }
        }
        DurationChip("Custom", selected = isCustom, onClick = onCustomSelected)
    }

    if (isCustom) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            DurationField(hoursText, onHoursChange, "Hours", 3, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            DurationField(minutesText, onMinutesChange, "Min", 2, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            DurationField(secondsText, onSecondsChange, "Sec", 2, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DurationChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * The wall-clock unlock time, large, ahead of the lock button. Shared by AppPickerScreen and
 * FocusModeScreen so both spell out the consequence the same way before the confirm dialog.
 */
@Composable
internal fun UnlockPreview(blockUntil: Long) {
    Panel(color = MaterialTheme.colorScheme.primaryContainer) {
        Eyebrow("Unlocks at", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            formatUnlockAt(blockUntil),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "That's ${formatDuration(blockUntil - System.currentTimeMillis())} from now.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxLength)) },
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        // Done drops the number pad so the unlock time and the Lock button come back into view.
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier,
    )
}
