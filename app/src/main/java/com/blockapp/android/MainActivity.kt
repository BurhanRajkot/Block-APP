package com.blockapp.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.core.content.ContextCompat
import com.blockapp.android.ui.AppPickerScreen
import com.blockapp.android.ui.HomeScreen
import com.blockapp.android.ui.OnboardingScreen
import com.blockapp.android.ui.RemoveProtectionScreen
import com.blockapp.android.ui.ScreenTimeScreen
import com.blockapp.android.ui.UnlockKeyScreen

private sealed class Screen {
    data object Home : Screen()
    data object Onboarding : Screen()
    data object AppPicker : Screen()
    data object UnlockKey : Screen()
    data object RemoveProtection : Screen()
    data object ScreenTime : Screen()
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
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    Box {
        when (screen) {
            Screen.Home -> HomeScreen(
                onAddLock = { screen = Screen.AppPicker },
                onEnterKey = { screen = Screen.UnlockKey },
                onOnboarding = { screen = Screen.Onboarding },
                onScreenTime = { screen = Screen.ScreenTime },
            )
            Screen.AppPicker -> AppPickerScreen(onDone = { screen = Screen.Home })
            Screen.UnlockKey -> UnlockKeyScreen(onDone = { screen = Screen.Home })
            Screen.Onboarding -> OnboardingScreen(
                onDone = { screen = Screen.Home },
                onRemoveProtection = { screen = Screen.RemoveProtection },
            )
            Screen.RemoveProtection -> RemoveProtectionScreen(
                onDone = { screen = Screen.Home },
                onEnterKey = { screen = Screen.UnlockKey },
            )
            Screen.ScreenTime -> ScreenTimeScreen(onBack = { screen = Screen.Home })
        }
    }
}
