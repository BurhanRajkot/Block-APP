package com.blockapp.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.MainActivity
import com.blockapp.android.ui.theme.BlockAppTheme
import com.blockapp.android.ui.theme.Ink800
import com.blockapp.android.ui.theme.Ink950
import com.blockapp.android.ui.theme.Lime
import com.blockapp.android.ui.theme.LimeInk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ── screen-specific palette ───────────────────────────────────────────────────
// The app's own graphite and Lime, but with nothing else on screen: it has to read as a wall
// the user has hit, not as another page of the app they can interact their way out of. Dark,
// to match the dark window theme set on this activity in the manifest, so there's no flash on
// launch.
private val WallBackground = Ink950
private val WallAccent     = Lime
private val WallTrack      = Ink800

/**
 * The wall shown when a locked app is bounced to the home screen (see
 * AppBlockAccessibilityService's Tier 2). It is informational only — there is deliberately no
 * control here that shortens or lifts the lock; the only ways out are waiting it out or an
 * unlock key applied from the main app.
 *
 * Also the "reel limit reached" wall, when [EXTRA_REEL_LIMIT] is set: the service backs out of
 * a reel viewer once today's total hits util/ReelLimit, and shows this counting down to
 * midnight. Same look and the same lack of a way out, but no lock behind it, so it doesn't
 * watch activeLocks.
 */
class BlockOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCK_UNTIL = "block_until"
        const val EXTRA_PACKAGE_NAME = "package_name"

        /**
         * Today's reel limit, for the reel wall. 0 is a real limit ("no reels"), so absence is
         * [NOT_A_REEL_WALL], and an app lock never sets it. A reel-free-hours wall with no
         * limit set passes a negative value together with [EXTRA_QUIET_FROM].
         */
        const val EXTRA_REEL_LIMIT = "reel_limit"

        /** Start of the reel-free window, epoch millis, for a reel-free-hours wall. */
        const val EXTRA_QUIET_FROM = "quiet_from"

        private const val NOT_A_REEL_WALL = Int.MIN_VALUE
    }

    private var blockUntilState by mutableLongStateOf(0L)
    private var packageNameState by mutableStateOf("")
    private var reelLimitState by mutableIntStateOf(NOT_A_REEL_WALL)
    private var quietFromState by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)

        // Back would otherwise pop this activity and reveal whatever is beneath it — which, when
        // the launcher animation hasn't settled yet, can be the locked app itself. Going home
        // explicitly means back can never be the shortcut around the block; the watchdog would
        // catch it a poll later either way, but not before a readable glimpse of the app.
        onBackPressedDispatcher.addCallback(this) { goHome() }

        setContent {
            BlockAppTheme(darkTheme = true) {
                Surface(color = WallBackground) {
                    BlockOverlayContent(
                        packageName = packageNameState,
                        blockUntil  = blockUntilState,
                        reelLimit   = reelLimitState,
                        isReelWall  = reelLimitState != NOT_A_REEL_WALL,
                        quietFrom   = quietFromState,
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
        reelLimitState   = intent.getIntExtra(EXTRA_REEL_LIMIT, NOT_A_REEL_WALL)
        quietFromState   = intent.getLongExtra(EXTRA_QUIET_FROM, 0L)
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
    reelLimit: Int,
    isReelWall: Boolean,
    quietFrom: Long,
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
    val isReelLimit = isReelWall
    val isQuiet = isReelWall && quietFrom > 0L
    var blockedAt by remember(packageName) { mutableStateOf<Long?>(null) }
    LaunchedEffect(packageName, isReelLimit, quietFrom) {
        if (isQuiet) {
            // A reel-free window's ring runs from the window's start to its end.
            blockedAt = quietFrom
        } else if (isReelLimit) {
            // The reel-limit ring shows how far through the day it is: it runs out at midnight.
            blockedAt = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        } else if (packageName.isNotEmpty()) {
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
    LaunchedEffect(packageName, isReelLimit) {
        if (packageName.isEmpty() || isReelLimit) return@LaunchedEffect
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
        // A slow breathing glow behind the dial, and a short "nope" shake of the app icon when
        // the wall appears: the bounce should read as the app refusing, not as a crash.
        val breath by rememberInfiniteTransition(label = "breath").animateFloat(
            initialValue = 0.10f,
            targetValue = 0.22f,
            animationSpec = infiniteRepeatable(tween(BREATH_MS), RepeatMode.Reverse),
            label = "glow",
        )
        val shake = remember { Animatable(0f) }
        LaunchedEffect(packageName, blockUntil) {
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = SHAKE_MS
                    -14f at 60
                    12f at 130
                    -8f at 200
                    5f at 270
                    -2f at 330
                },
            )
        }
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size((DIAL_SIZE_DP * 1.5f).dp)) {
                // A thin ring that breathes, not a glow: the soft radial wash it replaced was part
                // of what made the app look generated.
                drawCircle(
                    color = WallAccent.copy(alpha = breath),
                    radius = size.minDimension / 2f - 2.dp.toPx(),
                    style = Stroke(1.5.dp.toPx()),
                )
            }
            if (elapsedFraction != null) {
                CircularProgressIndicator(
                    progress    = { animatedFraction },
                    modifier    = Modifier.size(DIAL_SIZE_DP.dp),
                    color       = WallAccent,
                    trackColor  = WallTrack,
                    strokeWidth = 8.dp,
                    strokeCap   = StrokeCap.Round,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (identity?.icon != null) {
                    Image(
                        bitmap             = identity.icon,
                        contentDescription = null,
                        modifier           = Modifier
                            .graphicsLayer { translationX = shake.value.dp.toPx() }
                            .size(56.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                RollingText(
                    if (remainingMs > 0L) formatCountdown(remainingMs) else "0:00",
                    style      = MaterialTheme.typography.displayMedium,
                    color      = Color.White,
                )
                Text(
                    "REMAINING",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        // ── what and until when ───────────────────────────────────────────
        Text(
            when {
                isQuiet -> "Reel-free hours"
                isReelLimit && reelLimit == 0 -> "Reels are off"
                isReelLimit -> "Reel limit reached"
                identity != null -> "${identity.label} is locked"
                else -> "This app is locked"
            },
            style      = MaterialTheme.typography.headlineMedium,
            color      = Color.White,
            textAlign  = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                isQuiet -> "Reels are back at ${formatUnlockAt(blockUntil)}."
                isReelLimit && reelLimit == 0 -> "Your plan has you reel-free today."
                isReelLimit -> "That's your $reelLimit for today. Reels are back at midnight."
                remainingMs > 0L -> "Unlocks at ${formatUnlockAt(blockUntil)}"
                else -> "Unlocking…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                if (isQuiet) {
                    "You chose these hours in your reel plan. The rest of the app still works; only reels are off."
                } else if (isReelLimit) {
                    "You set this limit yourself. The rest of the app still works; only reels are off."
                } else {
                    "You set this yourself. It can't be shortened from here — wait it out."
                },
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
                .height(56.dp),
            shape    = RoundedCornerShape(50),
            colors   = ButtonDefaults.buttonColors(
                containerColor = WallAccent,
                contentColor   = LimeInk,
            ),
        ) {
            Text("Go home", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
            Text("Open App Blocker", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

/** Big enough to read the countdown inside it at arm's length, small enough for a short phone. */
private const val DIAL_SIZE_DP = 232

private const val BREATH_MS = 2_400
private const val SHAKE_MS = 380
