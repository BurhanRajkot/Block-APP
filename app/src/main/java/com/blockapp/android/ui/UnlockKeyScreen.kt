package com.blockapp.android.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.keys.KeyVerificationResult
import com.blockapp.android.keys.KeyVerifier
import kotlinx.coroutines.launch

// Shared status palette — see OnboardingScreen for why these stay literal rather than themed.
private val AppliedGreen   = Color(0xFF2E7D32)
private val RejectedRed    = Color(0xFFB71C1C)
private val HeadsUpAmber   = Color(0xFFB26A00)

/** What to tell the user after a key was submitted, and how loudly. */
private data class KeyOutcome(val tone: Color, val icon: String, val title: String, val detail: String)

/**
 * Applies an offline-signed unlock key (see keys/KeyVerifier.kt and keygen/generate_key.py).
 *
 * Every submission ends in an explicit, named outcome rather than a silent navigation. A key is
 * single-use — the nonce is burned the moment it verifies, whether or not it found a lock to act
 * on — so "nothing visibly happened" is the one response this screen must never give: the user
 * would reasonably retype the same key and be told it was already used.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockKeyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    val scope = rememberCoroutineScope()

    var keyText by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<KeyOutcome?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Unlock key", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
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
            Text(
                "Paste a key generated on your PC with keygen/generate_key.py. It's verified " +
                    "offline against the public key built into this app — nothing is sent " +
                    "anywhere, and a key only works once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value         = keyText,
                onValueChange = {
                    keyText = it
                    // Clearing on edit stops a stale "already used" sitting under a key the user
                    // has since replaced, which reads as the new key having failed too.
                    outcome = null
                },
                label         = { Text("Key") },
                minLines      = 3,
                shape         = RoundedCornerShape(12.dp),
                modifier      = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Button(
                onClick  = {
                    when (val result = KeyVerifier.verify(keyText)) {
                        is KeyVerificationResult.Valid -> {
                            val payload = result.payload
                            // Sampled *before* applying: applyUnlockKey deactivates rows without
                            // reporting whether any matched, and this is the only way to tell a
                            // key that ended a lock from one that burned itself on nothing.
                            val hadLock = if (payload.targetPackage == "*") {
                                app.repository.activeLocks.value.isNotEmpty()
                            } else {
                                payload.targetPackage in app.repository.activeLocks.value
                            }
                            scope.launch {
                                val applied = app.repository.applyUnlockKey(
                                    payload.targetPackage,
                                    payload.newUntil,
                                    payload.nonce,
                                )
                                outcome = when {
                                    !applied -> KeyOutcome(
                                        tone   = RejectedRed,
                                        icon   = "⛔",
                                        title  = "Already used",
                                        detail = "This key was applied before. Keys are " +
                                            "single-use by design — generate a new one on " +
                                            "your PC.",
                                    )
                                    !hadLock -> KeyOutcome(
                                        tone   = HeadsUpAmber,
                                        icon   = "⚠️",
                                        title  = "Nothing to unlock",
                                        detail = "The key is valid, but " +
                                            targetLabel(payload.targetPackage) +
                                            " had no active lock. The key has been used up " +
                                            "regardless — mint a fresh one if you still need it.",
                                    )
                                    payload.newUntil > System.currentTimeMillis() -> KeyOutcome(
                                        tone   = AppliedGreen,
                                        icon   = "⏳",
                                        title  = "Lock extended",
                                        detail = targetLabel(payload.targetPackage) +
                                            " now unlocks at " +
                                            "${formatUnlockAt(payload.newUntil)}.",
                                    )
                                    else -> KeyOutcome(
                                        tone   = AppliedGreen,
                                        icon   = "🔓",
                                        title  = "Unlocked",
                                        detail = targetLabel(payload.targetPackage) +
                                            " can be opened again.",
                                    )
                                }
                                keyText = ""
                            }
                        }
                        KeyVerificationResult.Invalid -> outcome = KeyOutcome(
                            tone   = RejectedRed,
                            icon   = "⛔",
                            title  = "Not a valid key",
                            detail = "The signature didn't check out. Copy the whole line the " +
                                "script printed, including the dot in the middle.",
                        )
                    }
                },
                enabled  = keyText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = RoundedCornerShape(14.dp),
            ) {
                Text("Apply key", fontWeight = FontWeight.SemiBold)
            }

            outcome?.let { result ->
                Spacer(Modifier.height(16.dp))
                OutcomeCard(result)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick  = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(14.dp),
                ) {
                    Text("Done")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OutcomeCard(outcome: KeyOutcome) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .background(outcome.tone.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(outcome.icon, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                outcome.title,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = outcome.tone,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                outcome.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun targetLabel(targetPackage: String): String =
    if (targetPackage == "*") "Every locked app" else targetPackage
