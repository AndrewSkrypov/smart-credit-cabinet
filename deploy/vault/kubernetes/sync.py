#!/usr/bin/env python3
"""Verify or materialize allowlisted Vault KV v2 values into existing Prod Secrets."""

import argparse
import base64
import binascii
from dataclasses import dataclass, field
import json
import os
from pathlib import Path
import re
import ssl
import stat
import sys
import urllib.error
import urllib.parse
import urllib.request


NAMESPACE = "cabinet-prod"
NAMESPACE_UID = "ca101720-31ee-4247-9786-a51ec69f62c7"
SECRET_TYPES = {
    "cabinet-secrets": "Opaque",
    "cabinet-s3-app": "Opaque",
    "cabinet-s3-admin": "Opaque",
    "cabinet-prod-registry": "kubernetes.io/dockerconfigjson",
    "engagement-ollama-ssh": "Opaque",
}
SECRET_KEYS = {
    "cabinet-secrets": {
        "JWT_SECRET", "VALKEY_PASSWORD", "POSTGRES_PASSWORD", "SIM_API_KEY",
        "IDENTITY_LINK_KEY", "IDENTITY_DB_PASSWORD", "CREDIT_DB_PASSWORD",
        "ENGAGEMENT_DB_PASSWORD", "IDENTITY_MIGRATION_PASSWORD",
        "CREDIT_MIGRATION_PASSWORD", "ENGAGEMENT_MIGRATION_PASSWORD",
    },
    "cabinet-s3-app": {"S3_ACCESS_KEY", "S3_SECRET_KEY"},
    "cabinet-s3-admin": {"S3_ADMIN_ACCESS_KEY", "S3_ADMIN_SECRET_KEY"},
    "cabinet-prod-registry": {".dockerconfigjson"},
    "engagement-ollama-ssh": {"id_ed25519", "known_hosts"},
}
VAULT_PATH = "/v1/pump/data/prod/kubernetes/"
KUBE_NAMESPACE_PATH = f"/api/v1/namespaces/{NAMESPACE}"
KUBE_SECRET_PATH = KUBE_NAMESPACE_PATH + "/secrets/"
MAX_RESPONSE = 4 * 1024 * 1024
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_.-]{1,8192}")


class SyncError(Exception):
    """An error whose message is safe to log."""


class HttpError(SyncError):
    def __init__(self, operation, status):
        self.status = status
        super().__init__(f"{operation}: HTTP {status}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class HttpsApi:
    def __init__(self, name, origin, ca_path, token_header, token):
        try:
            parsed = urllib.parse.urlsplit(origin)
            valid_port = parsed.port is None or 0 < parsed.port <= 65535
        except ValueError:
            raise SyncError(f"{name}: invalid HTTPS origin") from None
        if (
            parsed.scheme != "https" or not parsed.hostname or not valid_port
            or parsed.username is not None or parsed.path not in ("", "/")
            or parsed.query or parsed.fragment or any(c.isspace() for c in origin)
        ):
            raise SyncError(f"{name}: expected HTTPS origin with no user information or path")
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        try:
            context.load_verify_locations(cafile=ca_path)
        except (OSError, ValueError):
            raise SyncError(f"{name}: unable to load the designated CA file") from None
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}),
            urllib.request.HTTPSHandler(context=context),
            NoRedirect(),
        )
        self.name = name
        self.origin = f"https://{parsed.netloc}"
        self.headers = {token_header: token, "Accept": "application/json"}

    def request(self, method, path, label, payload=None, content_type="application/json"):
        operation = f"{self.name} {method} {label}"
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        headers = dict(self.headers)
        if body is not None:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.origin + path, data=body, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=15) as response:
                raw = response.read(MAX_RESPONSE + 1)
        except urllib.error.HTTPError as error:
            error.close()
            raise HttpError(operation, error.code) from None
        except (OSError, ValueError):
            raise SyncError(f"{operation}: TLS or transport failure") from None
        if len(raw) > MAX_RESPONSE:
            raise SyncError(f"{operation}: response exceeds size limit")
        if not raw:
            return {}
        return decode_object(raw, operation)


def decode_object(raw, label):
    try:
        payload = json.loads(raw)
    except (ValueError, RecursionError):
        raise SyncError(f"{label}: invalid JSON") from None
    if not isinstance(payload, dict):
        raise SyncError(f"{label}: expected JSON object")
    return payload


