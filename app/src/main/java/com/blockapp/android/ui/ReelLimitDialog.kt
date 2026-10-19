package com.blockapp.android.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.ui.theme.Ink950
import com.blockapp.android.util.ReelLimit
import com.blockapp.android.util.ReelPlan

/**
 * Picks the daily reel limit (util/ReelLimit). The rule that lowering applies now and raising
 * waits for tomorrow is spelled out before the choice is made rather than discovered after,
 * and the outcome is confirmed in a toast either way. [onDone] fires after any change so the
 * caller can re-read the limit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReelLimitDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val current = remember { ReelLimit.todayLimit(context) }
    val pending = remember { ReelLimit.pending(context) }

    // null = off. The custom field holds whatever number isn't a preset.
    var choice by remember { mutableStateOf(current) }
    var custom by remember {
        mutableStateOf(current?.takeIf { it != 0 && it !in ReelLimit.PRESETS }?.toString().orEmpty())
    }
    var isCustom by remember {
        mutableStateOf(current != null && current != 0 && current !in ReelLimit.PRESETS)
    }
    val chosen: Int? = if (isCustom) custom.toIntOrNull()?.takeIf { it >= 0 } else choice
    val valid = !isCustom || chosen != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily reel limit") },
        text = {
            Column {
                Text(
                    "When you reach it, reel viewers in every app close and stay closed until " +
                        "midnight. The rest of each app keeps working.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LimitChip("Off", selected = !isCustom && choice == null) {
                        isCustom = false
                        choice = null
                    }
                    LimitChip("None", selected = !isCustom && choice == 0) {
                        isCustom = false
                        choice = 0
                    }
                    ReelLimit.PRESETS.forEach { preset ->
                        LimitChip("$preset", selected = !isCustom && choice == preset) {
                            isCustom = false
                            choice = preset
                        }
                    }
                    LimitChip("Custom", selected = isCustom) { isCustom = true }
                }
                if (isCustom) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it.filter(Char::isDigit).take(4) },
                        label = { Text("Reels a day") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Lowering it applies right away. Raising it or turning it off only applies " +
                        "from tomorrow, so it can't be lifted on impulse.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ReelPlan.current(context) != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your reel plan lowers this each week. A higher limit set here is undone " +
                            "by the plan's next step, so stop the plan first to raise it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pending != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Already set for tomorrow: ${pending.limit?.let { "$it a day" } ?: "off"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ember,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val message = when (ReelLimit.set(context, chosen)) {
                        ReelLimit.Change.APPLIED_NOW ->
                            if (chosen == 0) "Reels are off from now on" else "Limit is now $chosen reels a day"
                        ReelLimit.Change.FROM_TOMORROW ->
                            if (chosen == null) "Limit turns off tomorrow" else "Limit becomes $chosen from tomorrow"
                        ReelLimit.Change.UNCHANGED -> null
                    }
                    message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    onDone()
                },
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LimitChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Ember else MaterialTheme.colorScheme.outline)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Ink950 else MaterialTheme.colorScheme.onSurface,
        )
    }
}
