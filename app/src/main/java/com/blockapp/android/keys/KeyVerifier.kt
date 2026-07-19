package com.blockapp.android.keys

import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

data class UnlockPayload(val targetPackage: String, val newUntil: Long, val nonce: String)

sealed class KeyVerificationResult {
    data class Valid(val payload: UnlockPayload) : KeyVerificationResult()
    data object Invalid : KeyVerificationResult()
}

/**
 * Verifies unlock keys minted offline by keygen/generate_key.py. Key shape:
 * base64url(payload) + "." + base64url(signature), where payload is
 * "packageName|newUnlockEpochMillis|nonce" signed with SHA256withRSA. Only the embedded
 * public key is needed here — a valid signature could only have come from the private key
 * the developer keeps offline.
 */
object KeyVerifier {

    fun verify(keyString: String): KeyVerificationResult {
        val parts = keyString.trim().split(".")
        if (parts.size != 2) return KeyVerificationResult.Invalid

        return try {
            val payloadBytes = Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP)
            val signatureBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)

            val publicKeyBytes = Base64.decode(PublicKeyProvider.PUBLIC_KEY_BASE64, Base64.NO_WRAP)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))

            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            signature.update(payloadBytes)
            if (!signature.verify(signatureBytes)) return KeyVerificationResult.Invalid

            val fields = String(payloadBytes, Charsets.UTF_8).split("|")
            if (fields.size != 3) return KeyVerificationResult.Invalid

            KeyVerificationResult.Valid(
                UnlockPayload(
                    targetPackage = fields[0],
                    newUntil = fields[1].toLong(),
                    nonce = fields[2],
                ),
            )
        } catch (e: Exception) {
            KeyVerificationResult.Invalid
        }
    }
}
