package com.blockapp.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ── screen-specific palette ───────────────────────────────────────────────────
// This screen is deliberately dark while the rest of the app is light: it has to read as a wall
// the user has hit, not as another page of the app they can interact their way out of. Matches
// the dark window theme set on this activity in the manifest, so there's no flash on launch.
private val WallBackground = Color(0xFF101014)
private val WallAccent     = Color(0xFF8AB4F8)
private val WallTrack      = Color(0xFF2A2A33)

/**
 * The wall shown when a locked app is bounced to the home screen (see
 * AppBlockAccessibilityService's Tier 2). It is informational only — there is deliberately no
 * control here that shortens or lifts the lock; the only ways out are waiting it out or an
 * unlock key applied from the main app.
 */
class BlockOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCK_UNTIL = "block_until"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }

    private var blockUntilState by mutableLongStateOf(0L)
    private var packageNameState by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)

        // Back would otherwise pop this activity and reveal whatever is beneath it — which, when
        // the launcher animation hasn't settled yet, can be the locked app itself. Going home
        // explicitly means back can never be the shortcut around the block; the watchdog would
        // catch it a poll later either way, but not before a readable glimpse of the app.
        onBackPressedDispatcher.addCallback(this) { goHome() }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = WallBackground) {
                    BlockOverlayContent(
                        packageName = packageNameState,
                        blockUntil  = blockUntilState,
                        onGoHome    = ::goHome,
                        onOpenApp   = ::openBlockApp,
                        onExpired   = ::finish,
                    )
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
        readExtras(intent)
    }

    private fun readExtras(intent: Intent) {
        blockUntilState  = intent.getLongExtra(EXTRA_BLOCK_UNTIL, 0L)
        packageNameState = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    private fun openBlockApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }
}

@Composable
private fun BlockOverlayContent(
    packageName: String,
    blockUntil: Long,
    onGoHome: () -> Unit,
    onOpenApp: () -> Unit,
    onExpired: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    val identity = remember(packageName) {
        if (packageName.isEmpty()) null else loadAppIdentity(context, packageName)
    }

    // Only needed to draw how far through the lock the user is, so the wall renders immediately
    // without it and picks up the ring once Room answers.
    var blockedAt by remember(packageName) { mutableStateOf<Long?>(null) }
    LaunchedEffect(packageName) {
        if (packageName.isNotEmpty()) {
            blockedAt = app.repository.getActiveLock(packageName)?.blockedAt
        }
    }

    var remainingMs by remember(blockUntil) {
        mutableStateOf((blockUntil - System.currentTimeMillis()).coerceAtLeast(0L))
    }

    LaunchedEffect(blockUntil) {
        if (blockUntil <= 0L) return@LaunchedEffect
        while (remainingMs > 0L) {
            delay(1000L)
            remainingMs = (blockUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        // The countdown running out is the earliest, most reliable signal the lock is over — the
        // expiry alarm can be late or missed entirely (see BlockApplication's cold-start sweep),
        // and until something sweeps Room the lock is still "active", so this screen would sit
        // at 0:00 while the accessibility service kept bouncing an app whose time was up. Sweep
        // first, then close.
        app.applicationScope.launch { app.repository.expireAllDue() }
        onExpired()
    }

    // Covers the other way a lock ends early: an unlock key applied in the main app while this
    // wall is still on screen. Watching activeLocks means the wall closes itself instead of
    // stranding the user on a countdown for a lock that no longer exists.
    //
    // Absence only counts *after* the lock has been seen once. This activity is routinely
    // launched during a cold start (the accessibility service reconnects, finds a locked app in
    // front, and kicks straight here), and at that point activeLocks is still the empty initial
    // value Room's Flow hasn't replaced yet — treating that first emission as "lock gone" would
    // dismiss the wall instantly and let the locked app right back through.
    LaunchedEffect(packageName) {
        if (packageName.isEmpty()) return@LaunchedEffect
        var seenActive = false
        app.repository.activeLocks.collectLatest { locks ->
            if (packageName in locks) {
                seenActive = true
            } else if (seenActive) {
                onExpired()
            }
        }
    }

    val startedAt = blockedAt
    val elapsedFraction = if (startedAt != null && blockUntil > startedAt) {
        ((blockUntil - remainingMs - startedAt).toFloat() / (blockUntil - startedAt)).coerceIn(0f, 1f)
    } else {
        null
    }
    val animatedFraction by animateFloatAsState(
        targetValue   = elapsedFraction ?: 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label         = "lockProgress",
    )

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ── countdown dial ────────────────────────────────────────────────
        Box(contentAlignment = Alignment.Center) {
            if (elapsedFraction != null) {
                CircularProgressIndicator(
                    progress    = { animatedFraction },
                    modifier    = Modifier.size(DIAL_SIZE_DP.dp),
                    color       = WallAccent,
                    trackColor  = WallTrack,
                    strokeWidth = 6.dp,
                    strokeCap   = StrokeCap.Round,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (identity?.icon != null) {
                    Image(
                        bitmap             = identity.icon,
                        contentDescription = null,
                        modifier           = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Text("🔒", style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (remainingMs > 0L) formatCountdown(remainingMs) else "0:00",
                    style      = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color      = Color.White,
                )
                Text(
                    "remaining",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        // ── what and until when ───────────────────────────────────────────
        Text(
            identity?.let { "${it.label} is locked" } ?: "This app is locked",
            style      = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White,
            textAlign  = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (remainingMs > 0L) "Unlocks at ${formatUnlockAt(blockUntil)}" else "Unlocking…",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
        )

        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                "You set this yourself. It can't be shortened from here — wait it out, or apply " +
                    "an unlock key from App Blocker.",
                style     = MaterialTheme.typography.bodySmall,
                color     = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(32.dp))

        // ── actions ───────────────────────────────────────────────────────
        Button(
            onClick  = onGoHome,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = WallAccent,
                contentColor   = Color(0xFF10131A),
            ),
        ) {
            Text("Go home", fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
            Text("Open App Blocker", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** Big enough to read the countdown inside it at arm's length, small enough for a short phone. */
private const val DIAL_SIZE_DP = 200
