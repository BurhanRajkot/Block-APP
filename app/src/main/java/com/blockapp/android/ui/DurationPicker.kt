package com.blockapp.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LOCK_DURATION_PRESETS.forEach { (label, ms) ->
            FilterChip(
                selected = !isCustom && presetMs == ms,
                onClick = { onPresetSelected(ms) },
                label = { Text(label) },
            )
        }
        FilterChip(
            selected = isCustom,
            onClick = onCustomSelected,
            label = { Text("Custom") },
        )
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
private fun DurationField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter(Char::isDigit).take(maxLength)) },
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
