#!/usr/bin/env python3
"""Prove generated-file failures cannot undo issuance; needs a synthetic generated-files fixture."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.request import urlopen
from uuid import UUID

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet, local_request  # noqa: E402

parser = argparse.ArgumentParser(description="Отказ и восстановление generated artifacts в собственном S3")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-e2e")
parser.add_argument("--application", type=UUID, required=True, help="Выданная заявка из load/generated-files.py в S3-режиме")
parser.add_argument("--report-name", default="generated-storage")
args = parser.parse_args()
if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.report_name):
    parser.error("Нужно безопасное имя отчёта")
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
        result = probe()
        if condition(result):
            return result
        if time.monotonic() >= deadline:
            raise AssertionError("S3-проверка не завершилась за отведённое время")
        time.sleep(0.5)


postgres, storage, credit = single("postgres"), single("seaweedfs"), single("credit")
environment = dict(value.split("=", 1) for value in json.loads(docker("inspect", credit))[0]["Config"]["Env"])
assert environment.get("STORAGE_BACKEND") == "s3" and environment.get("S3_ENDPOINT") == "http://seaweedfs:8333"
assert api.json("GET", "/api/v1/auth/options")["demoAccounts"]
original = str(args.application)
actor = docker("exec", postgres, "psql", "-X", "-U", "cabinet", "-d", "credit", "-tAc", f"select created_by from applications where id='{original}'")
contact = api.service("identity", "GET", "/internal/v1/users/" + str(UUID(actor)))["body"]
assert contact["email"].startswith("files-updated-") and contact["email"].endswith("@example.test"), "Используйте только fixture generated-files"
token = api.sign_in(contact["email"], "Test2026safe")["token"]
time.sleep(2.1)
bank = api.sign_in("underwriter@demo.test")["token"]


def files(identifier):
    return api.json("GET", f"/api/v1/applications/{identifier}/artifacts", token=token)


def keys(identifier):
    return json.loads(docker("exec", postgres, "psql", "-X", "-U", "cabinet", "-d", "credit", "-tAc",
                             f"select json_object_agg(id,stored_name) from generated_artifacts where application_id='{UUID(identifier)}'"))


try:
    loan_before = api.json("GET", f"/api/v1/applications/{original}", token=token)["process"]["loan"]
    initial = files(original)
    assert len(initial) == 3 and all(item["status"] == "READY" for item in initial)
    original_keys = keys(original)
    docker("stop", storage)
    failed = api.request("GET", initial[0]["downloadUrl"], token=token, expected=(503,))
    assert "временно недоступен" in failed["body"]["message"]
    report["unavailable_download_http"] = failed["status"]

    started = time.monotonic()
    app = api.create(token, amount=300_000)
    new_id = app["id"]
    report["application_issued_while_s3_down"] = new_id
    api.json("POST", f"/api/v1/applications/{new_id}/submit", token=token)
    decision = api.wait(token, new_id, {"OFFER_ISSUED", "MANUAL_REVIEW", "REJECTED"})
    if decision["status"] == "MANUAL_REVIEW":
        api.json("POST", f"/api/v1/underwriting/applications/{new_id}/approve", {"comment": "Синтетическая проверка независимости выдачи от файлового хранилища"}, bank)
    accepted = api.json("POST", f"/api/v1/applications/{new_id}/offer/accept", token=token)
    api.json("POST", f"/api/v1/applications/{new_id}/sign", {"code": accepted["process"]["signing"]["code"]}, token)
    issued = api.wait(token, new_id, {"DISBURSED"})
    report["issuance_seconds_while_s3_down"] = round(time.monotonic() - started, 3)
    pending = until(lambda: files(new_id), lambda values: any(item["status"] == "ERROR" for item in values))
    assert len(pending) == 3 and all(item["status"] != "READY" and item["downloadUrl"] is None for item in pending)
    new_keys = keys(new_id)
    report["write_failure_did_not_rollback_issuance"] = True
    docker("start", storage)
    until(lambda: docker("inspect", "--format", "{{.State.Health.Status}}", storage), lambda state: state == "healthy")
    for identifier in [original, new_id]:
        for item in files(identifier):
            api.json("POST", f"/api/v1/applications/{identifier}/artifacts/{item['id']}/retry", token=token)
        ready = until(lambda: files(identifier), lambda values: len(values) == 3 and all(item["status"] == "READY" for item in values))
        for item in ready:
            with urlopen(local_request(api.base + item["downloadUrl"], headers={"Authorization": "Bearer " + token}), timeout=30) as response:
                data = response.read()
            assert len(data) == item["sizeBytes"] and hashlib.sha256(data).hexdigest() == item["checksum"]
        assert keys(identifier) == (original_keys if identifier == original else new_keys)
    assert loan_before == api.json("GET", f"/api/v1/applications/{original}", token=token)["process"]["loan"]
    assert issued["process"]["loan"] == api.json("GET", f"/api/v1/applications/{new_id}", token=token)["process"]["loan"]
    report.update(downloads_verified_after_recovery=6, stable_file_ids_and_keys=True, both_loans_unchanged=True, passed=True)
finally:
    docker("start", storage)
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
