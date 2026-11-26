#!/usr/bin/env python3
"""Provision the PUMP-only Vault mount, audit device, and access policies."""

import argparse
import http.client
import json
import os
import re
import socket
import ssl
import stat
import sys
from pathlib import Path
from urllib.parse import urlsplit


ISSUER = "https://gitlab.credit-cabinet.ru"
RELEASE_REF = "feat/final-platform-release"
GITLAB_AUTH = "pump-gitlab"
AUDIT_PATH = "/var/log/pump-vault/audit.jsonl"
KUBERNETES_SECRETS = (
    "cabinet-secrets",
    "cabinet-s3-app",
    "cabinet-s3-admin",
    "engagement-ollama-ssh",
    "cabinet-prod-registry",
)


class VaultError(RuntimeError):
    pass


def https_origin(value):
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.path or parsed.query or parsed.fragment):
        raise ValueError("use the exact Vault HTTPS origin, including its port")
    return parsed


class TunnelConnection(http.client.HTTPSConnection):
    def __init__(self, host, port, tunnel_port, **options):
        super().__init__(host, port, **options)
        self.tunnel_port = tunnel_port

    def connect(self):
        raw = socket.create_connection(("127.0.0.1", self.tunnel_port), self.timeout)
        try:
            self.sock = self._context.wrap_socket(raw, server_hostname=self.host)
        except OSError:
            raw.close()
            raise


class Vault:
    def __init__(self, origin, ca, token, tunnel_port=None):
        self.origin = https_origin(origin)
        self.context = ssl.create_default_context(cafile=str(ca))
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2
        self.token = token
        self.tunnel_port = tunnel_port

    def request(self, method, path, payload=None):
        options = dict(timeout=12, context=self.context)
        address = (self.origin.hostname, self.origin.port or 443)
        connection = (TunnelConnection(*address, self.tunnel_port, **options) if self.tunnel_port
                      else http.client.HTTPSConnection(*address, **options))
        headers = {"X-Vault-Token": self.token} if self.token else {}
        data = None
        if payload is not None:
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        try:
            connection.request(method, f"/v1/{path}", body=data, headers=headers)
            with connection.getresponse() as response:
                if response.status not in (200, 204):
                    raise VaultError(f"{method} {path}: HTTP {response.status}")
                raw = response.read(1024 * 1024 + 1)
                if len(raw) > 1024 * 1024:
                    raise VaultError(f"{method} {path}: unexpected response size")
                return json.loads(raw) if raw else {}
        finally:
            connection.close()


