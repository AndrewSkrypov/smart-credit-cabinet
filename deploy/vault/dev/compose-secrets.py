#!/usr/bin/env python3
"""Seed, verify or recover the existing Dev Compose credentials through Vault KV v2."""
import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import ssl
import stat
import sys
import tempfile
from urllib.parse import urlsplit


STATE_DIR = Path("/var/lib/cabinet-dev")
KV_DATA = "/v1/pump/data/dev/compose"
DOMAIN = "dev.credit-cabinet.ru"
MAX_ENV_BYTES = 128 * 1024
MAX_JSON_BYTES = 4 * MAX_ENV_BYTES
REQUIRED = {
    "POSTGRES_PASSWORD": 24,
    "JWT_SECRET": 48,
    "SIM_API_KEY": 24,
    "IDENTITY_LINK_KEY": 48,
    "VALKEY_PASSWORD": 32,
    **{
        f"{service}_{kind}_PASSWORD": 32
        for service in ("IDENTITY", "CREDIT", "ENGAGEMENT")
        for kind in ("DB", "MIGRATION")
    },
}
S3_KEYS = {"S3_ACCESS_KEY", "S3_SECRET_KEY", "S3_ADMIN_ACCESS_KEY", "S3_ADMIN_SECRET_KEY"}
SMTP_KEYS = {"SMTP_USERNAME", "SMTP_PASSWORD"}
OPTIONAL = S3_KEYS
ROLLBACK_NAME = re.compile(r"vault-rollback-[a-z0-9_]+\.json")
ENV_ASSIGNMENT = re.compile(r"([A-Z_][A-Z_0-9]*)=([^\r\n]*)")


class MigrationError(Exception):
    """An error whose message can safely be recorded."""


def require_private(metadata, mode, directory=False):
    is_expected_type = stat.S_ISDIR if directory else stat.S_ISREG
    if (
        not is_expected_type(metadata.st_mode)
        or stat.S_IMODE(metadata.st_mode) != mode
        or metadata.st_uid != os.geteuid()
        or (not directory and metadata.st_nlink != 1)
    ):
        raise MigrationError("Expected an owner-only regular file or directory owned by the invoking user.")


def read_private(path, limit):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        metadata = os.fstat(stream.fileno())
        require_private(metadata, 0o600)
        data = stream.read(limit + 1)
        if len(data) > limit:
            raise MigrationError("Private input exceeds its allowed size.")
        return data, metadata


