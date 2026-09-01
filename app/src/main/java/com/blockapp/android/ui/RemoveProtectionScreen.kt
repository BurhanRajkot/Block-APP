package com.blockapp.android.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.data.BlockedAppEntity
import com.blockapp.android.ui.theme.StatusColors
import kotlinx.coroutines.flow.collectLatest

private const val CONFIRM_PHRASE = "REMOVE"

/**
 * Deliberately the only sanctioned way to lift Device Admin protection. It calls
 * DevicePolicyManager.removeActiveAdmin() directly, in-process — which any admin app is always
 * allowed to do to itself — rather than sending the user to the guarded Settings screens (see
 * AppBlockAccessibilityService's Tier 1 doc). That keeps the app impossible to strip via a
 * few casual taps while guaranteeing the device owner always has a real, working way out.
 *
 * Invariant 1 lives here: while Tier 1 is armed this screen is the user's only route back to
 * Accessibility and Device Admin settings. Every branch below therefore ends in a button that
 * leaves the screen, and the removal path stays a plain in-process call with nothing between the
 * confirm and [DeviceAdminHelper.removeAdmin] that could throw. Presentation is free to change;
 * that structure is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Remove protection", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
            when {
                !isAdminActive -> {
                    StatusCard(
                        tone  = StatusColors.Success,
                        icon  = if (justRemoved) Icons.Filled.CheckCircle else Icons.Filled.Info,
                        title = if (justRemoved) {
                            "Device Admin has been removed."
                        } else {
                            "Device Admin is already off."
                        },
                        body  = "Last step: open Accessibility settings and turn off this app's " +
                            "toggle. After that you can uninstall normally, the same way as any " +
                            "other app.",
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("Open Accessibility settings", fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick  = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(14.dp),
                    ) {
                        Text("Back to app")
                    }
                }

                locks.isNotEmpty() -> {
                    StatusCard(
                        tone  = StatusColors.Warning,
                        icon  = Icons.Filled.Lock,
                        title = "You have ${locks.size} active " +
                            "lock${if (locks.size == 1) "" else "s"}.",
                        body  = "Protection can't be removed while a lock is running — that's " +
                            "the entire point of it. End your locks first, either by waiting " +
                            "them out or applying an unlock key, then come back here.",
                    )
                    Spacer(Modifier.height(12.dp))
                    locks.forEach { lock ->
                        LockLine(lock)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick  = onEnterKey,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                    ) { Text("Enter unlock key", fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick  = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(14.dp),
                    ) {
                        Text("Back to app")
                    }
                }

                else -> {
                    StatusCard(
                        tone  = StatusColors.Danger,
                        icon  = Icons.Filled.WarningAmber,
                        title = "This turns protection off.",
                        body  = "Device Admin comes off so the app can be uninstalled. It's " +
                            "deliberately not a quick action — that's what stops the app being " +
                            "removed by accident or on a whim.",
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value         = confirmText,
                        onValueChange = { confirmText = it },
                        label         = { Text("Type $CONFIRM_PHRASE to confirm") },
                        singleLine    = true,
                        shape         = RoundedCornerShape(12.dp),
                        modifier      = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            DeviceAdminHelper.removeAdmin(context)
                            isAdminActive = false
                            justRemoved = true
                        },
                        enabled  = confirmText == CONFIRM_PHRASE,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape    = RoundedCornerShape(14.dp),
                    ) { Text("Remove Device Admin", fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick  = onDone,
                        modifier = Modifier.fillMaxWidth(),
                        shape    = RoundedCornerShape(14.dp),
                    ) {
                        Text("Cancel")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusCard(tone: Color, icon: ImageVector, title: String, body: String) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(tone.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = tone)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = tone,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Names the locks standing in the way, so "end your locks first" is actionable rather than vague. */
@Composable
private fun LockLine(lock: BlockedAppEntity) {
    val context = LocalContext.current
    val identity = remember(lock.packageName) { loadAppIdentity(context, lock.packageName) }
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            identity.label,
            style      = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.weight(1f),
        )
        Text(
            "until ${formatUnlockAt(lock.blockUntil)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
