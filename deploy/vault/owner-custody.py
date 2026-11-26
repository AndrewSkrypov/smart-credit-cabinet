#!/usr/bin/env python3
"""Initialize, configure, and unseal the new Vault from the owner's Mac Keychain."""

import argparse
import ctypes as c
import hmac
import json
import os
import re
import secrets
import stat
import subprocess
import sys
from pathlib import Path

from configure import Vault, VaultError, configure, dev_approle_source, https_origin


FRAMEWORKS = "/System/Library/Frameworks"
NOT_FOUND = -25300
SHARES = 3
THRESHOLD = 2


class CustodyError(RuntimeError):
    pass


class Keychain:
    def __init__(self):
        self.security = c.CDLL(f"{FRAMEWORKS}/Security.framework/Security")
        self.core = c.CDLL(f"{FRAMEWORKS}/CoreFoundation.framework/CoreFoundation")
        self.security.SecKeychainAddGenericPassword.argtypes = [
            c.c_void_p, c.c_uint32, c.c_char_p, c.c_uint32, c.c_char_p,
            c.c_uint32, c.c_void_p, c.POINTER(c.c_void_p),
        ]
        self.security.SecKeychainFindGenericPassword.argtypes = [
            c.c_void_p, c.c_uint32, c.c_char_p, c.c_uint32, c.c_char_p,
            c.POINTER(c.c_uint32), c.POINTER(c.c_void_p), c.POINTER(c.c_void_p),
        ]
        self.security.SecKeychainItemFreeContent.argtypes = [c.c_void_p, c.c_void_p]
        self.security.SecKeychainItemDelete.argtypes = [c.c_void_p]
        self.core.CFRelease.argtypes = [c.c_void_p]
        self.core.CFRelease.restype = None

    @staticmethod
    def check(status, operation):
        if status:
            raise CustodyError(f"Keychain {operation} failed (OSStatus {status})")

    @staticmethod
    def label(account):
        service = b"PUMP Vault off-VM owner custody"
        account = account.encode("ascii")
        return (None, len(service), service, len(account), account)

    def find(self, account):
        length, buffer = c.c_uint32(), c.c_void_p()
        status = self.security.SecKeychainFindGenericPassword(
            *self.label(account), c.byref(length), c.byref(buffer), None,
        )
        if status == NOT_FOUND:
            return None
        self.check(status, "read")
        try:
            return c.string_at(buffer, length.value)
        finally:
            self.security.SecKeychainItemFreeContent(None, buffer)

    def add(self, account, value):
        item = c.c_void_p()
        buffer = c.create_string_buffer(value)
        status = self.security.SecKeychainAddGenericPassword(
            *self.label(account), len(value), buffer, c.byref(item),
        )
        self.check(status, "add")
        self.core.CFRelease(item)
        if not hmac.compare_digest(self.find(account) or b"", value):
            raise CustodyError("Keychain read-back verification failed")

    def delete(self, account):
        item = c.c_void_p()
        status = self.security.SecKeychainFindGenericPassword(
            *self.label(account), None, None, c.byref(item),
        )
        self.check(status, "find for deletion")
        try:
            self.check(self.security.SecKeychainItemDelete(item), "delete")
        finally:
            self.core.CFRelease(item)

    def probe(self):
        account = "synthetic-probe-" + secrets.token_hex(12)
        try:
            self.add(account, secrets.token_bytes(32))
        finally:
            if self.find(account) is not None:
                self.delete(account)


