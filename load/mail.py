#!/usr/bin/env python3
"""Prove that SMTP failure does not lose in-app notifications or durable mail jobs."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urlencode
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet  # noqa: E402

parser = argparse.ArgumentParser(description="Проверка независимости уведомлений от SMTP на отдельном стенде")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-e2e")
parser.add_argument("--mailpit", default="http://127.0.0.1:8025")
parser.add_argument("--count", type=int, default=6)
parser.add_argument("--report-name", default="mail")
args = parser.parse_args()
if not 1 <= args.count <= 10 or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.report_name):
    parser.error("Нужны 1–10 получателей и безопасное имя отчёта")
if not re.fullmatch(r"http://(?:localhost|127\.0\.0\.1):[0-9]+", args.mailpit):
    parser.error("Mailpit должен быть локальным SMTP-перехватчиком")
api = DockerCabinet(args.base, args.project)
directory = ROOT / "load/reports" / args.report_name
directory.mkdir(parents=True, exist_ok=True)


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], cwd=ROOT, text=True, encoding="utf-8").strip()


def container(service):
    ids = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                 "--filter", f"label=com.docker.compose.service={service}").split()
    assert len(ids) == 1, "Нужен единственный контейнер " + service
    return ids[0]


postgres, smtp, sender = container("postgres"), container("mailpit"), container("engagement")
identity = json.loads(docker("inspect", *api.containers("identity")))
assert all("DEMO_ACCOUNTS=true" in item["Config"]["Env"] for item in identity), "Требуется отдельный синтетический стенд"


def sql(query):
    return json.loads(docker("exec", postgres, "psql", "-X", "-U", "cabinet", "-d", "engagement", "-tAc", query))


def until(probe, condition, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if condition(value):
            return value
        time.sleep(0.5)
    raise AssertionError("Почтовая проверка не завершилась за отведённое время")


report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "recipients": args.count, "passed": False}
report["image_id"] = docker("inspect", "--format", "{{.Image}}", sender)
report["source_commit"] = docker("image", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}', report["image_id"])
clients = []
try:
    bank = api.sign_in("underwriter@demo.test")["token"]
    for index in range(args.count):
        time.sleep(2.1)
        client = api.register("smtp-fault")
        setting = api.json("GET", "/api/v1/notifications/settings", token=client["token"])
        assert setting["emailAvailable"] and not setting["emailEnabled"], "Профиль mail должен быть включён, выбор — выключен"
        api.json("PUT", "/api/v1/notifications/settings", {"emailEnabled": True, "version": setting["version"]}, token=client["token"])
        clients.append(client)
    docker("stop", smtp)
    started = time.monotonic()
    for client in clients:
        app = api.create(client["token"], amount=300_000)
        api.json("POST", f"/api/v1/applications/{app['id']}/submit", token=client["token"])
        result = api.wait(client["token"], app["id"], {"OFFER_ISSUED", "MANUAL_REVIEW", "REJECTED"})
        if result["status"] == "MANUAL_REVIEW":
            api.json("POST", f"/api/v1/underwriting/applications/{app['id']}/approve", {"comment": "Проверка SMTP на синтетической заявке"}, token=bank)
        page = until(lambda: api.json("GET", "/api/v1/notifications", token=client["token"]), lambda value: value["total"] > 0)
        assert any(app["id"] in (item["link"] or "") for item in page["items"])
    report["in_app_notifications_available_during_smtp_failure"] = args.count
    report["business_and_bell_seconds"] = round(time.monotonic() - started, 3)
    users = ",".join("'" + client["user_id"] + "'" for client in clients)
    where = f"channel='email-notification' and payload->>'recipientId' in ({users})"

    def jobs():
        return sql(f"select coalesce(json_agg(t),'[]') from (select id,state,attempts from delivery_outbox where {where}) t")

    queued = until(jobs, lambda rows: len(rows) == args.count and all(row["attempts"] > 0 for row in rows))
    assert all(row["state"] in {"PENDING", "RUNNING"} for row in queued), queued
    docker("restart", sender)
    assert {row["id"] for row in queued} == {row["id"] for row in jobs()}, "Перезапуск потерял письма"
    resumed = time.monotonic()
    docker("start", smtp)
    delivered = until(jobs, lambda rows: len(rows) == args.count and all(row["state"] == "DELIVERED" for row in rows))
    report["smtp_catch_up_seconds"] = round(time.monotonic() - resumed, 3)
    counts = []
    for client in clients:
        with urlopen(args.mailpit + "/api/v1/search?" + urlencode({"query": "to:" + client["email"]}), timeout=10) as response:
            counts.append(len(json.load(response)["messages"]))
    assert counts == [1] * args.count, counts
    report.update(queued=len(queued), delivered=len(delivered), lost=0, duplicate_messages=0, mailbox_counts=counts, passed=True)
finally:
    docker("start", smtp, sender)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
