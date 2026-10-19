package com.blockapp.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.ui.AppPickerScreen
import com.blockapp.android.ui.FocusModeScreen
import com.blockapp.android.ui.HomeScreen
import com.blockapp.android.ui.HistoryScreen
import com.blockapp.android.ui.LockedCelebration
import com.blockapp.android.ui.LockedSummary
import com.blockapp.android.ui.OnboardingScreen
import com.blockapp.android.ui.PlanScreen
import com.blockapp.android.ui.QuizScreen
import com.blockapp.android.ui.RemoveProtectionScreen
import com.blockapp.android.ui.SettingsScreen
import com.blockapp.android.ui.UnlockKeyScreen
import com.blockapp.android.ui.theme.BlockAppTheme
import com.blockapp.android.util.ReelPlan
import java.time.LocalDate

private sealed class Screen {
    data object Home : Screen()

    /** [day] opens straight on that day; null opens the month view. */
    data class History(val day: LocalDate? = null) : Screen()
    data object Settings : Screen()
    data object Onboarding : Screen()
    data object Quiz : Screen()
    data object Plan : Screen()
    data object FocusMode : Screen()
    data object UnlockKey : Screen()
    data object RemoveProtection : Screen()

    /**
     * [packageName] skips straight to the duration step for that app (the lock buttons on
     * usage rows). [returnTo] is the tab it was opened from, which is where back and a
     * finished lock both go.
     */
    data class AppPicker(val packageName: String? = null, val returnTo: Screen = Home) : Screen()
}

/** The bottom-bar destinations. Every other screen hides the bar while it is open. */
private val Tabs = listOf(Screen.Home, Screen.History(), Screen.Settings)

/** History is a tab whichever day it was opened on. */
private fun Screen.isTab(): Boolean = this is Screen.Home || this is Screen.History || this is Screen.Settings

/**
 * Where the system back gesture goes from each screen. A fixed parent per screen rather than a
 * real back stack — with this few screens that's enough, and it keeps one guarantee explicit:
 * every path terminates at [Screen.Home] in at most two hops, so back can never strand a user
 * on RemoveProtectionScreen (invariant 1 — that screen is the only way out once Device Admin has
 * locked the Settings routes away, and a dead end there is a factory-reset-grade problem).
 * AppPicker's [Screen.AppPicker.returnTo] is only ever a tab, and every tab's parent is Home.
 */
