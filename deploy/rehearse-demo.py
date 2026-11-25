#!/usr/bin/env python3
"""Reset the confirmed synthetic rehearsal and check that both demo roles can sign in."""

import argparse
import json
from pathlib import Path
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import Cabinet  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description="Подготовить отдельный синтетический стенд к прогону агентского сценария")
    parser.add_argument("--project", required=True)
    parser.add_argument("--confirm-project", required=True)
    parser.add_argument("--port", type=int, default=18082)
    args = parser.parse_args()
    started = time.monotonic()
    subprocess.run(
        [sys.executable, str(ROOT / "scripts/demo-reset.py"), "--project", args.project,
         "--confirm-project", args.confirm_project, "--port", str(args.port), "--no-build"],
        cwd=ROOT, check=True,
    )
    api = Cabinet(f"http://127.0.0.1:{args.port}")
    roles = {account: api.sign_in(f"{account}@demo.test")["user"]["role"] for account in ("agent", "client")}
    elapsed = time.monotonic() - started
    report = {"project": args.project, "seconds": round(elapsed, 3), "roles": roles,
              "passed": roles == {"agent": "AGENT", "client": "CLIENT"} and elapsed <= 300}
    print(json.dumps(report, ensure_ascii=False), flush=True)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
