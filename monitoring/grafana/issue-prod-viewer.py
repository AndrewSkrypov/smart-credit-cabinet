#!/usr/bin/env python3
"""Issue or verify a Grafana Viewer for PUMP production over verified TLS."""

import argparse
from base64 import b64decode, b64encode
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import HTTPRedirectHandler, Request, build_opener


ORIGIN = "https://grafana.credit-cabinet.ru"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def request(method, route, account=None, data=None):
    headers = {"Accept": "application/json"}
    if account:
        credential = (account["login"] + ":" + account["password"]).encode()
        headers["Authorization"] = "Basic " + b64encode(credential).decode()
    body = None
    if data is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(data).encode()
    req = Request(ORIGIN + route, headers=headers, data=body, method=method)
    try:
        with build_opener(NoRedirect()).open(req, timeout=12) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        with error:
            return error.code, None


def expect(method, route, account=None, data=None):
    status, result = request(method, route, account, data)
    if status not in {200, 201}:
        raise RuntimeError(f"{method} {route.split('?')[0]} returned HTTP {status}")
    return result


def load_or_create(filename, login):
    try:
        descriptor = os.open(filename, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        account = {"url": ORIGIN + "/", "login": login, "password": secrets.token_urlsafe(36)}
        descriptor = os.open(filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            json.dump(account, stream)
            stream.write("\n")
        return account
    with os.fdopen(descriptor) as stream:
        mode = os.fstat(stream.fileno())
        if not stat.S_ISREG(mode.st_mode) or stat.S_IMODE(mode.st_mode) != 0o600 or mode.st_uid != os.getuid():
            raise RuntimeError("Existing credential file must be owned by the operator and mode 0600")
        account = json.load(stream)
    if account["url"] != ORIGIN + "/" or account["login"] != login or len(account["password"]) < 32:
        raise RuntimeError("Existing credential file does not match the requested account")
    return account


def run(args):
    cmd = ["kubectl", "--kubeconfig", str(args.kubeconfig), "--context", args.context,
           "--request-timeout=12s", "-n", "monitoring", "get", "secret", "cabinet-grafana-admin", "-o", "json"]
    data = json.loads(subprocess.check_output(cmd, stderr=subprocess.DEVNULL))["data"]
    admin = {"login": b64decode(data["admin-user"]).decode().strip(),
             "password": b64decode(data["admin-password"]).decode().rstrip("\n")}
    if request("GET", "/api/search")[0] != 401 or expect("GET", "/api/user", admin).get("isGrafanaAdmin") is not True:
        raise RuntimeError("Grafana HTTPS access boundary or admin identity check failed")
    viewer = load_or_create(args.output, args.login)
    status, identity = request("GET", "/api/user", viewer)
    if status == 401:
        lookup = "/api/users/lookup?loginOrEmail=" + quote(args.login, safe="")
        if request("GET", lookup, admin)[0] != 404:
            raise RuntimeError("Requested login already exists or lookup failed; no password was reset")
        expect("POST", "/api/admin/users", admin, {
            "name": "PUMP Observability Viewer", "email": args.login + "@example.invalid",
            "login": args.login, "password": viewer["password"], "OrgId": 1,
        })
        identity = expect("GET", "/api/user", viewer)
    elif status != 200:
        raise RuntimeError(f"Viewer identity probe returned HTTP {status}")
    if identity["login"] != args.login or identity["isGrafanaAdmin"]:
        raise RuntimeError("Viewer identity does not meet the requested boundary")
    memberships = expect("GET", "/api/user/orgs", viewer)
    if memberships and {entry["orgId"] for entry in memberships} != {1}:
        raise RuntimeError("Viewer has access outside the default organization")
    if not memberships:
        expect("POST", "/api/orgs/1/users", admin, {"loginOrEmail": args.login, "role": "Viewer"})
    elif memberships[0]["role"] != "Viewer":
        expect("PATCH", f"/api/orgs/1/users/{identity['id']}", admin, {"role": "Viewer"})
    memberships = expect("GET", "/api/user/orgs", viewer)
    if [(m["orgId"], m["role"]) for m in memberships] != [(1, "Viewer")]:
        raise RuntimeError("Viewer role confirmation failed")
    dashboards = expect("GET", "/api/search", viewer)
    if not {"credit-pipeline", "cabinet-platform", "cabinet-agent-final"} <= {row["uid"] for row in dashboards}:
        raise RuntimeError("Viewer cannot see the three provisioned dashboards")
    print(f"Grafana Viewer verified. Protected file: {args.output.absolute()}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kubeconfig", required=True, type=Path)
    parser.add_argument("--context", required=True)
    parser.add_argument("--login", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r"pump-[a-z0-9-]{1,40}", args.login):
        parser.error("Use a dedicated PUMP login such as pump-observer")
    try:
        run(args)
    except RuntimeError as error:
        parser.exit(1, str(error) + "\n")
    except (URLError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, "Viewer provisioning stopped: " + type(error).__name__ + "\n")


if __name__ == "__main__":
    main()