private fun Screen.parent(): Screen = when (this) {
    Screen.Home -> Screen.Home
    is Screen.History -> Screen.Home
    Screen.Settings -> Screen.Home
    Screen.FocusMode -> Screen.Home
    Screen.UnlockKey -> Screen.Home
    Screen.Quiz -> Screen.Home
    Screen.Plan -> Screen.Home
    is Screen.AppPicker -> returnTo
    Screen.Onboarding -> Screen.Settings
    Screen.RemoveProtection -> Screen.Settings
}

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Best-effort: the block-guard foreground service runs regardless of this permission,
        // but without it its ongoing notification stays hidden on Android 13+, which makes it
        // an easier target for OEM battery managers to kill unnoticed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            BlockAppTheme {
                Surface {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current

    // A first launch opens on the quiz, which builds the reel plan, then goes on to setup. A
    // later launch after Accessibility/admin was stripped opens straight on setup instead of on
    // Home. Home's red banner is easy to skim past, and until both required permissions are on
    // the app silently blocks nothing at all — which reads as "the app is broken", not "setup
    // isn't finished".
    fun afterQuiz(): Screen = if (DeviceAdminHelper.requiresSetup(context)) Screen.Onboarding else Screen.Home
    var screen by remember {
        mutableStateOf(if (!ReelPlan.quizSeen(context)) Screen.Quiz else afterQuiz())
    }

    // Without this, back from any sub-screen or tab leaves the app entirely — which on this app means
    // a user who opened Settings to reach "Remove protection" gets dropped to the launcher and
    // has to start over. Disabled on Home so back there still exits, as expected.
    BackHandler(enabled = screen != Screen.Home) { screen = screen.parent() }

    // Shown on top of everything right after a lock starts. Navigation has already moved on
    // underneath it, so dismissing it just reveals where the user was going anyway.
    var celebration by remember { mutableStateOf<LockedSummary?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = screen.isTab(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                BottomBar(current = screen, onSelect = { screen = it })
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = screen,
            modifier = Modifier.padding(padding),
            transitionSpec = {
                val from = initialState.depth()
                val to = targetState.depth()
                // Springs rather than fixed-length tweens, so a screen decelerates into place
                // instead of stopping dead at 320ms, which is most of what made navigation feel
                // stiff. Offsets are springs too, and fades stay short tweens so nothing lingers
                // half-transparent.
                val slide = spring<IntOffset>(dampingRatio = 0.9f, stiffness = SCREEN_STIFFNESS)
                when {
                    // Between tabs: a short glide in the direction of the tab tapped, so the
                    // bar's order and the motion agree.
                    from == 0 && to == 0 -> {
                        val dir = if (targetState.tabIndex() >= initialState.tabIndex()) 1 else -1
                        (slideInHorizontally(slide) { dir * it / 12 } + fadeIn(tween(200, delayMillis = 40)))
                            .togetherWith(slideOutHorizontally(slide) { -dir * it / 12 } + fadeOut(tween(120)))
                    }
                    // Deeper: the new screen slides in from the right over the old one.
                    to > from ->
                        (slideInHorizontally(slide) { it / 4 } + fadeIn(tween(220)) + scaleIn(spring(stiffness = SCREEN_STIFFNESS), initialScale = 0.98f))
                            .togetherWith(slideOutHorizontally(slide) { -it / 8 } + fadeOut(tween(160)))
                    // Back out: the reverse.
                    else ->
                        (slideInHorizontally(slide) { -it / 8 } + fadeIn(tween(220)))
                            .togetherWith(slideOutHorizontally(slide) { it / 4 } + fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.98f))
                }
            },
            label = "screen",
        ) { target ->
            when (target) {
                Screen.Home -> HomeScreen(
                    onFocusMode = { screen = Screen.FocusMode },
                    onAddLock = { screen = Screen.AppPicker() },
                    onLockApp = { screen = Screen.AppPicker(it, returnTo = Screen.Home) },
                    onHistory = { screen = Screen.History(it) },
                    onPlan = { screen = Screen.Plan },
                    onFixSetup = { screen = Screen.Onboarding },
                )
                is Screen.History -> HistoryScreen(
                    initialDay = target.day,
                    onLockApp = { screen = Screen.AppPicker(it, returnTo = target) },
                )
                Screen.Quiz -> QuizScreen(onDone = { screen = afterQuiz() })
                Screen.Plan -> PlanScreen(
                    onBack = { screen = Screen.Home },
                    onRebuild = { screen = Screen.Quiz },
                )
                Screen.Settings -> SettingsScreen(
                    onPermissions = { screen = Screen.Onboarding },
                    onHistory = { screen = Screen.History() },
                    onPlan = { screen = Screen.Plan },
                    onEnterKey = { screen = Screen.UnlockKey },
                    onRemoveProtection = { screen = Screen.RemoveProtection },
                )
                is Screen.AppPicker -> AppPickerScreen(
                    initialPackage = target.packageName,
                    onBack = { screen = target.returnTo },
                    onLocked = {
                        screen = target.returnTo
                        celebration = it
                    },
                )
                Screen.FocusMode -> FocusModeScreen(
                    onBack = { screen = Screen.Home },
                    onLocked = {
                        screen = Screen.Home
                        celebration = it
                    },
                )
                Screen.UnlockKey -> UnlockKeyScreen(onBack = { screen = Screen.Home })
                Screen.Onboarding -> OnboardingScreen(
                    onBack = { screen = Screen.Settings },
                    onDone = { screen = Screen.Home },
                    onRemoveProtection = { screen = Screen.RemoveProtection },
                )
                Screen.RemoveProtection -> RemoveProtectionScreen(
                    onDone = { screen = Screen.Home },
                )
            }
        }
    }

    celebration?.let { summary ->
        LockedCelebration(summary, onFinished = { celebration = null })
    }
}

/** A tab's position in the bottom bar, for the direction a tab switch slides. */
private fun Screen.tabIndex(): Int = when (this) {
    Screen.Home -> 0
    is Screen.History -> 1
    else -> 2
}

/** Spring stiffness of screen changes: settles in about 300ms, with no visible bounce. */
private const val SCREEN_STIFFNESS = 500f

/** How deep a screen sits, which decides the direction screens slide in [AppRoot]. */
private fun Screen.depth(): Int = when (this) {
    Screen.Home, is Screen.History, Screen.Settings -> 0
    Screen.RemoveProtection -> 2
    else -> 1
}

@Composable
private fun BottomBar(current: Screen, onSelect: (Screen) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Tabs.forEach { tab ->
            val (label, icon) = when (tab) {
                is Screen.History -> "History" to Icons.Outlined.CalendarMonth
                Screen.Settings -> "Settings" to Icons.Outlined.Settings
                else -> "Today" to Icons.Outlined.Today
            }
            NavigationBarItem(
                selected = current::class == tab::class,
                onClick = { onSelect(tab) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