def read_private(path, limit, label):
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as stream:
            metadata = os.fstat(stream.fileno())
            if (
                not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid()
                or stat.S_IMODE(metadata.st_mode) not in (0o400, 0o600)
            ):
                raise SyncError(f"{label}: requires an owner-only regular file (0400 or 0600)")
            value = stream.read(limit + 1)
    except OSError:
        raise SyncError(f"{label}: unable to read protected file") from None
    if len(value) > limit:
        raise SyncError(f"{label}: size limit exceeded")
    return value


def read_token(path, label):
    raw = sys.stdin.buffer.read(8193) if path == "-" else read_private(path, 8192, label)
    try:
        token = raw.decode("ascii").strip()
    except UnicodeError:
        raise SyncError(f"{label}: invalid token format") from None
    if len(raw) > 8192 or not TOKEN_PATTERN.fullmatch(token):
        raise SyncError(f"{label}: invalid token format")
    return token


def validate_data(name, values):
    allowed = SECRET_KEYS[name]
    external_s3 = SECRET_KEYS["cabinet-s3-app"]
    if not isinstance(values, dict) or not (
        values.keys() == allowed or (name == "cabinet-secrets" and values.keys() == allowed | external_s3)
    ):
        raise SyncError(f"{name}: Secret key set does not match its approved schema")
    for encoded in values.values():
        try:
            decoded = base64.b64decode(encoded, validate=True)
        except (binascii.Error, ValueError, TypeError):
            raise SyncError(f"{name}: invalid base64 value") from None
        if not decoded or base64.b64encode(decoded).decode("ascii") != encoded:
            raise SyncError(f"{name}: expected non-empty canonical base64 data")
    if name == "cabinet-prod-registry":
        config = decode_object(base64.b64decode(values[".dockerconfigjson"]), name)
        auths = config.get("auths")
        host = "registry.credit-cabinet.ru"
        if config.keys() != {"auths"} or not isinstance(auths, dict) or auths.keys() != {host}:
            raise SyncError(f"{name}: expected a dedicated Cabinet Registry auth entry")
        entry = auths[host]
        try:
            auth = base64.b64decode(entry["auth"], validate=True)
            username, separator, password = auth.partition(b":")
        except (binascii.Error, KeyError, TypeError, ValueError):
            raise SyncError(f"{name}: malformed Cabinet Registry auth entry") from None
        if not (username and separator and password):
            raise SyncError(f"{name}: malformed Cabinet Registry auth entry")
    return values


@dataclass(frozen=True)
class Secret:
    name: str
    uid: str
    resource_version: str
    immutable: bool
    data: dict = field(repr=False)


def check_namespace(api):
    namespace = api.request("GET", KUBE_NAMESPACE_PATH, NAMESPACE)
    metadata = namespace.get("metadata")
    if (
        namespace.get("kind") != "Namespace" or not isinstance(metadata, dict)
        or metadata.get("name") != NAMESPACE or metadata.get("uid") != NAMESPACE_UID
        or metadata.get("deletionTimestamp") is not None
    ):
        raise SyncError("Kubernetes namespace identity or lifecycle mismatch")


def kube_snapshot(name, payload):
    metadata = payload.get("metadata")
    if (
        not isinstance(metadata, dict) or payload.get("kind") != "Secret"
        or metadata.get("name") != name or metadata.get("namespace") != NAMESPACE
        or metadata.get("deletionTimestamp") is not None
        or payload.get("type") != SECRET_TYPES[name]
    ):
        raise SyncError(f"{name}: Kubernetes Secret identity, lifecycle or type mismatch")
    uid = metadata.get("uid")
    version = metadata.get("resourceVersion")
    if not isinstance(uid, str) or not uid or not isinstance(version, str) or not version:
        raise SyncError(f"{name}: missing Kubernetes UID or resourceVersion")
    return Secret(name, uid, version, payload.get("immutable") is True, validate_data(name, payload.get("data")))


def read_kube(api, name):
    return kube_snapshot(name, api.request("GET", KUBE_SECRET_PATH + name, name))


