#!/usr/bin/env python3
"""Render a SHA-pinned Vault JWT role without connecting to Vault."""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "deploy" / "vault"))
from configure import gitlab_role


ORIGIN = "https://vault.pump.internal:8200"
ROLES = {
    "pump-ci-dev-canary": ("dev", 2, "verify"),
    "pump-ci-dev": ("dev", 2, "start"),
    "pump-ci-prod-canary": ("production", 4, "verify"),
    "pump-ci-prod": ("production", 4, "start"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=ROLES)
    parser.add_argument("sha", help="full SHA of the protected pipeline commit")
    args = parser.parse_args()
    environment, runner_id, action = ROLES[args.role]
    try:
        role = gitlab_role(ORIGIN, environment, runner_id, action=action, sha=args.sha)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(role, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
