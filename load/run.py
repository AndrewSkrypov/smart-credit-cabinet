#!/usr/bin/env python3
"""Prepare isolated companies, run pinned k6, and assert API/SQL invariants."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import Cabinet  # noqa: E402

parser = argparse.ArgumentParser(description="Измеренная нагрузка на синтетическом стенде")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-submission")
parser.add_argument("--companies", type=int, default=20)
parser.add_argument("--revenue", type=int, default=24_000_000, help="Заявленная годовая выручка; 999000000 гарантирует расхождение с синтетической ФНС")
parser.add_argument("--label", default=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
parser.add_argument("--prepare-only", action="store_true")
args = parser.parse_args()
if not re.fullmatch(r"cabinet-(?:submission|reliability|e2e)(?:-[a-z0-9]+)?", args.project):
    parser.error("Разрешён только отдельный проект cabinet-submission/cabinet-reliability/cabinet-e2e")
if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.label) or not 1 <= args.companies <= 200:
    parser.error("Метка: 1–64 буквы/цифры/_/-, компаний: 1–200")
report = ROOT / "load/reports" / args.label
report.mkdir(parents=True, exist_ok=False)


def docker(*arguments, check=True):
    result = subprocess.run(["docker", *arguments], cwd=ROOT, capture_output=True, text=True, encoding="utf-8", check=check)
    return result.stdout


pg = docker("ps", "--filter", f"label=com.docker.compose.project={args.project}", "--filter", "label=com.docker.compose.service=postgres", "--format", "{{.ID}}").strip()
if not pg or "\n" in pg:
    raise SystemExit("Не найден единственный PostgreSQL целевого тестового проекта")
network = f"{args.project}_cabinet"
containers = json.loads(docker("inspect", *docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}").split()))
runtime = [{"name": item["Name"], "image_id": item["Image"], "memory_limit_bytes": item["HostConfig"]["Memory"]} for item in containers]
api = Cabinet(args.base)
bank = api.sign_in("underwriter@demo.test")["token"]
clients = []
for index in range(args.companies):
    # Registration is setup, outside measured load, and respects the real gateway limit.
    time.sleep(2.1)
    client = api.register(f"load-{args.label.lower()}")
    app = api.create(client["token"], revenue=args.revenue)
    clients.append({**client, "id": app["id"]})
    print(f"Подготовлено {index + 1}/{args.companies}", flush=True)
cohort = {"run": args.label, "clients": clients, "underwriter": bank}
(report / "cohort.json").write_text(json.dumps(cohort), encoding="utf-8")
if args.prepare_only:
    print(f"Подготовлена когорта: {report}")
    raise SystemExit(0)

started = time.monotonic()
result = subprocess.run([
    "docker", "run", "--rm", "--network", network, "--user", "0:0",
    "-v", f"{ROOT / 'load'}:/work", "-e", "BASE_URL=http://gateway",
    "-e", f"COHORT=/work/reports/{args.label}/cohort.json",
    "-e", f"SUMMARY=/work/reports/{args.label}/summary.json",
    "grafana/k6:1.3.0", "run", "/work/credit.js",
], cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
elapsed = time.monotonic() - started
(report / "k6.log").write_text(result.stdout + result.stderr, encoding="utf-8")
rows = []
for client in clients:
    app = api.json("GET", f"/api/v1/applications/{client['id']}", token=client["token"])
    events = api.json("GET", f"/api/v1/applications/{client['id']}/events", token=client["token"])
    transitions = [event for event in events if event["fromStatus"] != event["toStatus"]]
    counts = Counter((event["fromStatus"], event["toStatus"]) for event in transitions)
    assert all(count == 1 for count in counts.values()), (client["id"], counts)
    assert sum(event["event"] == "SUBMIT" for event in events) == 1
    assert app["status"] in {"OFFER_ISSUED", "REJECTED"}, app["status"]
    rows.append({"id": app["id"], "status": app["status"], "transitions": len(transitions), "single_transitions": True})

ids = ",".join(f"'{client['id']}'::uuid" for client in clients)
sql = f"""
select json_build_object(
 'applications', (select count(*) from applications where id in ({ids})),
 'duplicate_offers', (select count(*) from (select application_id from offers where application_id in ({ids}) group by application_id having count(*) > 1) d),
 'failed_jobs', (select count(*) from pipeline_jobs where application_id in ({ids}) and status = 'FAILED'),
 'due_open_jobs', (select count(*) from pipeline_jobs where application_id in ({ids}) and step not in ('EXPIRE_OFFER','EXPIRE_INFO_REQUEST') and status in ('PENDING','RUNNING')),
 'jobs_retried', (select count(*) from pipeline_jobs where application_id in ({ids}) and attempts > 1));
"""
db = json.loads(docker("exec", pg, "psql", "-U", os.environ.get("POSTGRES_USER", "cabinet"), "-d", "credit", "-tAc", sql))
assert db["applications"] == args.companies and db["duplicate_offers"] == db["failed_jobs"] == db["due_open_jobs"] == 0, db
summary = json.loads((report / "summary.json").read_text(encoding="utf-8"))
measurements = {
    "run": args.label, "checked_at": datetime.now(timezone.utc).isoformat(), "project": args.project,
    "harness_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
    "working_tree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
    "companies": args.companies, "k6_exit": result.returncode, "process_seconds": elapsed,
    "runtime": runtime,
    "api_ms": summary["metrics"]["cabinet_api_ms"]["values"],
    "decision_ms": summary["metrics"]["cabinet_decision_ms"]["values"],
    "http_failed": summary["metrics"]["http_req_failed"]["values"],
    "http_requests": summary["metrics"]["http_reqs"]["values"],
    "decisions": summary["metrics"]["cabinet_decisions"]["values"],
    "database": db, "applications": rows,
}
(report / "verified.json").write_text(json.dumps(measurements, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({key: value for key, value in measurements.items() if key != "applications"}, ensure_ascii=False, indent=2))
raise SystemExit(result.returncode)
