package com.blockapp.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.HourglassDisabled
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.admin.OemBackground
import com.blockapp.android.admin.RestrictedSettings
import com.blockapp.android.ui.theme.StatusColors
import com.blockapp.android.usage.UsageStatsProvider

/**
 * First-time setup, and where Settings → Permissions lands later. Two required steps
 * (Accessibility, then Device Admin) shown one at a time, then the optional reliability extras
 * Android can check, then the phone maker's own switches it can't (OemBackground).
 *
 * Accessibility is judged by whether the service is *running*, not only by the Settings switch
 * (rememberProtection, ServiceState): a switch left on over a crashed service used to read as
 * done here while nothing was blocked, and that step now says so and walks the fix.
 *
 * Invariant 1 lives here too: "Remove protection" stays in the header on every state of this
 * screen, because a user whose admin is active but whose setup broke comes here first.
 *
 * The Accessibility step is a three-part walk-through on Android 13+, in the only order that
 * works on a sideloaded install: see RestrictedSettings for why App info's ⋮ item doesn't exist
 * until the switch has been tried once.
 */
@Composable
fun OnboardingScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    onRemoveProtection: () -> Unit,
) {
    val context = LocalContext.current
    val protection = rememberProtection()

    var isBatteryUnrestricted  by remember { mutableStateOf(false) }
    var canScheduleExactAlarms by remember { mutableStateOf(false) }
    var hasNotifications       by remember { mutableStateOf(false) }
    var hasUsageAccess         by remember { mutableStateOf(false) }
    var canDrawOverlays        by remember { mutableStateOf(false) }
    var exemptFromHibernation  by remember { mutableStateOf(false) }
    var oemDone                by remember { mutableStateOf(setOf<String>()) }
    var triedSwitch            by remember { mutableStateOf(RestrictedSettings.triedSwitch(context)) }
    var openedAppInfo          by remember { mutableStateOf(RestrictedSettings.openedAppInfo(context)) }
    val likelyRestricted       = remember { RestrictedSettings.likelyRestricted(context) }
    val oemSteps               = remember { OemBackground.steps(context) }
    val maker                  = remember { OemBackground.maker() }

    /**
     * Device Admin is launched through an activity-result launcher rather than
     * `context.startActivity(...)`, and that difference is load-bearing: Settings'
     * `DeviceAdminAdd` calls `finish()` immediately if the intent that started it carries
     * `FLAG_ACTIVITY_NEW_TASK` (see DeviceAdminHelper.requestAdminIntent). Launching through the
     * registry starts it for a result from MainActivity itself, so the intent reaches Settings
     * with no task flags at all and the activation dialog actually shows.
     *
     * The result is ignored — the user can back out of the system dialog without a cancel result
     * on some builds — so admin state is re-read from DevicePolicyManager on resume instead
     * (rememberProtection).
     */
    val adminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    /**
     * For a result for the same reason as admin, but a different one: Settings only opens a
     * service's own switch page (RestrictedSettings.accessibilityDetailsIntent) when it can see
     * which package asked, and a calling package exists only for a for-result start.
     */
    val accessibilityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    fun openAccessibilitySwitch() {
        try {
            accessibilityLauncher.launch(RestrictedSettings.accessibilityDetailsIntent(context))
        } catch (e: ActivityNotFoundException) {
            openOrExplain(context, RestrictedSettings.accessibilityListIntent(), "Settings → Accessibility")
        } catch (e: SecurityException) {
            // Some OEM builds refuse the details action to third-party callers outright rather
            // than falling back to the list themselves.
            openOrExplain(context, RestrictedSettings.accessibilityListIntent(), "Settings → Accessibility")
        }
    }

    // Re-check every time this screen resumes (e.g. coming back from Settings).
    LifecycleResumeEffect(Unit) {
        isBatteryUnrestricted  = DeviceAdminHelper.isIgnoringBatteryOptimizations(context)
        canScheduleExactAlarms = DeviceAdminHelper.canScheduleExactAlarms(context)
        hasNotifications       = DeviceAdminHelper.areNotificationsEnabled(context)
        hasUsageAccess         = UsageStatsProvider.hasUsageAccess(context)
        canDrawOverlays        = DeviceAdminHelper.canDrawOverlays(context)
        exemptFromHibernation  = DeviceAdminHelper.isExemptFromHibernation(context)
        oemDone                = oemSteps.filter { OemBackground.isDone(context, it) }.map { it.id }.toSet()
        onPauseOrDispose {}
    }

    val isAccessibilityActive = protection.accessibilityOn
    val isAdminActive = protection.admin
    val accessibilityDone = protection.accessibility == AccessibilityUi.RUNNING ||
        protection.accessibility == AccessibilityUi.STARTING
    val requiredDone = listOf(accessibilityDone, isAdminActive).count { it }
    val allRequired = isAccessibilityActive && isAdminActive

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = if (protection.isProtected) "You're protected" else "Set up protection",
            subtitle = if (protection.isProtected) {
                "Both switches are on and blocking is running. The extras below make it harder to break."
            } else {
                "Two switches make blocking work. About a minute."
            },
            onBack = onBack,
            actions = {
                TextButton(onClick = onRemoveProtection) {
                    Text("Remove protection", color = StatusColors.Danger)
                }
            },
        )

        Column(Modifier.padding(horizontal = ScreenPadding)) {
            SetupProgress(done = requiredDone, total = 2)
            Spacer(Modifier.height(16.dp))

            Reveal(0) {
                StepPanel(
                    number = 1,
                    icon = Icons.Outlined.AccessibilityNew,
                    title = when (protection.accessibility) {
                        AccessibilityUi.NOT_RUNNING -> "Accessibility is on but not running"
                        AccessibilityUi.STARTING -> "Accessibility · starting"
                        else -> "Accessibility"
                    },
                    why = "Lets App Blocker see which app is in front, so it can send you away " +
                        "from a locked one and count reels. Everything stays on this phone. It " +
                        "doesn't record what you type or keep what's on screen.",
                    state = when (protection.accessibility) {
                        AccessibilityUi.RUNNING, AccessibilityUi.STARTING -> StepState.Done
                        else -> StepState.Active
                    },
                ) {
                    if (protection.accessibility == AccessibilityUi.NOT_RUNNING) {
                        NotRunningFix(maker = maker, onOpenSwitch = ::openAccessibilitySwitch)
                    } else if (RestrictedSettings.applies()) {
                        AccessibilityWalkthrough(
                            likelyRestricted = likelyRestricted,
                            triedSwitch = triedSwitch,
                            openedAppInfo = openedAppInfo,
                            onTrySwitch = {
                                RestrictedSettings.markTriedSwitch(context)
                                triedSwitch = true
                                openAccessibilitySwitch()
                            },
                            onOpenAppInfo = {
                                RestrictedSettings.markOpenedAppInfo(context)
                                openedAppInfo = true
                                // Safe to open while this step is showing: the service isn't
                                // running yet, so Tier 1 can't bounce this app's App info.
                                openOrExplain(context, RestrictedSettings.appInfoIntent(context), "Settings → Apps → App Blocker")
                            },
                            onTurnOn = ::openAccessibilitySwitch,
                        )
                    } else {
                        HowTo(
                            listOf(
                                "Find \"App Blocker\", usually under Downloaded apps or Installed apps",
                                "Turn the switch on, then tap Allow",
                            ),
                        )
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton("Open Accessibility", ::openAccessibilitySwitch, Modifier.fillMaxWidth())
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Reveal(1) {
                StepPanel(
                    number = 2,
                    icon = Icons.Outlined.Shield,
                    title = "Device admin",
                    why = "Makes Android ask for admin to be turned off before App Blocker can " +
                        "be uninstalled, so it can't go in two taps mid-lock. To undo it later, " +
                        "use \"Remove protection\" at the top of this screen.",
                    state = when {
                        isAdminActive -> StepState.Done
                        // Order matters: once admin is active the service's Tier 1 guard arms and
                        // bounces the Accessibility screen. Admin first means the moment the user
                        // finally flips the Accessibility switch on, the service starts and kicks
                        // them out of the screen they're standing in.
                        !isAccessibilityActive -> StepState.Waiting
                        else -> StepState.Active
                    },
                    waitingNote = "Unlocks after step 1. Admin first would start guarding the " +
                        "Accessibility screen and bounce you out of it mid-setup.",
                ) {
                    HowTo(listOf("Read the warning Android shows", "Tap \"Activate this device admin app\""))
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        "Activate device admin",
                        onClick = {
                            val intent = DeviceAdminHelper.requestAdminIntent(
                                context,
                                "Prevents the app being uninstalled while a lock is active.",
                            )
                            try {
                                adminLauncher.launch(intent)
                            } catch (e: ActivityNotFoundException) {
                                // A handful of OEM builds don't expose DeviceAdminAdd to
                                // third-party apps, and there is no other intent that adds an
                                // admin, so say where to do it by hand rather than let the tap
                                // throw and take the setup screen down with it.
                                Toast.makeText(
                                    context,
                                    "This phone doesn't open the Device admin screen directly. Add " +
                                        "\"App Blocker\" under Settings → Security → Device admin apps.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } catch (e: SecurityException) {
                                Toast.makeText(
                                    context,
                                    "Add \"App Blocker\" under Settings → Security → Device admin apps.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            AnimatedVisibility(
                visible = protection.isProtected,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    Panel(color = StatusColors.Success.copy(alpha = 0.10f)) {
                        Text("Protection is on", style = MaterialTheme.typography.titleMedium, color = StatusColors.Success)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Locked apps will be bounced from now on. It makes giving in harder, " +
                                "not impossible: Safe Mode or Settings can still undo it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val extras = buildList {
                add(
                    Extra(
                        Icons.Outlined.BatteryChargingFull,
                        "Unrestricted battery",
                        "Stops Android from putting App Blocker to sleep partway through a lock.",
                        isBatteryUnrestricted,
                    ) { requestIgnoreBatteryOptimizations(context) },
                )
                add(
                    Extra(
                        Icons.Outlined.Layers,
                        "Display over other apps",
                        "Lets the block screen appear reliably after a bounce. Needed on Xiaomi and some others.",
                        canDrawOverlays,
                    ) { requestOverlay(context) },
                )
                add(
                    Extra(
                        Icons.Outlined.Alarm,
                        "Exact alarms",
                        "Ends a lock the minute its timer runs out, not a few minutes late.",
                        canScheduleExactAlarms,
                    ) { requestScheduleExactAlarm(context) },
                )
                add(
                    Extra(
                        Icons.Outlined.Notifications,
                        "Notifications",
                        "Shows the \"blocking is on\" notification, which keeps the app from being killed quietly.",
                        hasNotifications,
                    ) { openNotificationSettings(context) },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(
                        Extra(
                            Icons.Outlined.HourglassDisabled,
                            "Don't pause when unused",
                            "Turn off \"Pause app activity if unused\" so Android never strips its permissions.",
                            exemptFromHibernation,
                        ) { requestHibernationExemption(context) },
                    )
                }
                add(
                    Extra(
                        Icons.Outlined.BarChart,
                        "Usage access",
                        "Only for the screen-time charts. Nothing leaves the phone.",
                        hasUsageAccess,
                    ) { openUsageAccessSettings(context) },
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Make it reliable", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Text(
                    "${extras.count { it.granted }} of ${extras.size} on",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Optional. Locks work without these, just less dependably. Each one ticks itself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            extras.forEachIndexed { index, extra ->
                Reveal(2 + index) { ExtraRow(extra) }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (maker == OemBackground.Maker.OTHER) "On this phone" else "On your ${maker.label}",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${oemDone.size} of ${oemSteps.size} done",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (maker == OemBackground.Maker.OTHER) {
                    "One more habit that keeps a blocker alive on any phone."
                } else {
                    "${maker.label} phones close background apps on their own. These switches stop " +
                        "that. Android can't read them, so tick each one off yourself."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            oemSteps.forEach { step ->
                OemStepRow(
                    step = step,
                    done = step.id in oemDone,
                    onOpen = {
                        if (!OemBackground.open(context, step)) {
                            Toast.makeText(context, "Couldn't open it here. Look in Settings → Apps → App Blocker.", Toast.LENGTH_LONG).show()
                        }
                    },
                    onToggle = { done ->
                        OemBackground.markDone(context, step, done)
                        oemDone = if (done) oemDone + step.id else oemDone - step.id
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(20.dp))
            // Gated on the required steps only. Holding the user here until every optional
            // extra was granted made the screen a dead end on OEMs that don't offer one of them.
            PrimaryButton(
                if (allRequired) "Done" else "Finish steps 1 and 2 first",
                onClick = onDone,
                enabled = allRequired,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "App Blocker has no internet permission. Nothing it sees can leave this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Shown when the switch is on but the service isn't running: the service crashed, or the phone
 * stopped it, and Android won't start it again until the switch is cycled. Samsung marks this
 * "Not working" under the switch, a Pixel "This service is malfunctioning". The guard that
 * bounces the Accessibility screen (Tier 1) lives in the stopped service, so the screen is
 * reachable now even with Device Admin active.
 */
@Composable
private fun NotRunningFix(maker: OemBackground.Maker, onOpenSwitch: () -> Unit) {
    Panel(color = StatusColors.Warning.copy(alpha = 0.10f), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Text(
            "The switch is on, but Android isn't running App Blocker's service, so nothing is " +
                "being blocked." + if (maker == OemBackground.Maker.SAMSUNG) " Samsung shows this as \"Not working\"." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(12.dp))
    HowTo(
        listOf(
            "Open the App Blocker switch",
            "Turn it off, then on again, and tap Allow",
            "Come back here. This step ticks itself once it's running",
        ),
    )
    Spacer(Modifier.height(14.dp))
    PrimaryButton("Open the switch", onOpenSwitch, Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text(
        "If it keeps stopping, turn on Unrestricted battery and the steps for your phone below.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun OemStepRow(
    step: OemBackground.Step,
    done: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Panel(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (done) StatusColors.Success else Color.Transparent)
                    .border(1.5.dp, if (done) StatusColors.Success else MaterialTheme.colorScheme.outline, CircleShape)
                    .clickable { onToggle(!done) },
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(15.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step.intents.isNotEmpty()) {
                        SmallAction("Open", filled = !done, onClick = onOpen)
                    }
                    SmallAction(if (done) "Not done" else "Mark done", filled = false) { onToggle(!done) }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(text: String, filled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

// ── progress ───────────────────────────────────────────────────────────────────

/** One bar per required step, filling in Lime as each is done. */
@Composable
private fun SetupProgress(done: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(total) { index ->
            val fill by animateColorAsState(
                if (index < done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                tween(PROGRESS_FADE_MS),
                label = "segment",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(fill),
            )
        }
    }
}

// ── required step ──────────────────────────────────────────────────────────────

private enum class StepState { Done, Active, Waiting }

/**
 * A required step. Only the active one is open; a done step collapses to its title and a tick,
 * and a waiting one shows why it's waiting, so the screen always has exactly one thing to do.
 */
@Composable
private fun StepPanel(
    number: Int,
    icon: ImageVector,
    title: String,
    why: String,
    state: StepState,
    waitingNote: String? = null,
    content: @Composable () -> Unit,
) {
    val edge by animateColorAsState(
        when (state) {
            StepState.Active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            else -> Color.Transparent
        },
        tween(PROGRESS_FADE_MS),
        label = "stepEdge",
    )
    Panel(modifier = Modifier.border(1.dp, edge, PanelShape)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBadge(number, state)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Eyebrow("Step $number")
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state == StepState.Waiting) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(
            visible = state == StepState.Active,
            enter = expandVertically(tween(STEP_EXPAND_MS)) + fadeIn(tween(STEP_EXPAND_MS)),
            exit = shrinkVertically(tween(STEP_EXPAND_MS)) + fadeOut(tween(STEP_EXPAND_MS / 2)),
        ) {
            Column {
                Spacer(Modifier.height(10.dp))
                Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
        if (state == StepState.Waiting && waitingNote != null) {
            Spacer(Modifier.height(8.dp))
            Text(waitingNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepBadge(number: Int, state: StepState) {
    val fill by animateColorAsState(
        when (state) {
            StepState.Done -> StatusColors.Success
            StepState.Active -> MaterialTheme.colorScheme.primary
            StepState.Waiting -> Color.Transparent
        },
        tween(PROGRESS_FADE_MS),
        label = "badge",
    )
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(fill)
            .then(
                if (state == StepState.Waiting) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (state == StepState.Done) {
            Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
        } else {
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge,
                color = if (state == StepState.Active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── the restricted-settings walk-through ──────────────────────────────────────────

/**
 * Try the switch → App info ⋮ → switch again. When the install probably isn't restricted (a USB
 * or store install) it starts as a single "turn it on" button, with the walk-through one tap
 * away for when the switch turns out to be greyed out after all.
 */
@Composable
private fun AccessibilityWalkthrough(
    likelyRestricted: Boolean,
    triedSwitch: Boolean,
    openedAppInfo: Boolean,
    onTrySwitch: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onTurnOn: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(likelyRestricted) }
    if (!expanded) {
        HowTo(listOf("Tap the App Blocker switch", "Tap Allow"))
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Open the switch", onTurnOn, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { expanded = true }) {
            Text("Switch greyed out, or says \"Restricted setting\"?")
        }
        return
    }

    Text(
        "Android blocks this switch for apps installed from a file until you allow it. It only " +
            "lets you allow it after it has blocked you once, so the order matters:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(12.dp))
    val current = when {
        !triedSwitch -> 1
        !openedAppInfo -> 2
        else -> 3
    }
    GuideRow(
        number = 1,
        current = current,
        title = "Try the switch once",
        detail = "Tap App Blocker's switch. Android says \"Restricted setting\". Tap OK. That's " +
            "expected, and it's what unlocks step 2.",
        button = "Open the switch",
        onClick = onTrySwitch,
    )
    GuideRow(
        number = 2,
        current = current,
        title = "Allow restricted settings",
        detail = "On App info, tap ⋮ at the top right, then \"Allow restricted settings\", then " +
            "confirm with your PIN or fingerprint.",
        button = "Open App info",
        onClick = onOpenAppInfo,
    )
    GuideRow(
        number = 3,
        current = current,
        title = "Turn the switch on",
        detail = "Back on the switch, tap it again and tap Allow. This step ticks itself when it's on.",
        button = "Open the switch",
        onClick = onTurnOn,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "No ⋮ menu, or no \"Allow restricted settings\" in it? Android hasn't blocked the switch " +
            "yet. Do step 1 again and tap the greyed-out switch itself until the message shows.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One part of the walk-through. The current part is open with a filled button; done parts keep a
 * quiet "Again" so a user who dismissed a dialog too fast can repeat it; later parts stay shut.
 */
@Composable
private fun GuideRow(
    number: Int,
    current: Int,
    title: String,
    detail: String,
    button: String,
    onClick: () -> Unit,
) {
    val state = when {
        number < current -> StepState.Done
        number == current -> StepState.Active
        else -> StepState.Waiting
    }
    Row(Modifier.padding(vertical = 6.dp)) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when (state) {
                        StepState.Done -> StatusColors.Success.copy(alpha = 0.18f)
                        StepState.Active -> MaterialTheme.colorScheme.primaryContainer
                        StepState.Waiting -> MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (state == StepState.Done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = StatusColors.Success, modifier = Modifier.size(14.dp))
            } else {
                Text(
                    "$number",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state == StepState.Active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (state == StepState.Active) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (state == StepState.Waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (state == StepState.Done) {
                    Text(
                        "Again",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = state == StepState.Active,
                enter = expandVertically(tween(STEP_EXPAND_MS)) + fadeIn(tween(STEP_EXPAND_MS)),
                exit = shrinkVertically(tween(STEP_EXPAND_MS)) + fadeOut(tween(STEP_EXPAND_MS / 2)),
            ) {
                Column {
                    Spacer(Modifier.height(4.dp))
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(button, onClick, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun HowTo(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { index, line ->
            Row {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp),
                )
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// ── optional extras ───────────────────────────────────────────────────────────

private class Extra(
    val icon: ImageVector,
    val title: String,
    val why: String,
    val granted: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun ExtraRow(extra: Extra) {
    Panel(
        onClick = if (extra.granted) null else extra.onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                extra.icon,
                tint = if (extra.granted) StatusColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
                background = if (extra.granted) StatusColors.Success.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                size = 36.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(extra.title, style = MaterialTheme.typography.titleSmall)
                Text(extra.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(10.dp))
            if (extra.granted) {
                StatusPill("On", StatusColors.Success)
            } else {
                Text(
                    "Allow",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ── helpers ────────────────────────────────────────────────────────────────────

/**
 * Opens a Settings screen, or says where it is when this build doesn't have it. A missing
 * Settings activity on some OEM ROM must not crash the setup screen (invariant 9's spirit).
 */
private fun openOrExplain(context: Context, intent: Intent, where: String) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Open $where on this phone.", Toast.LENGTH_LONG).show()
    } catch (e: SecurityException) {
        // Not exported to third-party apps on some OEM builds; same answer as missing.
        Toast.makeText(context, "Open $where on this phone.", Toast.LENGTH_LONG).show()
    }
}

private fun requestScheduleExactAlarm(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    openOrExplain(
        context,
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
        "Settings → Apps → Special app access → Alarms & reminders",
    )
}

/**
 * The direct request is a one-tap system dialog that sets the app to "Unrestricted" (on a Pixel,
 * App battery usage → Unrestricted), which is far easier than finding that page by hand.
 */
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val directIntent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(directIntent)
    } catch (e: ActivityNotFoundException) {
        // A few OEM builds don't resolve the direct per-app request. Fall back to the general
        // battery optimisation list so the user can still find this app manually.
        openOrExplain(
            context,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            "Settings → Apps → App Blocker → App battery usage",
        )
    }
}

/**
 * Sends the user to this app's notification settings rather than firing the POST_NOTIFICATIONS
 * runtime request. After two dismissals Android stops showing that dialog entirely and the
 * request becomes a silent no-op — a button that visibly does nothing is worse than one extra
 * screen, and this route keeps working however many times it's been declined.
 */
private fun openNotificationSettings(context: Context) {
    // No SDK_INT guard: ACTION_APP_NOTIFICATION_SETTINGS landed in API 26, which is this app's
    // minSdk. App info stays as a runtime fallback because some OEM builds don't resolve it.
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        openOrExplain(context, RestrictedSettings.appInfoIntent(context), "Settings → Apps → App Blocker")
    }
}

/** The system's own "Display over other apps" page for this app. */
private fun requestOverlay(context: Context) {
    openOrExplain(
        context,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        "Settings → Apps → Special app access → Display over other apps",
    )
}

/**
 * Android 11's per-app "Pause app activity if unused" switch. The action exists from API 30;
 * App info is the fallback because the switch also sits on that page.
 */
private fun requestHibernationExemption(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val intent = Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.parse("package:${context.packageName}"))
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        openOrExplain(context, RestrictedSettings.appInfoIntent(context), "Settings → Apps → App Blocker")
    }
}

private const val PROGRESS_FADE_MS = 260
private const val STEP_EXPAND_MS = 260
