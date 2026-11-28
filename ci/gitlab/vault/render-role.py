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
    "pump-ci-dev-main": ("dev", 2, "start"),
    "pump-ci-prod-main": ("production", 4, "start"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=ROLES)
    parser.add_argument("sha", nargs="?", help="full SHA for a legacy protected release or canary")
    args = parser.parse_args()
    environment, runner_id, action = ROLES[args.role]
    main_role = args.role.endswith("-main")
    if main_role and args.sha:
        parser.error("main roles use protected main; the deploy job verifies the exact SHA and pipeline")
    if not main_role and not args.sha:
        parser.error("legacy roles require an exact SHA")
    try:
        role = gitlab_role(ORIGIN, environment, runner_id, action=action, sha=args.sha)
    except ValueError as error:
        parser.error(str(error))
    if main_role:
        role["bound_claims"].update({
            "ref": "main",
            "ref_path": "refs/heads/main",
            "pipeline_source": "push",
        })
        role.update(clock_skew_leeway=15, expiration_leeway=15, not_before_leeway=15)
    print(json.dumps(role, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