@contextmanager
def deployment_lock(state, read_only=False):
    require_private(state.lstat(), 0o700, directory=True)
    flags = os.O_RDONLY if read_only else os.O_CREAT | os.O_WRONLY
    descriptor = os.open(state / "deploy.lock", flags | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    with os.fdopen(descriptor, "rb" if read_only else "wb") as lock:
        require_private(os.fstat(lock.fileno()), 0o600)
        try:
            mode = fcntl.LOCK_SH | fcntl.LOCK_NB if read_only else fcntl.LOCK_EX
            fcntl.flock(lock, mode)
        except BlockingIOError:
            raise MigrationError("The Dev deploy lock is busy. Retry the read-only check after the writer exits.") from None
        yield


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise MigrationError("Duplicate fields in structured data.")
        result[key] = value
    return result


def decode_json(data):
    try:
        return json.loads(data, object_pairs_hook=unique_keys)
    except (ValueError, UnicodeError, RecursionError):
        raise MigrationError("Invalid structured data.") from None


class CabinetEnv:
    def __init__(self, raw):
        self.raw = raw
        self.text = raw.decode("utf-8")
        self.values = {}
        for line in self.text.splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            match = ENV_ASSIGNMENT.fullmatch(line)
            if not match or "\x00" in line or match[1] in self.values:
                raise MigrationError("cabinet.env must have unique, single-line KEY=value assignments.")
            self.values[match[1]] = match[2]
        if (
            self.values.get("CABINET_DOMAIN") != DOMAIN
            or self.values.get("PUBLIC_CABINET_URL") not in {f"https://{DOMAIN}", f"https://{DOMAIN}/"}
            or self.values.get("CABINET_ISOLATED_DEMO", "false") != "false"
        ):
            raise MigrationError("The scope must be the ordinary Dev Compose environment.")

    def check_write_allowed(self, state):
        for name in ("CABINET_ALPHA_DEV_ENABLED", "CABINET_ALPHA_DEV_READ_ENABLED"):
            if self.values.get(name, "false") != "false":
                raise MigrationError("Active or indeterminate Alpha configuration prevents a local write.")
        if os.path.lexists(state / "pending.json"):
            raise MigrationError("A Dev release is pending. Resolve it before changing cabinet.env.")

    def secrets(self, include_smtp):
        allowed = REQUIRED.keys() | OPTIONAL
        if include_smtp:
            allowed |= SMTP_KEYS
        return {key: value for key, value in self.values.items() if key in allowed and value}

    def with_missing(self, secrets):
        difference = {
            key for key, value in secrets.items()
            if key in self.values and self.values[key] != value
        }
        if difference:
            raise MigrationError("Vault differs from existing credentials. Coordinate credential rotation separately.")
        missing = {key: value for key, value in secrets.items() if key not in self.values}
        if not missing:
            return self.raw
        if any(not re.fullmatch(r"[A-Za-z0-9_.@+/=\-]+", value) for value in missing.values()):
            raise MigrationError("Recovery requires values that can be safely appended as plain dotenv assignments.")
        newline = "\r\n" if "\r\n" in self.text and "\n" not in self.text.replace("\r\n", "") else "\n"
        separator = "" if not self.text or self.text.endswith("\n") else newline
        updated = self.text + separator + "".join(f"{key}={value}{newline}" for key, value in sorted(missing.items()))
        return updated.encode("utf-8")


def validate_secrets(secrets, settings, include_smtp):
    allowed = REQUIRED.keys() | OPTIONAL
    if include_smtp:
        allowed |= SMTP_KEYS
    if not isinstance(secrets, dict) or not REQUIRED.keys() <= secrets.keys() or not secrets.keys() <= allowed:
        raise MigrationError("Vault or host fields do not match the Dev Compose secret schema.")
    if any(
        not isinstance(value, str)
        or not 1 <= len(value) <= 4096
        or not all(character.isprintable() for character in value)
        for value in secrets.values()
    ):
        raise MigrationError("A credential is empty or cannot be represented on one line.")
    for key, minimum in (REQUIRED | {key: 24 for key in S3_KEYS & secrets.keys()}).items():
        value = secrets[key]
        if (
            len(value) < minimum
            or not re.fullmatch(r"[A-Za-z0-9_-]+", value)
            or value.startswith("dev-")
            or "CHANGE_ME" in value
        ):
            raise MigrationError("A required platform credential failed its existing format rules.")
    distinct = REQUIRED.keys() | (S3_KEYS & secrets.keys())
    if len({secrets[key] for key in distinct}) != len(distinct):
        raise MigrationError("Database, session, simulator, Valkey and S3 credentials must be distinct.")
    storage_enabled = settings.get("STORAGE_BACKEND") == "s3" or settings.get("STORAGE_ENABLED") == "true"
    if storage_enabled and not S3_KEYS <= secrets.keys():
        raise MigrationError("The enabled storage profile requires both S3 credential pairs.")
    if include_smtp and SMTP_KEYS & secrets.keys() and not SMTP_KEYS <= secrets.keys():
        raise MigrationError("Platform SMTP requires a complete service credential pair.")


def vault_authority(origin):
    if not re.fullmatch(r"https://[a-zA-Z0-9.-]+(?::[0-9]{1,5})?", origin):
        raise MigrationError("Use the exact Vault HTTPS origin without a path or trailing slash.")
    parsed = urlsplit(origin)
    try:
        port = parsed.port or 443
    except ValueError:
        raise MigrationError("Invalid Vault HTTPS port.") from None
    if parsed.port == 0 or not parsed.hostname or any(not label for label in parsed.hostname.split(".")):
        raise MigrationError("Invalid Vault HTTPS authority.")
    return parsed.hostname, port


class AddressPinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, host, port, connect_ip, **options):
        super().__init__(host, port, **options)
        self.connect_ip = connect_ip

    def connect(self):
        transport = socket.create_connection((self.connect_ip, self.port), self.timeout)
        try:
            self.sock = self._context.wrap_socket(transport, server_hostname=self.host)
        except BaseException:
            transport.close()
            raise


