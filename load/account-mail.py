#!/usr/bin/env python3
"""Verify hash-only account links through a real SMTP outage and identity restart."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urlencode, urlparse, parse_qs
from urllib.request import urlopen
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet  # noqa: E402

parser = argparse.ArgumentParser(description="Служебная почта при отказе SMTP и рестарте identity")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-e2e")
parser.add_argument("--mailpit", default="http://127.0.0.1:8025")
parser.add_argument("--count", type=int, default=3)
parser.add_argument("--report-name", default="account-mail")
args = parser.parse_args()
if not 1 <= args.count <= 5 or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.report_name):
    parser.error("Нужны 1–5 компаний и безопасное имя отчёта")
if not re.fullmatch(r"http://(?:localhost|127\.0\.0\.1):[0-9]+", args.mailpit):
    parser.error("Mailpit должен быть локальным")
api = DockerCabinet(args.base, args.project)
directory = ROOT / "load/reports" / args.report_name
directory.mkdir(parents=True, exist_ok=True)


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], cwd=ROOT, text=True, encoding="utf-8").strip()


def single(service):
    ids = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}", "--filter", f"label=com.docker.compose.service={service}").split()
    assert len(ids) == 1, "Нужен единственный " + service
    return ids[0]


postgres, smtp, sender = single("postgres"), single("mailpit"), single("identity")
metadata = json.loads(docker("inspect", sender))[0]
assert "DEMO_ACCOUNTS=true" in metadata["Config"]["Env"], "Нужен отдельный синтетический стенд"
assert api.json("GET", "/api/v1/auth/options")["mailAvailable"], "Нужен включённый профиль mail"
credit_instances = api.containers("credit")
assert len(credit_instances) == 2, "Проверка отзыва требует двух настоящих credit"


def sql(query):
    return json.loads(docker("exec", postgres, "psql", "-X", "-U", "cabinet", "-d", "identity", "-tAc", query))


def until(probe, condition, timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = probe()
        if condition(value):
            return value
        time.sleep(0.5)
    raise AssertionError("Проверка служебной почты не завершилась за отведённое время")


def mailbox(email):
    with urlopen(args.mailpit + "/api/v1/search?" + urlencode({"query": "to:" + email}), timeout=10) as response:
        return json.load(response)["messages"]


def link_token(message, expected_path):
    with urlopen(args.mailpit + "/api/v1/message/" + message["ID"], timeout=10) as response:
        text = json.load(response)["Text"]
    match = re.search(r"https?://\S+", text)
    assert match is not None, "В письме нет ссылки"
    link = urlparse(match.group())
    assert link.path == expected_path and not link.query, "Ссылка должна использовать фиксированный путь и фрагмент"
    return parse_qs(link.fragment)["token"][0]


def private_status(instance, route, token):
    config = 'header = "Authorization: Bearer ' + token + '"\n'
    result = subprocess.run(["docker", "exec", "-i", instance, "curl", "-sS", "--max-time", "15", "--config", "-", "-o", "/dev/null", "-w", "%{http_code}", "http://localhost:8080" + route],
                            input=config, text=True, capture_output=True, check=True)
    return int(result.stdout)


report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "companies": args.count, "passed": False, "actions": []}
report["image_id"] = metadata["Image"]
report["source_commit"] = docker("image", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}', metadata["Image"])
clients, references, invited_addresses = [], [], []


def action(name, target):
    docker(name, target)
    report["actions"].append({"completed_at": datetime.now(timezone.utc).isoformat(), "action": name, "container_id": target, "authorization": "isolated release fault check"})


try:
    for index in range(args.count):
        time.sleep(2.1)
        clients.append(api.register("account-mail-fault"))
    action("stop", smtp)
    for client in clients:
        api.json("POST", "/api/v1/auth/password-recovery", {"email": client["email"]})
        reference = sql(f"select to_json(id) from password_reset_requests where user_id='{client['user_id']}' and used_at is null and revoked_at is null")
        references.append(reference)
        email = f"account-invited-{uuid4().hex}@example.test"
        invitation = api.json("POST", "/api/v1/companies/me/invitations", {"email": email, "canSign": False}, client["token"], headers={"Idempotency-Key": str(uuid4())})
        assert invitation["link"] is None, "С почтой открытая ссылка не возвращается"
        references.append(invitation["id"])
        invited_addresses.append(email)
    ref_list = ",".join("'" + reference + "'" for reference in references)

    def jobs():
        return sql(f"select coalesce(json_agg(t),'[]') from (select id,state,attempts,(select count(*) from jsonb_object_keys(payload)) as fields from delivery_outbox where channel='email-security' and payload->>'referenceId' in ({ref_list})) t")

    queued = until(jobs, lambda rows: len(rows) == 2*args.count and all(row["attempts"] > 0 for row in rows))
    assert all(row["state"] in {"PENDING", "RUNNING"} and row["fields"] == 2 for row in queued), "Очередь должна хранить только назначение и UUID"
    action("restart", sender)
    assert {row["id"] for row in queued} == {row["id"] for row in jobs()}, "Рестарт потерял очередь"
    resumed = time.monotonic()
    action("start", smtp)
    delivered = until(jobs, lambda rows: len(rows) == 2*args.count and all(row["state"] == "DELIVERED" for row in rows))
    report["smtp_catch_up_seconds"] = round(time.monotonic() - resumed, 3)
    addresses = [client["email"] for client in clients] + invited_addresses
    counts = [len(mailbox(email)) for email in addresses]
    assert counts == [1]*len(addresses), "Ожидается ровно одно письмо каждому получателю"
    report.update(queued=len(queued), delivered=len(delivered), lost=0, duplicate_messages=0, mailbox_counts=counts, durable_reference_only=True, mail_delivery_verified=True)
    until(lambda: api.request("GET", "/api/v1/auth/options", expected=None), lambda value: value["status"] == 200 and value["body"]["mailAvailable"])
    report["gateway_ready_after_smtp_seconds"] = round(time.monotonic() - resumed, 3)
    reset = link_token(mailbox(clients[0]["email"])[0], "/reset-password")
    api.json("POST", "/api/v1/auth/password-reset", {"token": reset, "password": "Restored2026"})
    revoked_at = time.monotonic()
    checks = []
    for instance, route in [(sender, "/api/v1/users/me"), (single("engagement"), "/api/v1/notifications"), *[(item, "/api/v1/applications") for item in credit_instances]]:
        status = private_status(instance, route, clients[0]["token"])
        checks.append({"container_id": instance, "path": route, "status": status, "ms_after_ack": round((time.monotonic()-revoked_at)*1000, 3)})
        assert status == 401, "Прежняя сессия должна быть отозвана на каждом экземпляре"
    api.request("POST", "/api/v1/auth/password-reset", {"token": reset, "password": "Restored2026"}, expected=(410,))
    invitation = link_token(mailbox(invited_addresses[0])[0], "/accept-invitation")
    accepted = api.json("POST", "/api/v1/auth/invitations/accept", {"token": invitation, "fullName": "Синтетический сотрудник", "password": "Employee2026", "personalDataConsent": True})
    assert not accepted["user"]["canSign"]
    api.request("POST", "/api/v1/auth/invitations/accept", {"token": invitation, "fullName": "Синтетический сотрудник", "password": "Employee2026", "personalDataConsent": True}, expected=(410,))
    report.update(queued=len(queued), delivered=len(delivered), lost=0, duplicate_messages=0, mailbox_counts=counts, durable_reference_only=True,
                  reset_and_invitation_usable_after_sender_restart=True, reuse_rejected=True, revocation_checks=checks, passed=True)
finally:
    action("start", smtp)
    action("start", sender)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
