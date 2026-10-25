package com.blockapp.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.accessibility.ServiceState
import com.blockapp.android.admin.DeviceAdminHelper
import kotlinx.coroutines.delay

/** How the Accessibility step reads on screen. See DeviceAdminHelper.AccessibilityState. */
internal enum class AccessibilityUi {
    OFF,

    /** Switched on, and the service hasn't connected yet but still may. Shown as on. */
    STARTING,

    /** Switched on, and still not running after the grace period: needs off and on again. */
    NOT_RUNNING,

    RUNNING,
}

/** The two required capabilities, live. [accessibility] RUNNING and [admin] mean protected. */
internal data class Protection(val accessibility: AccessibilityUi, val admin: Boolean) {
    /** The switch is on (running or about to be). What setup gates the admin step on. */
    val accessibilityOn: Boolean get() = accessibility != AccessibilityUi.OFF

    val isProtected: Boolean
        get() = admin && (accessibility == AccessibilityUi.RUNNING || accessibility == AccessibilityUi.STARTING)
}

/**
 * The live protection state for Home, Settings and setup, so all three say the same thing.
 *
 * The service's connection is observed (ServiceState), so a switch flipped on in Settings ticks
 * the moment the service binds rather than at the next resume, and the Settings switch and the
 * admin state are re-read on every resume, since both change while the app is away.
 *
 * A switched-on service that isn't connected reads as STARTING for [NOT_RUNNING_GRACE_MS]
 * before it reads as NOT_RUNNING. A cold start always has that gap: the process is up before
 * the system has rebound the service, and calling that "not running" sent people to fix a
 * switch that was fine.
 */
@Composable
internal fun rememberProtection(): Protection {
    val context = LocalContext.current
    val connected by ServiceState.connected.collectAsState()
    var switchedOn by remember { mutableStateOf(DeviceAdminHelper.isAccessibilityActive(context)) }
    var admin by remember { mutableStateOf(DeviceAdminHelper.isAdminActive(context)) }
    var graceOver by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        switchedOn = DeviceAdminHelper.isAccessibilityActive(context)
        admin = DeviceAdminHelper.isAdminActive(context)
        onPauseOrDispose {}
    }
    LaunchedEffect(connected, switchedOn) {
        graceOver = false
        if (switchedOn && !connected) {
            delay(NOT_RUNNING_GRACE_MS)
            graceOver = true
        }
    }

    val accessibility = when {
        connected -> AccessibilityUi.RUNNING
        !switchedOn -> AccessibilityUi.OFF
        graceOver -> AccessibilityUi.NOT_RUNNING
        else -> AccessibilityUi.STARTING
    }
    return Protection(accessibility, admin)
}

/**
 * Long enough for the system to rebind the service after a cold start (well under a second on
 * the emulator, a couple of seconds on a busy phone), short enough that a crashed service is
 * reported while the user is still looking at the screen.
 */
private const val NOT_RUNNING_GRACE_MS = 4_000L
