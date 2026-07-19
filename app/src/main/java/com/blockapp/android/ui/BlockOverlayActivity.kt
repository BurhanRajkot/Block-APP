package com.blockapp.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class BlockOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCK_UNTIL = "block_until"
    }

    private var blockUntilState by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blockUntilState = intent.getLongExtra(EXTRA_BLOCK_UNTIL, 0L)
        setContent {
            MaterialTheme {
                Surface {
                    BlockOverlayContent(blockUntilState, onGoHome = ::goHome)
                }
            }
        }
    }

    // singleTask + FLAG_ACTIVITY_CLEAR_TOP means a re-lock while this is already on screen
    // (e.g. the user taps the locked app again) delivers here instead of a fresh onCreate —
    // without this, the countdown would keep showing whatever blockUntil it started with.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        blockUntilState = intent.getLongExtra(EXTRA_BLOCK_UNTIL, 0L)
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun BlockOverlayContent(blockUntil: Long, onGoHome: () -> Unit) {
    var remainingMs by remember(blockUntil) {
        mutableStateOf((blockUntil - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    LaunchedEffect(blockUntil) {
        while (remainingMs > 0L) {
            delay(1000L)
            remainingMs = (blockUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔒", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(16.dp))
            Text(
                "This app is locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (remainingMs > 0L) "Unlocks in ${formatRemaining(remainingMs)}" else "Unlocking…",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGoHome) { Text("Go home") }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
