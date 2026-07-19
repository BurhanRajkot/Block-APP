package com.blockapp.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.data.BlockedAppEntity
import kotlinx.coroutines.flow.collectLatest

private const val CONFIRM_PHRASE = "REMOVE"

/**
 * Deliberately the only sanctioned way to lift Device Admin protection. It calls
 * DevicePolicyManager.removeActiveAdmin() directly, in-process — which any admin app is always
 * allowed to do to itself — rather than sending the user to the guarded Settings screens (see
 * AppBlockAccessibilityService's Tier 1 doc). That keeps the app impossible to strip via a
 * few casual taps while guaranteeing the device owner always has a real, working way out.
 */
@Composable
fun RemoveProtectionScreen(onDone: () -> Unit, onEnterKey: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication

    var locks by remember { mutableStateOf<List<BlockedAppEntity>>(emptyList()) }
    var isAdminActive by remember { mutableStateOf(DeviceAdminHelper.isAdminActive(context)) }
    var confirmText by remember { mutableStateOf("") }
    var justRemoved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        app.repository.activeEntities.collectLatest { locks = it }
    }

    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("Remove protection", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            when {
                !isAdminActive -> {
                    Text(
                        if (justRemoved) {
                            "Device Admin has been removed."
                        } else {
                            "Device Admin is already off."
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Last step: open Accessibility settings and turn off this app's toggle. " +
                            "After that you can uninstall normally, the same way as any other app.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Open Accessibility settings") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to app")
                    }
                }

                locks.isNotEmpty() -> {
                    Text(
                        "You have ${locks.size} active lock${if (locks.size == 1) "" else "s"}. " +
                            "Protection can't be removed while a lock is running — that's the " +
                            "entire point of it. End your locks first, either by waiting them " +
                            "out or applying an unlock key, then come back here.",
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onEnterKey, modifier = Modifier.fillMaxWidth()) {
                        Text("Enter unlock key")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to app")
                    }
                }

                else -> {
                    Text(
                        "This turns off Device Admin so you can uninstall the app. It's " +
                            "deliberately not a quick action — that's what stops the app being " +
                            "removed by accident or on a whim.",
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        label = { Text("Type $CONFIRM_PHRASE to confirm") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            DeviceAdminHelper.removeAdmin(context)
                            isAdminActive = false
                            justRemoved = true
                        },
                        enabled = confirmText == CONFIRM_PHRASE,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Remove Device Admin") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
