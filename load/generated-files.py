#!/usr/bin/env python3
"""Exercise final files and readiness mail on a named synthetic stand, using Mailpit only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import urlopen
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet, local_request  # noqa: E402

parser = argparse.ArgumentParser(description="Файлы и письмо о готовности при отказе SMTP, смене email, отписке и отзыве")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-e2e")
parser.add_argument("--mailpit", default="http://127.0.0.1:8025")
parser.add_argument("--report-name", default="generated-files")
args = parser.parse_args()
if not re.fullmatch(r"http://(?:localhost|127\.0\.0\.1):[0-9]+", args.mailpit) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.report_name):
    parser.error("Требуются локальный Mailpit и безопасное имя отчёта")
api = DockerCabinet(args.base, args.project)
directory = ROOT / "load/reports" / args.report_name
directory.mkdir(parents=True, exist_ok=True)
report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "passed": False}


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], cwd=ROOT, text=True, encoding="utf-8").strip()


def single(service):
    ids = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}", "--filter", f"label=com.docker.compose.service={service}").split()
    assert len(ids) == 1, "Нужна одна реплика " + service
    return ids[0]


def until(probe, condition, timeout=180):
    deadline = time.monotonic() + timeout
    while True:
        value = probe()
        if condition(value):
            return value
        if time.monotonic() >= deadline:
            raise AssertionError("Проверка файлов или почты не завершилась за отведённое время")
        time.sleep(0.5)


def mailbox(address):
    with urlopen(args.mailpit + "/api/v1/search?" + urlencode({"query": "to:" + address}), timeout=10) as response:
        return json.load(response)["messages"]


def message(identifier):
    with urlopen(args.mailpit + "/api/v1/message/" + identifier, timeout=10) as response:
        return json.load(response)


postgres, smtp, sender = single("postgres"), single("mailpit"), single("engagement")
environment = dict(value.split("=", 1) for value in json.loads(docker("inspect", sender))[0]["Config"]["Env"])
assert environment.get("CABINET_MAIL_HOST") == "mailpit", "Разрешён только собственный SMTP-перехватчик"
assert api.json("GET", "/api/v1/auth/options")["demoAccounts"], "Требуется синтетический стенд"
report["images"] = []
for service in ["identity", "credit", "engagement"]:
    container = json.loads(docker("inspect", single(service)))[0]
    revision = docker("image", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}', container["Image"])
    report["images"].append({"service": service, "image": container["Image"], "revision": revision})


def sql(database, query):
    return json.loads(docker("exec", postgres, "psql", "-X", "-U", "cabinet", "-d", database, "-tAc", query))


def subscribe(token, enabled):
    previous = api.json("GET", "/api/v1/notifications/settings", token=token)
    return api.json("PUT", "/api/v1/notifications/settings", {"emailEnabled": enabled, "version": previous["version"]}, token=token)


try:
    bank = api.sign_in("underwriter@demo.test")["token"]
    time.sleep(2.1)
    owner = api.register("files-owner")
    subscribe(owner["token"], True)
    colleagues = []
    for label in ["revoked", "optout"]:
        address = f"files-{label}-{uuid4().hex[:12]}@example.test"
        invitation = api.json("POST", "/api/v1/companies/me/invitations", {"email": address, "canSign": False}, owner["token"], headers={"Idempotency-Key": str(uuid4())})
        assert invitation.get("link") is None, "При mail-on открытый токен не возвращается"
        mails = until(lambda: mailbox(address), lambda values: bool(values))
        text = message(mails[0]["ID"])["Text"]
        url = urlparse(re.search(r"https?://\S+", text).group())
        invite_token = parse_qs(url.fragment)["token"][0]
        time.sleep(2.1)
        employee = api.json("POST", "/api/v1/auth/invitations/accept", {
            "token": invite_token, "fullName": "Синтетический сотрудник " + label, "password": "Test2026safe", "personalDataConsent": True,
        })
        subscribe(employee["token"], True)
        colleagues.append({"token": employee["token"], "user_id": employee["user"]["id"], "email": address})

    docker("stop", smtp)
    app = api.create(owner["token"], amount=300_000)
    app_id = app["id"]
    report["application_id"] = app_id
    api.json("POST", f"/api/v1/applications/{app_id}/submit", token=owner["token"])
    decision = api.wait(owner["token"], app_id, {"OFFER_ISSUED", "MANUAL_REVIEW", "REJECTED"})
    if decision["status"] == "MANUAL_REVIEW":
        api.json("POST", f"/api/v1/underwriting/applications/{app_id}/approve", {"comment": "Синтетическая проверка готовности файлов и почтовых повторов"}, bank)
    accepted = api.json("POST", f"/api/v1/applications/{app_id}/offer/accept", token=owner["token"])
    api.json("POST", f"/api/v1/applications/{app_id}/sign", {"code": accepted["process"]["signing"]["code"]}, owner["token"])
    issued = api.wait(owner["token"], app_id, {"DISBURSED"})
    files = until(lambda: api.json("GET", f"/api/v1/applications/{app_id}/artifacts", token=owner["token"]), lambda values: len(values) == 3 and all(row["status"] == "READY" for row in values))
    assert issued["documents"] == []
    for file in files:
        response = api.request("POST", f"/api/v1/applications/{app_id}/artifacts/{file['id']}/retry", token=owner["token"])
        assert response["body"]["id"] == file["id"] and response["body"]["status"] == "READY"
        assert "storedName" not in file and "storageBackend" not in file
        with urlopen(local_request(api.base + file["downloadUrl"], headers={"Authorization": "Bearer " + owner["token"]}), timeout=30) as download:
            content = download.read()
            assert download.headers["Content-Type"] == file["contentType"]
            assert len(content) == file["sizeBytes"] and hashlib.sha256(content).hexdigest() == file["checksum"]
        (directory / file["fileName"]).write_bytes(content)
    report["files_ready_during_smtp_failure"] = 3

    recipients = [owner, *colleagues]
    users = ",".join("'" + str(UUID(person["user_id"])) + "'" for person in recipients)
    where = f"channel='email-notification' and payload->'content'->>'kind'='FILES_READY' and payload->>'recipientId' in ({users})"

    def jobs():
        return sql("engagement", f"select coalesce(json_agg(t),'[]') from (select id,event_key,state,attempts,payload->>'recipientId' as recipient from delivery_outbox where {where}) t")

    pending = until(jobs, lambda rows: len(rows) == 3 and all(row["attempts"] > 0 for row in rows))
    assert all(row["state"] in {"PENDING", "RUNNING"} for row in pending)
    updated_address = f"files-updated-{uuid4().hex[:12]}@example.test"
    updated = api.json("PUT", "/api/v1/users/me", {"fullName": "Представитель синтетической компании", "email": updated_address, "currentPassword": "Test2026safe"}, owner["token"])
    owner["token"] = updated["token"]
    api.json("DELETE", f"/api/v1/companies/me/employees/{colleagues[0]['user_id']}/access", token=owner["token"], headers={"Idempotency-Key": str(uuid4())})
    subscribe(colleagues[1]["token"], False)
    assert api.request("GET", f"/api/v1/applications/{app_id}/artifacts", token=colleagues[0]["token"], expected=None)["status"] == 401
    docker("restart", sender)
    assert {row["id"] for row in jobs()} == {row["id"] for row in pending}, "Перезапуск потерял очередь"
    docker("start", smtp)
    completed = until(jobs, lambda rows: len(rows) == 3 and all(row["state"] == "DELIVERED" for row in rows))
    ready_mail = until(lambda: [mail for mail in mailbox(updated_address) if mail["Subject"] == "Документы по кредиту готовы"], lambda values: len(values) == 1)
    detail = message(ready_mail[0]["ID"])
    assert detail["Text"].rstrip().endswith(f"/client/applications/{app_id}")
    assert "PDF и XLSX" in detail["Text"]
    assert not re.search(r"generated-|documents/|storage_backend|s3://|R_SCORE|300000", detail["Text"])
    job = next(row for row in completed if row["recipient"] == owner["user_id"])
    stable_id = str(UUID(bytes=hashlib.md5(job["event_key"].encode()).digest(), version=3)) + "@cabinet.local"
    observed_id = detail.get("MessageID", ready_mail[0].get("MessageID", "")).strip("<>")
    assert observed_id == stable_id, "SMTP Message-ID не совпадает с устойчивым ключом задания"
    assert not [mail for mail in mailbox(owner["email"]) if mail["Subject"] == "Документы по кредиту готовы"]
    for colleague in colleagues:
        assert not [mail for mail in mailbox(colleague["email"]) if mail["Subject"] == "Документы по кредиту готовы"]
    assert files == api.json("GET", f"/api/v1/applications/{app_id}/artifacts", token=owner["token"])
    assert issued["process"]["loan"] == api.json("GET", f"/api/v1/applications/{app_id}", token=owner["token"])["process"]["loan"]
    report.update(queued_ready_jobs=3, ready_smtp_messages=1, preference_suppressed=1, revoked_suppressed=1,
                  current_email_after_retry=True, stable_message_id=observed_id, files_survived_mail_failure=True, passed=True)
finally:
    docker("start", smtp, sender)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