def read_vault(api, name, version=None):
    query = "" if version is None else f"?version={version}"
    frame = api.request("GET", VAULT_PATH + name + query, name).get("data")
    metadata = frame.get("metadata") if isinstance(frame, dict) else None
    if not isinstance(metadata, dict):
        raise SyncError(f"{name}: missing Vault version metadata")
    actual = metadata.get("version")
    if (
        type(actual) is not int or actual < 1 or (version is not None and actual != version)
        or metadata.get("destroyed") is not False or metadata.get("deletion_time") != ""
    ):
        raise SyncError(f"{name}: Vault version is invalid, deleted or destroyed")
    return actual, validate_data(name, frame.get("data"))


def compare_keys(name, before, after):
    if before.keys() != after.keys():
        raise SyncError(f"{name}: Kubernetes and Vault Secret key sets differ")


def patch_secret(api, before, after, dry_run=False):
    name = before.name
    if before.immutable:
        raise SyncError(f"{name}: Kubernetes Secret is immutable")
    patch = [
        {"op": "test", "path": "/metadata/uid", "value": before.uid},
        {"op": "test", "path": "/metadata/resourceVersion", "value": before.resource_version},
        {"op": "test", "path": "/type", "value": SECRET_TYPES[name]},
        {"op": "replace", "path": "/data", "value": after},
    ]
    query = "?dryRun=All" if dry_run else ""
    result = api.request(
        "PATCH", KUBE_SECRET_PATH + name + query, name, patch, "application/json-patch+json"
    )
    current = kube_snapshot(name, result)
    if current.uid != before.uid or current.data != after:
        raise SyncError(f"{name}: Kubernetes admission or response changed the requested data")


def write_journal(path, before, after, version):
    journal = {
        "format": 1,
        "namespace_uid": NAMESPACE_UID,
        "name": before.name,
        "uid": before.uid,
        "type": SECRET_TYPES[before.name],
        "vault_version": version,
        "previous": before.data,
        "desired": after,
    }
    try:
        parent = os.open(path.parent, os.O_DIRECTORY | os.O_RDONLY | os.O_NOFOLLOW)
        try:
            metadata = os.fstat(parent)
            if metadata.st_uid != os.geteuid() or stat.S_IMODE(metadata.st_mode) != 0o700:
                raise SyncError("rollback journal directory must be owned by the operator with mode 0700")
            descriptor = os.open(
                path.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent
            )
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(json.dumps(journal).encode("utf-8"))
                stream.flush()
                os.fsync(stream.fileno())
            os.fsync(parent)
        finally:
            os.close(parent)
    except OSError:
        raise SyncError("could not create and sync a new owner-only rollback journal") from None


def seed_vault(vault, snapshot):
    name = snapshot.name
    try:
        version, current = read_vault(vault, name)
    except HttpError as error:
        if error.status != 404:
            raise
    else:
        if current != snapshot.data:
            raise SyncError(f"{name}: existing Vault data differs; refusing to overwrite")
        return version, "already seeded"

    write_failed = False
    try:
        vault.request("POST", VAULT_PATH + name, name, {"options": {"cas": 0}, "data": snapshot.data})
    except SyncError:
        write_failed = True

    try:
        version, current = read_vault(vault, name)
    except SyncError:
        raise SyncError(f"{name}: Vault seed parity unconfirmed; verify before retry") from None
    if current != snapshot.data:
        raise SyncError(f"{name}: Vault seed differs after create attempt; refusing to overwrite")
    return version, "already seeded" if write_failed else "seeded"


def commit_one(api, before, after):
    # Recheck the cluster identity just before writing. The JSON patch also pins
    # the individual Secret UID and resourceVersion even if the request races.
    check_namespace(api)
    write_error = None
    try:
        patch_secret(api, before, after)
    except SyncError as error:
        # A timeout or response failure can occur after commit. Resolve by read,
        # never by replaying the write against the current resourceVersion.
        write_error = error
    try:
        check_namespace(api)
        current = read_kube(api, before.name)
    except SyncError:
        raise SyncError(f"{before.name}: write outcome unknown; verify against the journal") from None
    if current.uid == before.uid and current.data == after:
        return
    if current.uid == before.uid and current.data == before.data:
        detail = f" ({write_error})" if write_error else ""
        raise SyncError(f"{before.name}: write did not change the previous Secret data{detail}")
    raise SyncError(f"{before.name}: Secret changed unexpectedly; protect the journal and investigate")


