#!/usr/bin/env python3
"""Create a fresh signing key and synthetic sessions for a disposable, offline Credit process."""

from base64 import urlsafe_b64encode
import hashlib
import hmac
import json
import secrets
import time
from uuid import uuid4


def encode(data):
    return urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def session(secret, role, company=None):
    header = encode(b'{"alg":"HS256","typ":"JWT"}')
    claims = {"sub": str(uuid4()), "role": role, "name": "Alpha QA", "exp": int(time.time()) + 7200}
    if company is not None:
        claims["cid"] = f"20000000-0000-4000-8000-{company:012d}"
        claims["sign"] = True
    content = header + "." + encode(json.dumps(claims, separators=(",", ":")).encode())
    return content + "." + encode(hmac.digest(secret.encode(), content.encode(), hashlib.sha256))


if __name__ == "__main__":
    # 32 ASCII bytes select HS256 in the platform verifier.
    key = secrets.token_hex(16)
    print(f"export JWT_SECRET={key}")
    print("export IDENTITY_URL=")
    for variable, role, company in (
        ("CLIENT", "CLIENT", 5),
        ("UNRELATED_CLIENT", "CLIENT", 10),
        ("AGENT", "AGENT", None),
        ("MANAGER", "MANAGER", None),
        ("UNDERWRITER", "UNDERWRITER", None),
        ("RISK_ADMIN", "RISK_ADMIN", None),
    ):
        print(f"export ALPHA_QA_{variable}_TOKEN={session(key, role, company)}")
    print("export ALPHA_QA_STUCK_CLIENT_TOKEN=$ALPHA_QA_UNRELATED_CLIENT_TOKEN")
    print("export ALPHA_QA_RECOVERY_CLIENT_TOKEN=$ALPHA_QA_CLIENT_TOKEN")