def read_token(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "r", encoding="ascii") as token_file:
        info = os.fstat(token_file.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o600:
            raise ValueError("token file must be a regular owner-owned file with mode 0600")
        token = token_file.read(4096).strip()
    if not re.fullmatch(r"[A-Za-z0-9_.-]{16,2048}", token):
        raise ValueError("invalid token file format")
    return token


def policies():
    items = {
        "pump-ci-dev": {"pump/data/dev/ci-deploy": ["read"]},
        "pump-ci-prod": {"pump/data/prod/ci-deploy": ["read"]},
        "pump-seed-ci-dev": {"pump/data/dev/ci-deploy": ["create", "read"]},
        "pump-seed-ci-prod": {"pump/data/prod/ci-deploy": ["create", "read"]},
        "pump-dev-compose": {"pump/data/dev/compose": ["read"]},
        "pump-seed-dev-compose": {"pump/data/dev/compose": ["create", "read"]},
        "pump-prod-kubernetes": {f"pump/data/prod/kubernetes/{name}": ["read"]
                                 for name in KUBERNETES_SECRETS},
        "pump-seed-prod-kubernetes": {f"pump/data/prod/kubernetes/{name}": ["create", "read"]
                                      for name in KUBERNETES_SECRETS},
        "pump-raft-snapshot": {"sys/storage/raft/snapshot": ["read"]},
    }
    for rules in items.values():
        rules["auth/token/revoke-self"] = ["update"]
    return items


def render_policy(rules):
    return "\n".join(f'path "{path}" {{ capabilities = {json.dumps(capabilities)} }}'
                     for path, capabilities in sorted(rules.items())) + "\n"


def gitlab_role(origin, environment, runner_id):
    policy = "pump-ci-dev" if environment == "dev" else "pump-ci-prod"
    return {
        "role_type": "jwt",
        "user_claim": "job_id",
        "bound_audiences": [origin],
        "bound_claims_type": "string",
        "bound_claims": {
            "job_project_id": "1",
            "job_project_path": "pump/smart-credit-cabinet",
            "ref": RELEASE_REF,
            "ref_path": f"refs/heads/{RELEASE_REF}",
            "ref_type": "branch",
            "ref_protected": "true",
            "pipeline_source": ["api", "push", "web"],
            "runner_id": runner_id,
            "environment": environment,
            "environment_action": "start",
        },
        "token_type": "service",
        "token_policies": [policy],
        "token_no_default_policy": True,
        "token_ttl": 120,
        "token_max_ttl": 180,
        "token_explicit_max_ttl": 180,
    }


def configure(vault, origin):
    # Enable audit before application data or external authentication is installed.
    auditors = vault.request("GET", "sys/audit").get("data", {})
    audit = auditors.get("pump-file/")
    if audit is None:
        vault.request("POST", "sys/audit/pump-file", {
            "type": "file", "options": {"file_path": AUDIT_PATH, "mode": "0600"},
        })
    elif audit.get("type") != "file" or audit.get("options", {}).get("file_path") != AUDIT_PATH:
        raise VaultError("existing PUMP audit device does not match the expected file")

    mount = vault.request("GET", "sys/mounts").get("data", {}).get("pump/")
    if mount is None:
        vault.request("POST", "sys/mounts/pump", {"type": "kv", "options": {"version": "2"}})
    elif mount.get("type") != "kv" or mount.get("options", {}).get("version") != "2":
        raise VaultError("existing pump mount is not KV v2")
    vault.request("POST", "pump/config", {"cas_required": True})

    for name, rules in policies().items():
        vault.request("PUT", f"sys/policies/acl/{name}", {"policy": render_policy(rules)})

    auth = vault.request("GET", "sys/auth").get("data", {}).get(f"{GITLAB_AUTH}/")
    if auth is None:
        vault.request("POST", f"sys/auth/{GITLAB_AUTH}", {"type": "jwt"})
    elif auth.get("type") != "jwt":
        raise VaultError("existing pump-gitlab mount has a different auth method")
    vault.request("POST", f"auth/{GITLAB_AUTH}/config", {
        "oidc_discovery_url": ISSUER,
        "bound_issuer": ISSUER,
        "jwt_supported_algs": ["RS256"],
    })
    for name, environment, runner_id in (("pump-ci-dev", "dev", 2), ("pump-ci-prod", "production", 4)):
        role = gitlab_role(origin, environment, runner_id)
        path = f"auth/{GITLAB_AUTH}/role/{name}"
        vault.request("POST", path, role)
        actual = vault.request("GET", path).get("data", {})
        if any(actual.get(key) != role[key] for key in ("bound_audiences", "bound_claims", "token_policies",
                                                       "token_no_default_policy", "token_explicit_max_ttl")):
            raise VaultError(f"role {name} read-back mismatch")
    if vault.request("GET", "pump/config").get("data", {}).get("cas_required") is not True:
        raise VaultError("PUMP KV v2 CAS is not enabled")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", required=True, help="exact Vault HTTPS origin with port")
    parser.add_argument("--ca", required=True, type=Path, help="trusted PEM CA")
    parser.add_argument("--token-file", required=True, type=Path, help="protected bootstrap token file (0600)")
    parser.add_argument("--tunnel-port", type=int, help="optional local SSH forwarding port, preserving HTTPS name")
    args = parser.parse_args()
    if args.tunnel_port is not None and not 1 <= args.tunnel_port <= 65535:
        parser.error("invalid local tunnel port")
    try:
        vault = Vault(args.address, args.ca, read_token(args.token_file), args.tunnel_port)
        configure(vault, args.address)
    except (ValueError, OSError, VaultError, http.client.HTTPException) as error:
        message = str(error) if type(error) in (ValueError, VaultError) and not isinstance(error, json.JSONDecodeError) else type(error).__name__
        print(f"Vault configuration failed ({message})", file=sys.stderr)
        return 1
    print("Configured PUMP KV v2 CAS, file audit, scoped policies and two protected GitLab JWT roles")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
