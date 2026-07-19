"""
One-time setup: generates the RSA keypair used to sign/verify unlock keys.

Run once:
    python3 keygen/generate_keypair.py

Writes keygen/keys/private_key.pem (keep this file secret — never commit it or put it in
the Android app) and prints a base64 public key to paste into
app/src/main/java/com/blockapp/android/keys/PublicKeyProvider.kt
"""
import base64
import os

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

KEYS_DIR = os.path.join(os.path.dirname(__file__), "keys")
PRIVATE_KEY_PATH = os.path.join(KEYS_DIR, "private_key.pem")


def main() -> None:
    os.makedirs(KEYS_DIR, exist_ok=True)
    if os.path.exists(PRIVATE_KEY_PATH):
        raise SystemExit(f"{PRIVATE_KEY_PATH} already exists, refusing to overwrite.")

    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    with open(PRIVATE_KEY_PATH, "wb") as f:
        f.write(
            private_key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption(),
            )
        )

    public_key_der = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_key_b64 = base64.b64encode(public_key_der).decode("ascii")

    print(f"Private key written to {PRIVATE_KEY_PATH} -- keep this file secret.")
    print()
    print("Paste this into PublicKeyProvider.kt as PUBLIC_KEY_BASE64:")
    print(public_key_b64)


if __name__ == "__main__":
    main()
