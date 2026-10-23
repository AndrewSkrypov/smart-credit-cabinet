#!/usr/bin/env python3
"""Measure fail-closed uploads and recovery on both credit replicas with real ClamAV/S3."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet  # noqa: E402


def docker(*args, data=None):
    result = subprocess.run(["docker", *args], input=data, capture_output=True)
    if result.returncode:
        raise RuntimeError(f"Docker {args[0]} failed, code {result.returncode}")
    return result.stdout.decode("utf-8").strip()


def main():
    parser = argparse.ArgumentParser(description="Отказ и восстановление ClamAV/S3 на отдельном демо-стенде")
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--project", default="cabinet-e2e")
    parser.add_argument("--report-name", default="storage")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.report_name):
        parser.error("Нужно безопасное имя отчёта")
    api = DockerCabinet(args.base, args.project)

    def containers(service):
        return docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                      "--filter", f"label=com.docker.compose.service={service}").split()

    for identity in api.containers("identity"):
        assert "DEMO_ACCOUNTS=true" in json.loads(docker("inspect", identity))[0]["Config"]["Env"], "Только синтетический демо-стенд"
    credits = api.containers("credit")
    assert len(credits) == 2, "Нужны ровно два экземпляра credit"
    for credit in credits:
        env = json.loads(docker("inspect", credit))[0]["Config"]["Env"]
        assert "STORAGE_BACKEND=s3" in env and "ANTIVIRUS_ENABLED=true" in env
    sidecars = {name: containers(name) for name in ("clamav", "seaweedfs", "postgres")}
    assert all(len(ids) == 1 for ids in sidecars.values())
    session = api.sign_in("technopark@demo.test")
    token = session["token"]
    scenario = next(c for c in api.json("GET", "/sim/control")["companies"] if c["inn"] == "0001001046")
    app = api.json("POST", "/api/v1/applications", {**scenario["scenario"]["request"], "bureauConsent": True}, token, expected=(201,))
    app_id = str(UUID(app["id"]))
    path = f"/api/v1/applications/{app_id}"
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project,
              "replicas": len(credits), "passed": False, "faults": []}
    report["images"] = list({json.loads(docker("inspect", cid))[0]["Image"] for cid in credits})
    stopped = set()
    sample = b"%PDF-1.7\nSynthetic storage fault check\n%%EOF\n"
    directory = ROOT / "load/reports" / args.report_name
    directory.mkdir(parents=True, exist_ok=True)

    def snapshot():
        values = {}
        for table in ("documents", "application_events", "application_chat_audit"):
            values[table] = int(docker("exec", sidecars["postgres"][0], "psql", "-X", "-U", "cabinet", "-d", "credit", "-tAc",
                                       f"select count(*) from {table} where application_id='{app_id}'"))
        values["version"] = api.json("GET", path, token=token)["version"]
        return values

    def upload(cid, context, expected):
        route = path + ("/documents?type=OTHER" if context == "BUSINESS" else "/messages/attachments")
        started = time.monotonic()
        raw = docker("exec", "-i", cid, "curl", "-sS", "--connect-timeout", "2", "--max-time", "20", "-X", "POST",
                     "-H", "Authorization: Bearer " + token, "-H", "Idempotency-Key: " + str(uuid4()),
                     "-F", "file=@-;filename=synthetic.pdf;type=application/pdf", "-w", "\n%{http_code}",
                     "http://localhost:8080" + route, data=sample)
        body, code = raw.rsplit("\n", 1)
        status = int(code)
        if expected is not None:
            assert status == expected, f"{context}: HTTP {status}, expected {expected}: {json.loads(body).get('message', '')}"
        elapsed = round(time.monotonic() - started, 3)
        assert elapsed < 20, "Upload failure exceeded its deadline"
        return {"replica": cid[:12], "context": context, "status": status, "seconds": elapsed}, json.loads(body)

    def start(service):
        docker("start", sidecars[service][0])
        until = time.monotonic() + 180
        while time.monotonic() < until:
            state = json.loads(docker("inspect", sidecars[service][0]))[0]["State"]
            if state.get("Health", {}).get("Status") == "healthy":
                stopped.discard(service)
                return
            time.sleep(2)
        raise RuntimeError(f"{service}: recovery health deadline")

    try:
        api.json("POST", path + "/submit", token=token)
        api.wait(token, app_id, {"INFO_REQUESTED"})
        for service, message in (("clamav", "Проверка файлов временно недоступна"), ("seaweedfs", "Хранилище документов временно недоступно")):
            before = snapshot()
            fault = {"service": service, "rejected": [], "recovered": []}
            report["faults"].append(fault)
            stopped.add(service)
            docker("stop", sidecars[service][0])
            for cid in credits:
                for context in ("BUSINESS", "CHAT"):
                    observation, body = upload(cid, context, 503)
                    assert message in body["message"]
                    fault["rejected"].append(observation)
                    assert snapshot() == before, "Rejected upload changed metadata, journal or application version"
            fault["no_database_changes"] = True
            started = time.monotonic()
            start(service)
            fault["recovery_health_seconds"] = round(time.monotonic() - started, 3)
            fault["recovery_retries"] = []
            for cid in credits:
                for context in ("BUSINESS", "CHAT"):
                    deadline = time.monotonic() + 45
                    expected = 201 if context == "BUSINESS" else 200
                    while True:
                        before_retry = snapshot()
                        observation, body = upload(cid, context, None)
                        if observation["status"] == expected:
                            break
                        assert observation["status"] == 503 and message in body["message"], "Unexpected recovery error"
                        assert snapshot() == before_retry, "A failed recovery attempt changed persisted data"
                        fault["recovery_retries"].append(observation)
                        assert time.monotonic() < deadline, "Uploads did not recover within 45 seconds after container health"
                        time.sleep(2)
                    fault["recovered"].append(observation)
                    document_id = str(UUID(body["id"]))
                    downloaded = api.request("GET", path + "/documents/" + document_id, token=token)
                    assert downloaded["body"].encode() == sample
            fault["download_bytes_verified"] = True
            fault["all_replicas_recovered_seconds"] = round(time.monotonic() - started, 3)
        report["passed"] = True
    finally:
        for service in list(stopped):
            start(service)
        try:
            api.json("POST", path + "/withdraw", {"comment": "Завершение проверки хранилища"}, token)
        finally:
            report["finished_at"] = datetime.now(timezone.utc).isoformat()
            (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
