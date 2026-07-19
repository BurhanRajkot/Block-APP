package com.blockapp.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.util.InstalledAppsProvider
import com.blockapp.android.util.LaunchableApp

@Composable
fun AppPickerScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    val apps = remember { InstalledAppsProvider.listLaunchableApps(context) }
    var selected by remember { mutableStateOf<LaunchableApp?>(null) }
    var hoursText by remember { mutableStateOf("0") }
    var minutesText by remember { mutableStateOf("0") }
    var secondsText by remember { mutableStateOf("0") }

    val totalMillis = (hoursText.toLongOrNull() ?: 0L) * 3_600_000L +
        (minutesText.toLongOrNull() ?: 0L) * 60_000L +
        (secondsText.toLongOrNull() ?: 0L) * 1_000L

    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("Pick an app to lock", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))

            Box(Modifier.weight(1f)) {
                LazyColumn {
                    items(apps) { launchable ->
                        ListItem(
                            headlineContent = { Text(launchable.label) },
                            trailingContent = {
                                if (selected?.packageName == launchable.packageName) Text("Selected")
                            },
                            modifier = Modifier.clickable { selected = launchable },
                        )
                    }
                }
            }

            Text("Lock for", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = hoursText,
                    onValueChange = { hoursText = it.filter(Char::isDigit).take(3) },
                    label = { Text("Hours") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = minutesText,
                    onValueChange = { minutesText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = secondsText,
                    onValueChange = { secondsText = it.filter(Char::isDigit).take(2) },
                    label = { Text("Seconds") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val target = selected ?: return@Button
                    if (totalMillis <= 0L) return@Button
                    val blockUntil = System.currentTimeMillis() + totalMillis
                    app.repository.lockApp(target.packageName, target.label, blockUntil)
                    onDone()
                },
                enabled = selected != null && totalMillis > 0L,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Lock it")
            }
        }
    }
}
