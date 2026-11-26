#!/usr/bin/env python3
import argparse
import base64
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
JWT_VAR = "PUMP_VAULT_ID_TOKEN"
MAX_RESPONSE = 1024 * 1024
PROFILES = {
    "dev": (
        "pump-ci-dev", "pump/data/dev/ci-deploy", 2, ("CABINET_DEV_SSH_KEY", "CABINET_DEV_KNOWN_HOSTS")
    ),
    "production": ("pump-ci-prod", "pump/data/prod/ci-deploy", 4, ("CABINET_PROD_VALUES",)),
}


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
    if address.port == 0:
        raise HandoffError("Vault address must use a valid port")

    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    tls.load_verify_locations(cafile=ca)
    return endpoint, build_opener(ProxyHandler({}), RejectRedirects(), HTTPSHandler(context=tls))


def validate_claims(jwt, endpoint, profile, runner_id, job_id):
    try:
        _, encoded, _ = jwt.split(".")
        claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
    except ValueError:
        raise HandoffError("Invalid GitLab ID token format") from None
    expected = {
        "iss": ISSUER,
        "job_project_id": "1",
        "ref": RELEASE_BRANCH,
        "ref_path": f"refs/heads/{RELEASE_BRANCH}",
        "ref_protected": "true",
        "environment": profile,
        "environment_action": "start",
        "runner_id": runner_id,
        "job_id": job_id,
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
    return json.loads(content)


def deploy(profile, command):
    if os.environ.get("CI_DEBUG_TRACE", "").lower() == "true":
        raise HandoffError("Vault handoff requires CI debug tracing to be disabled")
    jwt = os.environ.pop(JWT_VAR, "")
    job_id = os.environ.get("CI_JOB_ID", "")
    if not jwt or not job_id.isdecimal():
        raise HandoffError("Missing GitLab job identity")
    role, path, runner, keys = PROFILES[profile]
    endpoint, opener = load_trust()
    validate_claims(jwt, endpoint, profile, runner, job_id)
    login = vault_request(opener, endpoint, "auth/pump-gitlab/login", body={"role": role, "jwt": jwt})
    token = login["auth"]["client_token"]
    if not isinstance(token, str) or not token:
        raise HandoffError("Vault returned no scoped session")
    values = vault_request(opener, endpoint, path, token=token)["data"]["data"]
    if any(not isinstance(values.get(key), str) or not values[key] for key in keys):
        raise HandoffError("Vault deployment record is missing a required file")

    environment = os.environ.copy()
    with tempfile.TemporaryDirectory(prefix="pump-vault-deploy-", dir="/tmp") as temp:
        for key in keys:
            target = Path(temp) / key
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(values[key].encode())
            environment[key] = str(target)
        return subprocess.run(command, env=environment, check=False).returncode


def main():
    parser = argparse.ArgumentParser(description="Run a protected PUMP deployment with file secrets from Vault.")
    parser.add_argument("profile", choices=PROFILES)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a deployment command is required")
    try:
        return deploy(args.profile, command)
    except HandoffError as error:
        print(str(error), file=sys.stderr)
    except (KeyError, TypeError, ValueError, OSError, AttributeError):
        print("Invalid Vault configuration, deployment record, or file handoff", file=sys.stderr)
    return 1


if __name__ == "__main__":
    signal.signal(signal.SIGTERM, lambda number, _: sys.exit(128 + number))
    sys.exit(main())