class Vault:
    def __init__(self, origin, ca_file, token, connect_ip=None):
        self.host, self.port = vault_authority(origin)
        # This context trusts the operator-selected CA and verifies certificate SANs.
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        self.context.load_verify_locations(cafile=str(ca_file))
        self.token = token
        try:
            self.connect_ip = str(ipaddress.IPv4Address(connect_ip)) if connect_ip is not None else None
        except ipaddress.AddressValueError:
            raise MigrationError("Use a literal IPv4 address for the explicit Vault route.") from None

    def request(self, method, payload=None):
        headers = {"X-Vault-Token": self.token}
        body = None
        if payload is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(payload).encode("utf-8")
        # Direct HTTPS to this exact authority. No proxy, redirect or retry.
        options = {"timeout": 10, "context": self.context}
        connection = (AddressPinnedHTTPSConnection(self.host, self.port, self.connect_ip, **options)
                      if self.connect_ip else http.client.HTTPSConnection(self.host, self.port, **options))
        try:
            connection.request(method, KV_DATA, body=body, headers=headers)
            response = connection.getresponse()
            if response.status != 200:
                return response.status, None
            raw = response.read(MAX_JSON_BYTES + 1)
            if len(raw) > MAX_JSON_BYTES:
                raise MigrationError("Vault response exceeds its allowed size.")
            return response.status, decode_json(raw)
        finally:
            connection.close()

    def read(self, allow_missing=False):
        status, payload = self.request("GET")
        if allow_missing and status == 404:
            return None
        if status != 200:
            raise MigrationError(f"Vault KV read failed (HTTP {status}).")
        try:
            values = payload["data"]["data"]
            metadata = payload["data"]["metadata"]
            version = metadata["version"]
            if type(version) is not int or version < 1 or metadata["destroyed"] is not False or metadata["deletion_time"] != "":
                raise MigrationError("Vault did not return a current, intact KV version.")
        except (KeyError, TypeError):
            raise MigrationError("Unexpected Vault KV v2 response.") from None
        return values, version

    def seed(self, expected):
        current = self.read(allow_missing=True)
        if current is None:
            status, _ = self.request("POST", {"options": {"cas": 0}, "data": expected})
            if status not in {200, 204, 400}:
                raise MigrationError(f"Vault KV CAS create failed (HTTP {status}).")
            # HTTP 400 may be a competing CAS create. Only exact read-back can pass.
            current = self.read()
        actual, version = current
        if actual != expected:
            raise MigrationError("Vault differs from the current Dev inventory. No overwrite was requested.")
        return version


def read_token(args):
    if args.token_file:
        data, _ = read_private(args.token_file, 4096)
    else:
        if sys.stdin.isatty():
            raise MigrationError("Read the token from a private redirected stream or a mode 0600 file.")
        metadata = os.fstat(sys.stdin.fileno())
        if stat.S_ISREG(metadata.st_mode) and (
            stat.S_IMODE(metadata.st_mode) != 0o600
            or metadata.st_uid not in {0, os.geteuid()}
            or metadata.st_nlink != 1
        ):
            raise MigrationError("A redirected Vault token file must be owner-only.")
        data = sys.stdin.buffer.read(4097)
    if len(data) > 4096 or not re.fullmatch(rb"[A-Za-z0-9_.\-]+\r?\n?", data):
        raise MigrationError("Invalid Vault token input.")
    return data.rstrip(b"\r\n").decode("ascii")


