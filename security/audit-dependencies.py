#!/usr/bin/env python3
"""Retain all npm audit findings and fail verification on critical dependencies."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description="Аудит npm-проектов: критичные находки блокируют проверку")
parser.add_argument("--report", type=Path, action="append", help="Проверить сохранённый JSON вместо обращения к npm; можно повторить")
args = parser.parse_args()
directory = ROOT / "security/reports"
directory.mkdir(exist_ok=True)
reports = []
if args.report:
    reports = [(str(file), json.loads(file.read_text(encoding="utf-8-sig"))) for file in args.report]
else:
    command = ["cmd.exe", "/d", "/c", "npm"] if os.name == "nt" else ["npm"]
    attempts = []
    for scope in ["frontend", "e2e", "contracts/postman", "scripts/license-tools"]:
        for attempt in range(1, 4):
            data, returncode = {}, None
            try:
                response = subprocess.run(command + ["audit", "--prefix", scope, "--json", "--fetch-timeout=20000", "--fetch-retries=1"], cwd=ROOT,
                                          capture_output=True, text=True, encoding="utf-8", check=False, timeout=70)
                returncode = response.returncode
                data = json.loads(response.stdout)
            except (subprocess.TimeoutExpired, json.JSONDecodeError):
                pass
            valid = returncode in (0, 1) and not data.get("error") and isinstance(data.get("metadata", {}).get("vulnerabilities"), dict)
            attempts.append({"scope": scope, "attempt": attempt, "returncode": returncode, "valid_result": valid})
            (directory / "npm-audit-attempts.json").write_text(json.dumps(attempts, indent=2), encoding="utf-8")
            if valid:
                break
            if attempt == 3:
                raise SystemExit(f"npm audit {scope}: после трёх попыток нет результата; это ошибка проверки, не отсутствие уязвимостей")
            time.sleep(2 ** attempt)
        (directory / f"npm-{scope.replace('/', '-')}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        reports.append((scope, data))

rows = []
for scope, data in reports:
    if "metadata" not in data or "vulnerabilities" not in data["metadata"]:
        raise SystemExit(f"Нет результата аудита: {scope}")
    counts = data["metadata"]["vulnerabilities"]
    critical = sorted(name for name, finding in data.get("vulnerabilities", {}).items() if finding["severity"] == "critical")
    rows.append({"scope": scope, "counts": counts, "critical_packages": critical})
summary = {"checked_at": datetime.now(timezone.utc).isoformat(), "reports": rows,
           "passed": all(row["counts"]["critical"] == 0 for row in rows)}
if not args.report:
    (directory / "npm-audit-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
raise SystemExit(0 if summary["passed"] else 1)
