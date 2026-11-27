#!/usr/bin/env python3
import argparse
import base64
import hmac
import json
import os
import signal
import ssl
import stat
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPSHandler, HTTPRedirectHandler, ProxyHandler, Request, build_opener


CONFIG_DIR = Path("/etc/pump-vault-ci")
ISSUER = "https://gitlab.credit-cabinet.ru"
RELEASE_BRANCH = "feat/final-platform-release"
PROJECT_PATH = "pump/smart-credit-cabinet"
VAULT_ORIGIN = "https://vault.pump.internal:8200"
JWT_VAR = "PUMP_VAULT_ID_TOKEN"
MAX_RESPONSE = 1024 * 1024
PROFILES = {
    "dev": (
        "pump-ci-dev", "pump/data/dev/ci-deploy", 2, ("CABINET_DEV_SSH_KEY", "CABINET_DEV_KNOWN_HOSTS")
    ),
    "production": ("pump-ci-prod", "pump/data/prod/ci-deploy", 4, ("CABINET_PROD_VALUES",)),
}
ACTIVATION_MARKERS = {"dev": "dev-active-sha", "production": "prod-active-sha"}


class HandoffError(Exception):
    pass


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def verify_trust_path(path, expected_type):
    metadata = path.lstat()
    if not expected_type(metadata.st_mode) or metadata.st_uid != 0 or metadata.st_mode & 0o022:
        raise HandoffError("Vault trust configuration has unsafe ownership or permissions")


def load_trust():
    verify_trust_path(CONFIG_DIR, stat.S_ISDIR)
    config = CONFIG_DIR / "client.json"
    ca = CONFIG_DIR / "ca.pem"
    verify_trust_path(config, stat.S_ISREG)
    verify_trust_path(ca, stat.S_ISREG)

    endpoint = json.loads(config.read_text())["address"]
    address = urlsplit(endpoint)
    if (
        address.scheme != "https"
        or not address.hostname
        or address.username is not None
        or address.password is not None
    ):
        raise HandoffError("Vault address must be an HTTPS origin")
    if address.path or address.query or address.fragment or address.geturl() != endpoint:
        raise HandoffError("Vault address must be an HTTPS origin without a trailing slash")
    if endpoint != VAULT_ORIGIN:
        raise HandoffError("Vault address does not match the approved PUMP origin")

    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    tls.load_verify_locations(cafile=ca)
    return endpoint, build_opener(ProxyHandler({}), RejectRedirects(), HTTPSHandler(context=tls))


def validate_claims(jwt, endpoint, profile, runner_id, job_id, sha, action):
    try:
        _, encoded, _ = jwt.split(".")
        claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
    except ValueError:
        raise HandoffError("Invalid GitLab ID token format") from None
    expected = {
        "iss": ISSUER,
        "job_project_id": "1",
        "job_project_path": PROJECT_PATH,
        "ref": RELEASE_BRANCH,
        "ref_path": f"refs/heads/{RELEASE_BRANCH}",
        "ref_type": "branch",
        "ref_protected": "true",
        "environment": profile,
        "environment_action": action,
        "runner_id": runner_id,
        "job_id": job_id,
        "sha": sha,
    }
    if not isinstance(claims, dict) or any(claims.get(key) != value for key, value in expected.items()):
        raise HandoffError("GitLab token claims do not match this protected deployment")
    if claims.get("pipeline_source") not in ("push", "api", "web") or claims.get("aud") not in (endpoint, [endpoint]):
        raise HandoffError("GitLab token audience or pipeline source does not match this deployment")


def vault_request(opener, endpoint, path, *, body=None, token=None):
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token is not None:
        headers["X-Vault-Token"] = token
    request = Request(
        f"{endpoint}/v1/{path}",
        headers=headers,
        data=json.dumps(body).encode() if body is not None else None,
    )
    try:
        with opener.open(request, timeout=15) as response:
            content = response.read(MAX_RESPONSE + 1)
    except HTTPError as error:
        raise HandoffError(f"Vault request returned HTTP {error.code}") from None
    except (URLError, TimeoutError, ssl.SSLError):
        raise HandoffError("Could not reach Vault over verified TLS") from None
    if len(content) > MAX_RESPONSE:
        raise HandoffError("Vault response exceeded the deployment handoff limit")
    return json.loads(content) if content else {}


