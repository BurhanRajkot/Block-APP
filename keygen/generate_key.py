"""
Generates a signed unlock key for the Android app. Requires keys/private_key.pem to already
exist (run generate_keypair.py first).

Usage:
    python3 keygen/generate_key.py <package_name|*> <now|hours_from_now>

Examples:
    python3 keygen/generate_key.py com.instagram.android now
    python3 keygen/generate_key.py com.instagram.android 2
    python3 keygen/generate_key.py "*" now
"""
import base64
import os
import secrets
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

PRIVATE_KEY_PATH = os.path.join(os.path.dirname(__file__), "keys", "private_key.pem")


def b64url(data: bytes) -> str:
    # Keep padding ("=") -- the Kotlin side decodes with Base64.URL_SAFE, which expects it.
    return base64.urlsafe_b64encode(data).decode("ascii")


def main() -> None:
    if len(sys.argv) != 3:
        print(__doc__)
        raise SystemExit(1)

    target_package, when = sys.argv[1], sys.argv[2]
    new_until = 0 if when == "now" else int(time.time() * 1000) + int(float(when) * 3600 * 1000)
    nonce = secrets.token_hex(8)
    payload = f"{target_package}|{new_until}|{nonce}".encode("utf-8")

    with open(PRIVATE_KEY_PATH, "rb") as f:
        private_key = serialization.load_pem_private_key(f.read(), password=None)

    signature = private_key.sign(payload, padding.PKCS1v15(), hashes.SHA256())

    print(f"{b64url(payload)}.{b64url(signature)}")


if __name__ == "__main__":
    main()
