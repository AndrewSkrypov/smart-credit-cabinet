#!/usr/bin/env python3
"""Bind the protected main deployment jobs to their own release artifacts."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path


MAIN_REF = "refs/heads/main"
MANIFEST = Path("release.json")
EVIDENCE = Path("release-evidence.json")
SHA = re.compile(r"[0-9a-f]{40}")
ID = re.compile(r"[1-9][0-9]*")


class ReleaseError(Exception):
    pass


def git(*args):
    result = subprocess.run(
        ["git", *args], capture_output=True, text=True, timeout=30, check=False
    )
    if result.returncode != 0:
        raise ReleaseError("Could not verify the source checkout or the current main ref.")
    return result.stdout.strip()


def context():
    env = os.environ
    if (
        env.get("CI_COMMIT_BRANCH") != "main"
        or env.get("CI_COMMIT_REF_PROTECTED") != "true"
        or env.get("CI_PIPELINE_SOURCE") != "push"
        or env.get("PUMP_MAIN_RELEASE_ENABLED") != "1"
    ):
        raise ReleaseError("Release evidence requires an enabled protected main push pipeline.")
    revision = env.get("CI_COMMIT_SHA", "")
    project = env.get("CI_PROJECT_ID", "")
    pipeline = env.get("CI_PIPELINE_ID", "")
    job = env.get("CI_JOB_ID", "")
    if not SHA.fullmatch(revision) or not all(ID.fullmatch(value) for value in (project, pipeline, job)):
        raise ReleaseError("Invalid CI release provenance.")
    if git("rev-parse", "HEAD") != revision:
        raise ReleaseError("The release checkout does not match the pipeline commit.")
    if git("ls-remote", "--exit-code", "origin", MAIN_REF) != f"{revision}\t{MAIN_REF}":
        raise ReleaseError("This release commit is no longer the tip of main.")
    return {
        "format": 1,
        "project": project,
        "pipeline": pipeline,
        "ref": MAIN_REF,
        "source": "push",
        "revision": revision,
        "tree": git("rev-parse", "HEAD^{tree}"),
    }, job


def manifest_hash(revision):
    raw = MANIFEST.read_bytes()
    if json.loads(raw).get("revision") != revision:
        raise ReleaseError("The release manifest revision does not match the pipeline commit.")
    return hashlib.sha256(raw).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("check", "write", "verify"))
    args = parser.parse_args()
    try:
        expected, job = context()
        if args.operation == "check":
            return 0
        expected["manifest_sha256"] = manifest_hash(expected["revision"])
        if args.operation == "write":
            EVIDENCE.write_text(json.dumps(expected | {"publisher_job": job}, sort_keys=True) + "\n")
        else:
            evidence = json.loads(EVIDENCE.read_text())
            publisher = evidence.pop("publisher_job", None)
            if not isinstance(publisher, str) or not ID.fullmatch(publisher) or publisher == job or evidence != expected:
                raise ReleaseError("Release evidence does not match this pipeline and manifest.")
        print(f"Release {expected['revision']} manifest SHA-256 {expected['manifest_sha256']}")
    except ReleaseError as error:
        print(error, file=sys.stderr)
        return 1
    except (OSError, ValueError, AttributeError, TypeError, subprocess.TimeoutExpired):
        print("Could not read or validate release evidence.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