def read_values(profile, *, canary=False):
    if os.environ.get("CI_DEBUG_TRACE", "").lower() == "true":
        raise HandoffError("Vault handoff requires CI debug tracing to be disabled")
    jwt = os.environ.pop(JWT_VAR, "")
    job_id = os.environ.get("CI_JOB_ID", "")
    sha = os.environ.get("CI_COMMIT_SHA", "")
    if (not jwt or not job_id.isascii() or not job_id.isdecimal()
            or len(sha) != 40 or any(c not in "0123456789abcdef" for c in sha)):
        raise HandoffError("Missing GitLab job identity")
    role, path, runner, keys = PROFILES[profile]
    action = "verify" if canary else "start"
    if canary:
        role += "-canary"
    endpoint, opener = load_trust()
    validate_claims(jwt, endpoint, profile, runner, job_id, sha, action)
    auth = vault_request(opener, endpoint, "auth/pump-gitlab/login", body={"role": role, "jwt": jwt})["auth"]
    token = auth["client_token"]
    if not isinstance(token, str) or not token:
        raise HandoffError("Vault returned no scoped session")
    try:
        if auth.get("policies") != ["pump-ci-dev" if profile == "dev" else "pump-ci-prod"]:
            raise HandoffError("Vault session policies do not match the expected scope")
        values = vault_request(opener, endpoint, path, token=token)["data"]["data"]
        if set(values) != set(keys) or any(not isinstance(values[key], str) or not values[key] for key in keys):
            raise HandoffError("Vault deployment record does not match the expected files")
    finally:
        vault_request(opener, endpoint, "auth/token/revoke-self", body={}, token=token)
    return {key: values[key].encode() for key in keys}


def check_parity(profile):
    values = read_values(profile, canary=True)
    for key, expected in values.items():
        descriptor = os.open(os.environ[key], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as file:
            if not stat.S_ISREG(os.fstat(file.fileno()).st_mode):
                raise HandoffError("Legacy file variable is not a regular file")
            if not hmac.compare_digest(file.read(MAX_RESPONSE + 1), expected):
                raise HandoffError("Vault and legacy file variables differ")
    count = len(values)
    print(f"Vault {profile} canary: {count}/{count} file variables match, scoped session revoked")
    return 0


def deploy(profile, command):
    values = read_values(profile)
    environment = os.environ.copy()
    environment.pop("VAULT_TOKEN", None)
    with tempfile.TemporaryDirectory(prefix="pump-vault-deploy-", dir="/tmp") as temp:
        for key, value in values.items():
            target = Path(temp) / key
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(value)
            environment[key] = str(target)
        return subprocess.run(command, env=environment, check=False).returncode


def release(profile, command):
    marker = CONFIG_DIR / ACTIVATION_MARKERS[profile]
    try:
        verify_trust_path(CONFIG_DIR, stat.S_ISDIR)
        verify_trust_path(marker, stat.S_ISREG)
    except FileNotFoundError:
        environment = os.environ.copy()
        environment.pop(JWT_VAR, None)
        return subprocess.run(command, env=environment, check=False).returncode
    with marker.open(encoding="ascii") as active:
        activated_sha = active.read(42).rstrip("\n")
    if len(activated_sha) != 40 or activated_sha != os.environ.get("CI_COMMIT_SHA"):
        raise HandoffError("The runner Vault activation does not match this release")
    return deploy(profile, command)


def main():
    parser = argparse.ArgumentParser(description="Run a protected PUMP deployment with file secrets from Vault.")
    modes = tuple(f"{profile}-{mode}" for profile in PROFILES for mode in ("canary", "release"))
    parser.add_argument("profile", choices=modes)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    profile, mode = args.profile.rsplit("-", 1)
    if mode == "canary" and command:
        parser.error("the read-only canary does not accept a command")
    if mode != "canary" and not command:
        parser.error("a deployment command is required")
    try:
        if mode == "canary":
            return check_parity(profile)
        return release(profile, command)
    except HandoffError as error:
        print(str(error), file=sys.stderr)
    except (KeyError, TypeError, ValueError, OSError, AttributeError):
        print("Invalid Vault configuration, deployment record, or file handoff", file=sys.stderr)
    return 1


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda number, _: sys.exit(128 + number))
    sys.exit(main())
