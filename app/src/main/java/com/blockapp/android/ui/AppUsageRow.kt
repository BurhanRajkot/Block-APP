package com.blockapp.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.blockapp.android.ui.theme.Ember
import com.blockapp.android.usage.AppUsage

/**
 * One app in a time-spent list: icon, name, a meter against the most-used app in the same list,
 * and the time. [reels] adds a Ember reel count under the name. [onLock], when given, puts a
 * lock button on the end. That jump from "2h on YouTube" straight to locking it is the point of
 * listing apps at all.
 */
@Composable
internal fun AppUsageRow(
    usage: AppUsage,
    maxMillis: Long,
    reels: Int = 0,
    isLocked: Boolean = false,
    onLock: (() -> Unit)? = null,
) {
    val identity = rememberAppIdentity(usage.packageName)
    val fraction = if (maxMillis > 0L) usage.foregroundMillis.toFloat() / maxMillis else 0f

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(identity, size = 42)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    usage.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    formatHoursMinutes(usage.foregroundMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(7.dp))
            Meter(fraction, MaterialTheme.colorScheme.primary, height = 5.dp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (usage.opens) {
                        0 -> "Still open"
                        1 -> "Opened once"
                        else -> "Opened ${usage.opens}×"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (reels > 0) {
                    Text(
                        "  ·  ▶︎ $reels reel${if (reels == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ember,
                    )
                }
            }
        }
        if (onLock != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onLock) {
                Icon(
                    if (isLocked) Icons.Filled.Lock else Icons.Outlined.Lock,
                    contentDescription = if (isLocked) "Extend lock" else "Lock ${usage.label}",
                    tint = if (isLocked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Shown in place of any usage figure while the Usage access app-op is off. */
@Composable
internal fun UsageAccessPrompt(onGrant: () -> Unit) {
    Column {
        IconTile(
            Icons.Outlined.Timelapse,
            tint = MaterialTheme.colorScheme.primary,
            background = MaterialTheme.colorScheme.primaryContainer,
        )
        Spacer(Modifier.height(14.dp))
        Text("See where your day goes", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Time per app, by the hour, and how often you open each one. Needs \"Usage " +
                "access\", which only reads how long apps were in front. Nothing leaves this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Allow usage access", onClick = onGrant, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Opens the Usage access list, on this app's own entry where the OEM supports the extra. Shared
 * by HomeScreen, InsightsScreen and OnboardingScreen.
 */
internal fun openUsageAccessSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        // Usage access isn't exposed as its own screen on every OEM build; App info is the one
        // place it can always be reached from.
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
