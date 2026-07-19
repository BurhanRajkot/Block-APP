package com.blockapp.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.blockapp.android.BlockApplication
import com.blockapp.android.keys.KeyVerificationResult
import com.blockapp.android.keys.KeyVerifier
import kotlinx.coroutines.launch

@Composable
fun UnlockKeyScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlockApplication
    val scope = rememberCoroutineScope()
    var keyText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("Enter unlock key", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = { Text("Key") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    when (val result = KeyVerifier.verify(keyText)) {
                        is KeyVerificationResult.Valid -> scope.launch {
                            val applied = app.repository.applyUnlockKey(
                                result.payload.targetPackage,
                                result.payload.newUntil,
                                result.payload.nonce,
                            )
                            if (applied) {
                                onDone()
                            } else {
                                message = "This key has already been used."
                            }
                        }
                        KeyVerificationResult.Invalid -> message = "Invalid key."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Apply key")
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }
        }
    }
}
