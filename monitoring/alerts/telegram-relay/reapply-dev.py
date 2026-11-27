#!/usr/bin/env python3
"""Restore the host Alertmanager override after a verified legacy Dev rollout."""
import argparse
import fcntl
import json
from pathlib import Path
import re
import subprocess
import sys
import time


STATE = Path("/var/lib/cabinet-dev")
RELEASES = Path("/srv/cabinet-dev/releases")
OVERLAY = Path("/etc/pump/monitoring/compose.telegram-live.yaml")
SERVICE = "cabinet-dev-alertmanager-1"
CONFIG = "/etc/alertmanager/secrets/cabinet-paging/alertmanager-telegram.yml"


def docker(*args):
    result = subprocess.run(["docker", *args], check=True, capture_output=True, text=True, timeout=12)
    return result.stdout


def current_container():
    return json.loads(docker("inspect", SERVICE))[0]


def apply(expected, execute):
    with (STATE / "deploy.lock").open("r+b") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        live = json.loads((STATE / "current.json").read_text())
        if live["revision"] != expected or (STATE / "pending.json").exists():
            raise ValueError("Wait for CI/CD to confirm the exact Dev rollout")
        root = RELEASES / expected
        sys.path.insert(0, str(root / "deploy"))
        from common import Deployment

        deployment = Deployment("cabinet-dev", STATE / "cabinet.env", release_manifest=root / "release.json")
        if deployment.release.checksum != live["manifest_sha256"]:
            raise ValueError("The live manifest checksum changed")
        running = current_container()
        labels = running["Config"]["Labels"]
        if labels.get("com.docker.compose.project.working_dir") != str(root):
            raise ValueError("The running Alertmanager does not belong to the verified release")
        command = f"--config.file={CONFIG}"
        release_command = list(deployment.command)
        service = deployment.config()["services"]["alertmanager"]
        native = command in service["command"]
        if not native:
            deployment.command.extend(["-f", str(OVERLAY)])
            service = deployment.config()["services"]["alertmanager"]
        if command not in service["command"] or service.get("ports"):
            raise ValueError("Rendered Alertmanager config or exposure does not match the host contract")
        docker("exec", SERVICE, "amtool", "check-config", CONFIG)
        if command in running["Config"]["Cmd"]:
            print("Alertmanager already uses the protected config")
            return
        print("Native config validated" if native else "Alertmanager-only final override validated")
        if not execute:
            return
        up = ("up", "--detach", "--no-deps", "--no-build", "--pull", "never", "alertmanager")
        try:
            deployment.compose(*up, capture=True)
            for _ in range(20):
                try:
                    docker("exec", SERVICE, "wget", "-q", "-O", "/dev/null", "http://127.0.0.1:9093/-/ready")
                    if command in current_container()["Config"]["Cmd"]:
                        print("Protected Alertmanager ready")
                        return
                except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                    pass
                time.sleep(1)
            raise RuntimeError("Alertmanager did not become ready with the protected config")
        except Exception:
            if not native:
                deployment.command = release_command
                deployment.compose(*up, capture=True)
                print("Restored the release Alertmanager configuration")
            raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("revision", help="Exact SHA declared live by CI/CD")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-f0-9]{40}", args.revision):
        parser.error("revision must be the full Git SHA")
    apply(args.revision, args.apply)