def private_write(stream, data, group):
    metadata = os.fstat(stream.fileno())
    if metadata.st_gid != group:
        os.fchown(stream.fileno(), -1, group)
    os.fchmod(stream.fileno(), 0o600)
    stream.write(data)
    stream.flush()
    os.fsync(stream.fileno())


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def replace_env(path, data, group):
    descriptor, name = tempfile.mkstemp(dir=path.parent, prefix=".vault-env-")
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            private_write(stream, data, group)
        temporary.replace(path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def write_backup(state, before, after, group):
    descriptor, name = tempfile.mkstemp(dir=state, prefix="vault-rollback-", suffix=".json")
    backup = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            payload = json.dumps({
                "previous": before.decode("utf-8"),
                "applied_sha256": hashlib.sha256(after).hexdigest(),
            })
            private_write(stream, payload.encode("utf-8"), group)
        sync_directory(state)
    except BaseException:
        backup.unlink(missing_ok=True)
        raise
    return backup.name


def existing_matches(env, secrets, include_smtp):
    legacy = env.secrets(include_smtp)
    if any(key not in secrets for key in legacy):
        raise MigrationError("Vault is missing a credential still configured on the Dev host.")


def execute(args, state=STATE_DIR):
    with deployment_lock(state, read_only=args.operation == "verify"):
        path = state / "cabinet.env"
        raw, metadata = read_private(path, MAX_ENV_BYTES)
        env = CabinetEnv(raw)
        if args.operation == "rollback":
            env.check_write_allowed(state)
            if not ROLLBACK_NAME.fullmatch(args.backup):
                raise MigrationError("Use a rollback backup name from this Dev state directory.")
            record = decode_json(read_private(state / args.backup, MAX_JSON_BYTES)[0])
            if not isinstance(record, dict) or set(record) != {"previous", "applied_sha256"}:
                raise MigrationError("Invalid Dev rollback backup.")
            if hashlib.sha256(raw).hexdigest() != record["applied_sha256"]:
                raise MigrationError("cabinet.env changed since recovery. Rollback was refused.")
            if not isinstance(record["previous"], str):
                raise MigrationError("Invalid Dev rollback backup.")
            previous = CabinetEnv(record["previous"].encode("utf-8"))
            previous.check_write_allowed(state)
            replace_env(path, previous.raw, metadata.st_gid)
            return "Restored the previous cabinet.env."
        vault = Vault(args.vault_origin, args.ca_file, read_token(args), args.connect_ip)
        if args.operation == "seed":
            expected = env.secrets(args.include_service_smtp)
            validate_secrets(expected, env.values, args.include_service_smtp)
            version = vault.seed(expected)
            return f"Verified Vault Dev Compose KV version {version}."
        values, version = vault.read()
        validate_secrets(values, env.values, args.include_service_smtp)
        existing_matches(env, values, args.include_service_smtp)
        updated = env.with_missing(values)
        if args.operation == "verify":
            if updated != raw:
                raise MigrationError("The Dev stage is missing Vault fields. Nothing was changed.")
            return f"Dev stage dry-run passed: {len(values)}/{len(values)} credentials match Vault KV version {version}."
        if updated == raw:
            return f"Verified cabinet.env parity with Vault Dev Compose KV version {version}."
        env.check_write_allowed(state)
        if len(updated) > MAX_ENV_BYTES:
            raise MigrationError("Recovered cabinet.env exceeds its allowed size.")
        backup = write_backup(state, raw, updated, metadata.st_gid)
        replace_env(path, updated, metadata.st_gid)
        return f"Recovered missing credentials from Vault KV version {version}. Local rollback backup: {backup}."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="operation", required=True)
    for operation in ("seed", "verify", "apply"):
        command = commands.add_parser(operation)
        command.add_argument("--vault-origin", required=True)
        command.add_argument("--connect-ip", help="optional explicit IPv4 route; verify TLS against the HTTPS origin")
        command.add_argument("--ca-file", type=Path, required=True)
        token = command.add_mutually_exclusive_group(required=True)
        token.add_argument("--token-file", type=Path)
        token.add_argument("--token-stdin", action="store_true")
        command.add_argument("--include-service-smtp", action="store_true")
    rollback = commands.add_parser("rollback")
    rollback.add_argument("--backup", required=True)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        print(execute(args))
    except MigrationError as error:
        parser.exit(1, f"{error}\n")
    except (OSError, UnicodeError, ValueError, http.client.HTTPException) as error:
        # Details from exceptions may contain private payloads or connection configuration.
        parser.exit(1, f"Dev Vault operation failed ({type(error).__name__}).\n")


if __name__ == "__main__":
    main()
