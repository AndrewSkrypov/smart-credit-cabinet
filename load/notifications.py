#!/usr/bin/env python3
"""Measure durable notification catch-up using real business actions on an isolated demo stack."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet  # noqa: E402

parser = argparse.ArgumentParser(description="Отказ engagement и перезапуск отправителей без потери уведомлений")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-e2e")
parser.add_argument("--count", type=int, default=12)
parser.add_argument("--report-name", default="notifications")
args = parser.parse_args()
if not 1 <= args.count <= 20 or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.report_name):
    parser.error("Нужны 1–20 компаний и безопасное имя отчёта")
api = DockerCabinet(args.base, args.project)
directory = ROOT / "load/reports" / args.report_name
directory.mkdir(parents=True, exist_ok=True)


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], cwd=ROOT, text=True, encoding="utf-8").strip()


postgres = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                  "--filter", "label=com.docker.compose.service=postgres").split()
engagement = api.containers("engagement")
credits = api.containers("credit")
assert len(postgres) == len(engagement) == 1 and credits, "Нужен отдельный работающий стенд"
demo = json.loads(docker("inspect", *api.containers("identity")))
assert all("DEMO_ACCOUNTS=true" in item["Config"]["Env"] for item in demo), "Проверка разрешена только с синтетическими демо-аккаунтами"


def sql(database, query):
    return json.loads(docker("exec", postgres[0], "psql", "-X", "-U", "cabinet", "-d", database, "-tAc", query))


def until(probe, predicate, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if predicate(value):
            return value
        time.sleep(0.5)
    raise AssertionError("Превышено время ожидания измеряемого условия")


report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "companies": args.count,
          "sender_instances": len(credits), "passed": False}
# The OCI revision belongs to the image, not the Compose container.
report["source_commit"] = docker("image", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}',
                                 docker("inspect", "--format", "{{.Image}}", credits[0]))
clients = []
restored = False
try:
    docker("stop", *engagement)
    started = time.monotonic()
    for index in range(args.count):
        client = api.register("notification-fault")
        app = api.create(client["token"], amount=300_000)
        api.json("POST", f"/api/v1/applications/{app['id']}/submit", token=client["token"])
        client["application_id"] = app["id"]
        clients.append(client)
        if index + 1 < args.count:
            time.sleep(2.1)
    for client in clients:
        api.wait(client["token"], client["application_id"], {"OFFER_ISSUED", "MANUAL_REVIEW", "REJECTED"})
    report["business_completed_during_outage_seconds"] = round(time.monotonic() - started, 3)
    ids = "|".join(client["application_id"] for client in clients)
    predicate = f"channel='notification' and payload->>'link' ~ '{ids}'"

    def events():
        return sql("credit", f"select coalesce(json_agg(t),'[]') from (select id,event_key,state,attempts from delivery_outbox where {predicate}) t")

    before = until(events, lambda rows: len(rows) >= args.count and all(row["attempts"] > 0 for row in rows))
    assert all(row["state"] in {"PENDING", "RUNNING"} for row in before), "Недоступный получатель не должен подтверждать событие"
    report["queued_before_restart"] = len(before)
    report["maximum_attempts_before_restart"] = max(row["attempts"] for row in before)
    docker("restart", *credits)
    after = events()
    assert {row["id"] for row in before} == {row["id"] for row in after}, "Перезапуск потерял события"
    recovery = time.monotonic()
    docker("start", *engagement)
    restored = True
    delivered = until(events, lambda rows: len(rows) == len(before) and all(row["state"] == "DELIVERED" for row in rows))
    report["catch_up_seconds"] = round(time.monotonic() - recovery, 3)
    keys = ",".join("'" + row["event_key"] + "'" for row in delivered)
    counts = sql("engagement", f"""select json_build_object(
        'receipts',(select count(*) from notification_receipts where event_key in ({keys})),
        'notifications',(select count(*) from notifications where dedup_key in ({keys})),
        'duplicates',(select count(*) from (select recipient_id,dedup_key from notifications where dedup_key in ({keys}) group by 1,2 having count(*)>1) t))""")
    assert counts["receipts"] == len(delivered) and counts["notifications"] == len(delivered) and counts["duplicates"] == 0, counts
    payload = sql("credit", f"select payload from delivery_outbox where {predicate} order by id limit 1")
    with ThreadPoolExecutor(max_workers=4) as executor:
        replays = list(executor.map(lambda _: api.service("engagement", "POST", "/internal/v1/notifications", {"items": [payload]})["body"]["stored"], range(4)))
    assert replays == [0, 0, 0, 0], replays
    report.update(sent=len(before), delivered=len(delivered), lost=0, duplicates=0, receiver=counts,
                  concurrent_replays=4, additional_replay_notifications=sum(replays), passed=True)
finally:
    if not restored:
        docker("start", *engagement)
    docker("start", *credits)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
