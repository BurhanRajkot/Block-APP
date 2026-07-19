package com.blockapp.android.keys

/**
 * Run keygen/generate_keypair.py once, then replace PUBLIC_KEY_BASE64 below with the value
 * it prints. Only the public key ever lives here — the matching private key stays on the
 * developer's machine in keygen/keys/private_key.pem and is what actually mints unlock keys.
 */
object PublicKeyProvider {
    const val PUBLIC_KEY_BASE64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1gdM8BCBcUNPoNYAU/bJ51qSZAWn7qhQfLGBm4iXHTag8h05UNSdZvBZXI0ebFclANj/yHNjlmCSkBTBwp9F/EC1zpru7CbFzjtEfQSnD8UXSgce7xWDrFivOGe2Fe/eDlBxejv1VjKqh08khpxP/x/+NkRyD9MBWCLZPTxG+iknkyuN0VZVeBdQ7iICKOg2wvybSORwWlCMUz4ND5uIHZrGBtlxr/YzTGMq9d3hWkSiJD2WgUXCktTcTwWpaFiQbt90n4XckBH/PgxvknmV8kaIq/c8Sax2OTebJUPnV2hh2+4EVtdcyydkfJqVdvn+e8ziFBlUea6KizZA8zEHfwIDAQAB"
}
