#!/usr/bin/env python3
"""Append the Telegram route to an existing native Alertmanager config."""
import argparse
import base64
import binascii
import os
from pathlib import Path

import yaml


def render(existing, proxy, authorization, smoke_only=False):
    scheme, separator, authority = proxy.partition("://")
    if scheme != "https" or not separator or not authority or any(c in authority for c in "/@?#"):
        raise ValueError("HTTPS proxy authority is required")
    if not authorization.startswith("Basic "):
        raise ValueError("Basic proxy authorization must come from a protected file")
    try:
        base64.b64decode(authorization.removeprefix("Basic "), validate=True)
    except binascii.Error:
        raise ValueError("Invalid Basic proxy credential") from None

    config = yaml.safe_load(existing)
    path = Path(__file__).with_name("telegram-prod.yml")
    fragment = yaml.safe_load(path.read_text())
    receiver = fragment["receiver"]
    client = receiver["telegram_configs"][0]["http_config"]
    client["proxy_url"] = proxy
    client["proxy_connect_header"]["Proxy-Authorization"] = [authorization]
    receiver_name = receiver["name"]

    routes = config["route"].setdefault("routes", [])
    routes[:] = [route for route in routes if route.get("receiver") != receiver_name]
    # Keep all internal routes, including their priority over the Telegram route.
    routes.append(fragment["smoke"])
    if not smoke_only:
        routes.append(fragment["paging"])
    receivers = config["receivers"]
    receivers[:] = [entry for entry in receivers if entry["name"] != receiver_name]
    receivers.append(receiver)
    return yaml.safe_dump(config, sort_keys=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, required=True)
    parser.add_argument("--auth-file", type=Path, required=True)
    parser.add_argument("--proxy", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--smoke-only", action="store_true")
    args = parser.parse_args()
    result = render(args.base.read_text(), args.proxy, args.auth_file.read_text().strip(), args.smoke_only)
    with os.fdopen(os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
        output.write(result)


if __name__ == "__main__":
    main()