def validate_material(response):
    keys = response.get("keys_base64")
    token = response.get("root_token")
    if (not isinstance(keys, list) or len(keys) != SHARES or any(
            not isinstance(key, str) or not re.fullmatch(r"[A-Za-z0-9+/=]{20,128}", key) for key in keys)
            or len(set(keys)) != SHARES or not isinstance(token, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{16,2048}", token)):
        raise CustodyError("unexpected Vault initialization response format")
    return keys, token


def require_owner_directory(directory):
    info = directory.lstat()
    if info.st_uid != os.getuid() or not stat.S_ISDIR(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o700:
        raise CustodyError("custody directory must be owned by the operator and have mode 0700")
    filevault = subprocess.run(["/usr/bin/fdesetup", "status"], capture_output=True, text=True, check=False)
    if filevault.returncode or not filevault.stdout.startswith("FileVault is On."):
        raise CustodyError("FileVault must protect the owner recovery directory before initialization")


def initialize(vault, keychain, name, directory):
    require_owner_directory(directory)
    lock = directory / "vault-init.lock"
    lock.mkdir(mode=0o700)
    try:
        recovery = directory / "vault-init-emergency.json"
        if recovery.exists() or keychain.find(name + "/bootstrap") or keychain.find(name + "/shamir"):
            raise CustodyError("owner custody for this Vault endpoint already exists")
        if vault.request("GET", "sys/init").get("initialized") is not False:
            raise CustodyError("Vault is already initialized or its state could not be verified")
        keychain.probe()
        response = vault.request("PUT", "sys/init", {"secret_shares": SHARES, "secret_threshold": THRESHOLD})
        try:
            keys, token = validate_material(response)
            keychain.add(name + "/bootstrap", json.dumps({"keys": keys, "root_token": token}).encode())
            keychain.add(name + "/shamir", json.dumps(keys).encode())
        except (CustodyError, OSError) as error:
            descriptor = os.open(recovery, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as file:
                json.dump(response, file)
                file.flush()
                os.fsync(file.fileno())
            raise CustodyError("initialization response preserved in the FileVault-protected emergency file") from error
    finally:
        lock.rmdir()


def unseal(vault, keychain, name):
    status = vault.request("GET", "sys/seal-status")
    if status.get("initialized") is not True or status.get("storage_type") != "raft":
        raise CustodyError("target must be an initialized Raft Vault")
    if status.get("sealed") is False:
        return
    if status.get("progress") != 0:
        raise CustodyError("another unseal attempt has already started")
    stored = keychain.find(name + "/shamir")
    if not stored:
        raise CustodyError("Shamir custody not found in the owner Keychain")
    keys = json.loads(stored)
    if not isinstance(keys, list) or len(keys) != SHARES or status.get("t") != THRESHOLD:
        raise CustodyError("Vault seal threshold differs from recorded custody")
    for key in keys[:THRESHOLD]:
        status = vault.request("PUT", "sys/unseal", {"key": key})
    if status.get("sealed") is not False:
        raise CustodyError("Vault did not unseal using the stored threshold")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("probe", "init", "unseal", "configure", "revoke-bootstrap"))
    parser.add_argument("--address", help="exact Vault HTTPS origin")
    parser.add_argument("--ca", type=Path, help="trusted PEM CA")
    parser.add_argument("--tunnel-port", type=int, help="optional local SSH forwarding port retaining SNI")
    parser.add_argument("--directory", type=Path, help="owner-only FileVault-protected emergency directory")
    parser.add_argument("--dev-approle-source", type=dev_approle_source, help="verified public Dev egress IPv4 for configure")
    args = parser.parse_args()
    if args.dev_approle_source is not None and args.operation != "configure":
        parser.error("--dev-approle-source is only valid for configure")
    try:
        keychain = Keychain()
        if args.operation == "probe":
            keychain.probe()
            print("Owner login Keychain synthetic round-trip and cleanup verified")
            return 0
        if not args.address or args.ca is None:
            parser.error("this operation requires --address and --ca")
        if args.tunnel_port is not None and not 1 <= args.tunnel_port <= 65535:
            parser.error("invalid local tunnel port")
        name = https_origin(args.address).netloc
        vault = Vault(args.address, args.ca, None, args.tunnel_port)
        if args.operation == "init":
            if args.directory is None:
                parser.error("init also requires its owner-only --directory")
            initialize(vault, keychain, name, args.directory)
            print("Vault initialized with 2-of-3 Shamir and custody verified off-VM")
        elif args.operation == "unseal":
            unseal(vault, keychain, name)
            print("Vault is unsealed")
        else:
            stored = keychain.find(name + "/bootstrap")
            if not stored:
                raise CustodyError("bootstrap custody missing or already revoked")
            vault.token = json.loads(stored)["root_token"]
            if args.operation == "configure":
                configure(vault, args.address, args.dev_approle_source)
                print("PUMP KV, authentication and audit configured from verified custody")
            else:
                if not keychain.find(name + "/shamir"):
                    raise CustodyError("Shamir custody missing; bootstrap token was not revoked")
                vault.request("POST", "auth/token/revoke-self")
                keychain.delete(name + "/bootstrap")
                print("Bootstrap root token revoked and removed from owner Keychain")
    except (CustodyError, VaultError, ValueError, OSError, KeyError) as error:
        message = str(error) if isinstance(error, (CustodyError, VaultError)) else type(error).__name__
        print(f"Owner custody operation failed ({message})", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
