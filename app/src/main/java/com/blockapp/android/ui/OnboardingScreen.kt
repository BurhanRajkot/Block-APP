package com.blockapp.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.admin.DeviceAdminHelper

@Composable
fun OnboardingScreen(onDone: () -> Unit, onRemoveProtection: () -> Unit) {
    val context = LocalContext.current

    var isAccessibilityActive by remember { mutableStateOf(false) }
    var isAdminActive by remember { mutableStateOf(false) }
    var isBatteryUnrestricted by remember { mutableStateOf(false) }

    // Re-check every time this screen resumes (e.g. coming back from Settings). A one-shot
    // check on first composition would go stale the moment the user leaves and returns,
    // since this composable stays alive in the background and never recomposes on its own.
    LifecycleResumeEffect(Unit) {
        isAccessibilityActive = DeviceAdminHelper.isAccessibilityActive(context)
        isAdminActive = DeviceAdminHelper.isAdminActive(context)
        isBatteryUnrestricted = DeviceAdminHelper.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose {}
    }

    val allGranted = isAccessibilityActive && isAdminActive && isBatteryUnrestricted

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Setup", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Privacy note: everything below runs entirely on this device. No usage data, " +
                    "screen content, or installed-app list is ever sent anywhere — these " +
                    "permissions are used only to detect when a locked app opens and to stop " +
                    "this app being casually removed while a lock is active.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(16.dp))

            PermissionItem(
                granted = isAccessibilityActive,
                title = "1. Accessibility Service",
                rationale = "Lets the app notice, on-device only, when a locked app comes to " +
                    "the foreground so it can send you back home. It does not read what you " +
                    "type or capture your screen — only which app is currently in front.",
                buttonLabel = "Open Accessibility settings",
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )

            Spacer(Modifier.height(16.dp))
            PermissionItem(
                granted = isAdminActive,
                title = "2. Device Admin",
                rationale = "Required so the app can't be casually uninstalled while a lock is " +
                    "active. Once this is on, use \"Remove protection\" below (not Settings) " +
                    "if you ever want to take it off.",
                buttonLabel = "Activate Device Admin",
                onClick = {
                    context.startActivity(
                        DeviceAdminHelper.requestAdminIntent(
                            context,
                            "Required so the app can't be casually uninstalled while a lock is active.",
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )

            Spacer(Modifier.height(16.dp))
            PermissionItem(
                granted = isBatteryUnrestricted,
                title = "3. Ignore battery optimizations",
                rationale = "Without this, some phone makers silently kill the accessibility " +
                    "service in the background, which is why blocking can stop working " +
                    "intermittently. This only affects background scheduling — no extra data " +
                    "or battery access beyond that.",
                buttonLabel = "Open battery settings",
                onClick = { requestIgnoreBatteryOptimizations(context) },
            )

            Spacer(Modifier.height(24.dp))
            if (allGranted) {
                Text(
                    "✅ Protection is fully armed. Accessibility and Device Admin settings are " +
                        "now guarded against tampering — use \"Remove protection\" below if you " +
                        "ever need to turn this off.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(16.dp))
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onRemoveProtection, modifier = Modifier.fillMaxWidth()) {
                Text("Remove protection & uninstall")
            }
        }
    }
}

@Composable
private fun PermissionItem(
    granted: Boolean,
    title: String,
    rationale: String,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (granted) "✅" else "⬜")
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(rationale, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        if (granted) {
            Text(
                "Granted",
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(buttonLabel) }
        }
    }
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    val directIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(directIntent)
    } catch (e: ActivityNotFoundException) {
        // A few OEM builds don't resolve the direct per-app request — fall back to the
        // general battery optimization list so the user can still find this app manually.
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