def rollback(api, name, path):
    journal = decode_object(read_private(path, MAX_RESPONSE, "rollback journal"), "rollback journal")
    if (
        type(journal.get("format")) is not int or journal["format"] != 1
        or journal.get("namespace_uid") != NAMESPACE_UID or journal.get("name") != name
        or journal.get("type") != SECRET_TYPES[name]
    ):
        raise SyncError("rollback journal does not identify the selected Secret and namespace")
    before = validate_data(name, journal.get("previous"))
    after = validate_data(name, journal.get("desired"))
    compare_keys(name, before, after)
    current = read_kube(api, name)
    if current.uid != journal.get("uid"):
        raise SyncError(f"{name}: current Secret UID differs from the rollback journal")
    if current.data == before:
        print(f"{name}: already restored")
        return
    if current.data != after:
        raise SyncError(f"{name}: current data differs from both recorded states; refusing rollback")
    patch_secret(api, current, before, dry_run=True)
    commit_one(api, current, before)
    print(f"{name}: restored previous Secret data")


def run(args, kube, vault=None):
    check_namespace(kube)
    if args.action == "rollback":
        rollback(kube, args.secret[0], args.journal)
        return
    if args.action == "seed":
        current = read_kube(kube, args.secret[0])
        version, status = seed_vault(vault, current)
        if read_kube(kube, current.name) != current:
            raise SyncError(f"{current.name}: Kubernetes Secret changed during seed; recheck parity")
        print(f"{current.name}: {status}, Vault version {version}")
        return

    selection = []
    for name in args.secret:
        current = read_kube(kube, name)
        version, desired = read_vault(vault, name, args.vault_version)
        compare_keys(name, current.data, desired)
        selection.append((current, version, desired))

    for current, version, desired in selection:
        if current.data == desired:
            print(f"{current.name}: in sync with Vault version {version}")
            continue
        if args.action == "verify":
            raise SyncError(f"{current.name}: data drift against Vault version {version}")
        patch_secret(kube, current, desired, dry_run=True)
        if args.action == "stage":
            print(f"{current.name}: Vault version {version} passed server dry-run")
            continue
        write_journal(args.journal, current, desired, version)
        commit_one(kube, current, desired)
        print(f"{current.name}: materialized Vault version {version}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("verify", "stage", "seed", "apply", "rollback"))
    parser.add_argument("--secret", action="append", choices=SECRET_TYPES, required=True)
    parser.add_argument("--kube-address", required=True)
    parser.add_argument("--kube-ca", required=True)
    parser.add_argument("--kube-token-file", required=True)
    parser.add_argument("--vault-address")
    parser.add_argument("--vault-ca")
    parser.add_argument("--vault-token-file")
    parser.add_argument("--vault-version", type=int)
    parser.add_argument("--journal", type=Path)
    args = parser.parse_args(argv)

    if len(set(args.secret)) != len(args.secret):
        parser.error("each --secret may be selected once")
    if args.action in ("seed", "apply", "rollback") and len(args.secret) != 1:
        parser.error(f"{args.action} requires exactly one --secret")
    if args.vault_version is not None and (
        args.vault_version < 1 or len(args.secret) != 1 or args.action in ("seed", "rollback")
    ):
        parser.error("--vault-version requires one Secret and an action that reads Vault")
    if args.action == "apply" and args.vault_version is None:
        parser.error("apply requires an explicit --vault-version")
    if (args.action in ("apply", "rollback")) != (args.journal is not None):
        parser.error("apply and rollback require --journal")
    if args.action != "rollback" and not all((args.vault_address, args.vault_ca, args.vault_token_file)):
        parser.error("this action requires --vault-address, --vault-ca and --vault-token-file")
    if args.kube_token_file == args.vault_token_file == "-":
        parser.error("only one token can use stdin")

    try:
        kube = HttpsApi(
            "Kubernetes", args.kube_address, args.kube_ca, "Authorization",
            f"Bearer {read_token(args.kube_token_file, 'Kubernetes token')}",
        )
        vault = None
        if args.action != "rollback":
            vault = HttpsApi(
                "Vault", args.vault_address, args.vault_ca, "X-Vault-Token",
                read_token(args.vault_token_file, "Vault token"),
            )
        run(args, kube, vault)
    except SyncError as error:
        print(error, file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("interrupted; verify the selected Secret before retrying a write", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
