package com.blockapp.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.blockapp.android.admin.DeviceAdminHelper
import com.blockapp.android.ui.AppPickerScreen
import com.blockapp.android.ui.HomeScreen
import com.blockapp.android.ui.OnboardingScreen
import com.blockapp.android.ui.RemoveProtectionScreen
import com.blockapp.android.ui.ScreenTimeScreen
import com.blockapp.android.ui.SettingsScreen
import com.blockapp.android.ui.UnlockKeyScreen

private sealed class Screen {
    data object Home : Screen()
    data object Settings : Screen()
    data object Onboarding : Screen()
    data object AppPicker : Screen()
    data object UnlockKey : Screen()
    data object RemoveProtection : Screen()
    data object ScreenTime : Screen()
}

/**
 * Where the system back gesture goes from each screen. A fixed parent per screen rather than a
 * real back stack — with seven screens that's enough, and it keeps one guarantee explicit:
 * every path terminates at [Screen.Home] in at most two hops, so back can never strand a user
 * on RemoveProtectionScreen (invariant 1 — that screen is the only way out once Device Admin has
 * locked the Settings routes away, and a dead end there is a factory-reset-grade problem).
 */
private fun Screen.parent(): Screen = when (this) {
    Screen.Home -> Screen.Home
    Screen.Settings -> Screen.Home
    Screen.AppPicker -> Screen.Home
    Screen.UnlockKey -> Screen.Home
    Screen.Onboarding -> Screen.Settings
    Screen.ScreenTime -> Screen.Settings
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
            MaterialTheme {
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

    // A first launch (or a launch after Accessibility/admin was stripped) opens straight on
    // setup instead of on Home. Home's red banner is easy to skim past, and until both required
    // permissions are on the app silently blocks nothing at all — which reads as "the app is
    // broken", not "setup isn't finished".
    var screen by remember {
        mutableStateOf<Screen>(
            if (DeviceAdminHelper.requiresSetup(context)) Screen.Onboarding else Screen.Home,
        )
    }

    // Without this, back from any sub-screen leaves the app entirely — which on this app means
    // a user who opened Settings to reach "Remove protection" gets dropped to the launcher and
    // has to start over. Disabled on Home so back there still exits, as expected.
    BackHandler(enabled = screen != Screen.Home) { screen = screen.parent() }

    Box {
        when (screen) {
            Screen.Home -> HomeScreen(
                onAddLock = { screen = Screen.AppPicker },
                onEnterKey = { screen = Screen.UnlockKey },
                onSettings = { screen = Screen.Settings },
            )
            Screen.Settings -> SettingsScreen(
                onBack = { screen = Screen.Home },
                onPermissions = { screen = Screen.Onboarding },
                onScreenTime = { screen = Screen.ScreenTime },
                onEnterKey = { screen = Screen.UnlockKey },
                onRemoveProtection = { screen = Screen.RemoveProtection },
            )
            Screen.AppPicker -> AppPickerScreen(
                onBack = { screen = Screen.Home },
                onLocked = { screen = Screen.Home },
            )
            Screen.UnlockKey -> UnlockKeyScreen(onBack = { screen = Screen.Home })
            Screen.Onboarding -> OnboardingScreen(
                onBack = { screen = Screen.Settings },
                onDone = { screen = Screen.Home },
                onRemoveProtection = { screen = Screen.RemoveProtection },
            )
            Screen.RemoveProtection -> RemoveProtectionScreen(
                onDone = { screen = Screen.Home },
                onEnterKey = { screen = Screen.UnlockKey },
            )
            Screen.ScreenTime -> ScreenTimeScreen(onBack = { screen = Screen.Settings })
        }
    }
}
