#!/usr/bin/env python3
"""Provision the paired GoAlert secrets for its dedicated PostgreSQL."""

import argparse
from base64 import b64decode
import json
import os
from pathlib import Path
import secrets
import subprocess
from tempfile import TemporaryDirectory
from urllib.parse import quote, urlsplit


def db_url(namespace, app_password):
    password = quote(app_password, safe="")
    host = f"cabinet-goalert-db.{namespace}.svc:5432"
    return f"postgres://goalert:{password}@{host}/goalert?sslmode=disable"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kubeconfig", required=True, type=Path)
    parser.add_argument("--context", required=True)
    parser.add_argument("--namespace", default="monitoring")
    args = parser.parse_args()
    kubectl = [
        "kubectl", "--kubeconfig", str(args.kubeconfig), "--context", args.context,
        "--namespace", args.namespace, "--request-timeout=10s",
    ]

    def read_secret(name):
        payload = subprocess.check_output(
            [*kubectl, "get", "secret", name, "--ignore-not-found", "-o", "json"], text=True
        )
        if not payload.strip():
            return None
        return {key: b64decode(value).decode() for key, value in json.loads(payload).get("data", {}).items()}

    def create_secret(name, entries):
        with TemporaryDirectory(prefix="pump-goalert-secret-") as dirname:
            paths = []
            for key, value in entries.items():
                filename = Path(dirname) / key
                filename.write_text(value)
                paths.append(f"--from-file={key}={filename}")
            subprocess.run([*kubectl, "create", "secret", "generic", name, *paths], check=True)

    os.umask(0o077)
    db = read_secret("cabinet-goalert-db")
    app = read_secret("cabinet-goalert")
    if app is not None and db is None:
        parser.exit(1, "GoAlert Secret already exists without the managed DB Secret. Keep its existing credentials.\n")
    if db is not None and not all(db.get(key) for key in ["admin-password", "app-password"]):
        parser.exit(1, "Managed GoAlert DB Secret is missing a password key.\n")
    if db is None:
        db = {"admin-password": secrets.token_hex(32), "app-password": secrets.token_hex(32)}
        create_secret("cabinet-goalert-db", db)
    expected_url = db_url(args.namespace, db["app-password"])
    if app is not None:
        if urlsplit(app.get("db-url", "")) != urlsplit(expected_url) or not app.get("data-encryption-key"):
            parser.exit(1, "Existing GoAlert Secret does not match the managed DB. Resolve the pairing before deployment.\n")
        print("Existing GoAlert Secrets match.")
    else:
        create_secret("cabinet-goalert", {"db-url": expected_url, "data-encryption-key": secrets.token_hex(32)})


if __name__ == "__main__":
    main()
