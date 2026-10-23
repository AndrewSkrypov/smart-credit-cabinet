#!/usr/bin/env python3
"""Verify real copies, abrupt interruption, replay, reverse transfer and authorized downloads."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.request import urlopen
from uuid import UUID, uuid4

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
sys.path.insert(0, str(ROOT / "deploy"))
from cabinet_api import DockerCabinet, local_request  # noqa: E402
from storage_transfer import Database, Transfer, docker, migrate  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description="Проверить перенос только на локальном синтетическом стенде")
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--project", default="cabinet-e2e")
    parser.add_argument("--report-name", default="storage-transfer")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.report_name):
        parser.error("Нужно безопасное имя отчёта")
    api = DockerCabinet(args.base, args.project)
    for cid in api.containers("identity"):
        assert "DEMO_ACCOUNTS=true" in json.loads(docker("inspect", cid))[0]["Config"]["Env"]
    directory = ROOT / "load/reports" / args.report_name
    directory.mkdir(parents=True, exist_ok=False)
    pg = docker("ps", "-q", "--filter", "label=com.docker.compose.project=" + args.project,
                "--filter", "label=com.docker.compose.service=postgres").decode().split()
    assert len(pg) == 1
    credits = api.containers("credit")
    assert len(credits) == 2
    for cid in credits:
        env = json.loads(docker("inspect", cid))[0]["Config"]["Env"]
        assert "STORAGE_BACKEND=s3" in env and "ANTIVIRUS_ENABLED=true" in env

    def sql(query):
        return docker("exec", pg[0], "psql", "-X", "-U", "cabinet", "-d", "credit", "-tAc", query).decode().strip()

    def metadata_hash():
        return hashlib.sha256(sql("select row_to_json(d)::text from documents d order by id").encode()).hexdigest()

    owner = api.sign_in("technopark@demo.test")
    bank = api.sign_in("manager@demo.test")
    other = api.sign_in("client@demo.test")
    sample = b"%PDF-1.7\nSynthetic migration verification\n%%EOF\n"
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "passed": False, "checks": []}
    app_id = None
    fixture_rows = []

    def verify_api(stage):
        for document in fixture_rows:
            route = f"/api/v1/applications/{document['application_id']}/documents/{document['id']}"
            for session in (owner, bank):
                with urlopen(local_request(api.base + route, headers={"Authorization": "Bearer " + session["token"]}), timeout=15) as response:
                    assert response.status == 200
                    assert hashlib.sha256(response.read()).hexdigest() == document["checksum"]
                    assert "attachment" in response.headers["Content-Disposition"]
            denied = api.request("GET", route, token=other["token"], expected=(404,))
            assert denied["status"] == 404
        report["checks"].append({"stage": stage, "documents": len(fixture_rows), "owner_bank_downloads": 2 * len(fixture_rows), "foreign_denied": len(fixture_rows)})

    try:
        # Start with local records, regardless of the write mode used by the surrounding suite.
        initial = migrate(args.project, "LOCAL", True, directory / "initial-local.json")
        assert initial["passed"]
        source_rows = {row["id"]: row for row in initial["documents"]}
        user_id = str(UUID(owner["user"]["id"]))
        existing = json.loads(sql(
            "select coalesce(json_agg(t),'[]') from (select distinct on (context) id,application_id,context from documents "
            f"where uploaded_by='{user_id}' and deleted_at is null and "
            "(context='BUSINESS' or exists(select 1 from message_attachments m where m.document_id=documents.id)) order by context,uploaded_at) t"))
        for row in existing:
            fixture_rows.append({**row, "checksum": source_rows[row["id"]]["sha256"]})
        scenario = next(c for c in api.json("GET", "/sim/control")["companies"] if c["inn"] == "0001001046")
        app = api.json("POST", "/api/v1/applications", {**scenario["scenario"]["request"], "bureauConsent": True}, owner["token"], expected=(201,))
        app_id = str(UUID(app["id"]))
        api.json("POST", f"/api/v1/applications/{app_id}/submit", token=owner["token"])
        api.wait(owner["token"], app_id, {"INFO_REQUESTED"})
        business = api.upload(owner["token"], app_id, "OTHER", sample)["body"]
        chat = api.upload_chat(owner["token"], app_id, sample)["body"]
        api.json("POST", f"/api/v1/applications/{app_id}/messages", {"text": "Проверка сохранности вложения", "documentIds": [chat["id"]]},
                 owner["token"], expected=(200, 201), headers={"Idempotency-Key": str(uuid4())})
        for context, document in (("BUSINESS", business), ("CHAT", chat)):
            fixture_rows.append({"id": document["id"], "application_id": app_id, "context": context, "checksum": hashlib.sha256(sample).hexdigest()})
        verify_api("before")

        before = metadata_hash()
        dry = migrate(args.project, "S3", False, directory / "dry-run.json")
        assert dry["passed"] and metadata_hash() == before
        assert any(row["action"] == "would_copy" for row in dry["documents"]) or any(row["action"] == "reused_verified_copy" for row in dry["documents"])

        # Another real PostgreSQL session prevents a second maintenance process before it stops writers.
        guard = Database(pg[0], "cabinet")
        try:
            assert guard.sql("select pg_try_advisory_lock(16781,8801);") == "t"
            try:
                migrate(args.project, "S3", True, directory / "concurrent-rejected.json")
            except RuntimeError as error:
                assert "Другой перенос" in str(error)
            else:
                raise AssertionError("Concurrent transfer was permitted")
        finally:
            guard.close()
        assert metadata_hash() == before and set(api.containers("credit")) == set(credits)
        report["concurrent_transfer_rejected"] = True

        interrupted = directory / "interrupted.json"
        code = "import os,sys;sys.path.insert(0,'deploy');from storage_transfer import migrate;migrate(sys.argv[1],'S3',True,sys.argv[2],after_copy=lambda n: os._exit(77) if n==10 else None)"
        crashed = subprocess.run([sys.executable, "-c", code, args.project, str(interrupted)], cwd=ROOT, capture_output=True)
        assert crashed.returncode == 77, "Expected abrupt transfer process exit"
        assert metadata_hash() == before, "Interrupted transaction changed document metadata"
        report["abrupt_exit"] = 77
        report["interrupted_metadata_unchanged"] = True
        forward = migrate(args.project, "S3", True, directory / "forward.json", resume_path=interrupted)
        assert forward["passed"] and forward["writers_resumed"]
        verify_api("after_forward_resume")

        before_replay = metadata_hash()
        replay = migrate(args.project, "S3", True, directory / "replay.json")
        assert replay["passed"] and metadata_hash() == before_replay
        assert all(row["action"] == "already_target" for row in replay["documents"])
        report["replay_without_copy"] = len(replay["documents"])

        # A new upload made after cutover must also survive reverse migration.
        extra = api.upload_chat(owner["token"], app_id, sample, filename="after-cutover.pdf")["body"]
        api.json("POST", f"/api/v1/applications/{app_id}/messages", {"text": "Файл после переключения", "documentIds": [extra["id"]]},
                 owner["token"], expected=(200, 201), headers={"Idempotency-Key": str(uuid4())})
        fixture_rows.append({"id": extra["id"], "application_id": app_id, "context": "CHAT", "checksum": hashlib.sha256(sample).hexdigest()})
        extra_id = str(UUID(extra["id"]))
        name = sql(f"select stored_name from documents where id='{extra_id}'")
        assert re.fullmatch(r"[a-f0-9-]+\.pdf", name)
        conflicting = b"Deliberately different synthetic target"
        operator = Transfer(args.project, uuid4().hex)
        try:
            operator.begin(True)
            assert operator.local(name) is None
            operator.local(name, conflicting)
        finally:
            operator.close()
        before_conflict = metadata_hash()
        try:
            try:
                migrate(args.project, "LOCAL", True, directory / "conflict-rejected.json")
            except ValueError as error:
                assert "перезапись запрещена" in str(error)
            else:
                raise AssertionError("Different target content was overwritten")
            assert metadata_hash() == before_conflict
            report["different_target_rejected_without_metadata_changes"] = True
        finally:
            operator = Transfer(args.project, uuid4().hex)
            try:
                operator.begin(True)
                assert operator.local(name) == conflicting, "The existing target was modified"
                # Remove only the newly created conflicting fixture, never the original in S3.
                docker("exec", operator.helper, "rm", "--", "/app/uploads/" + name)
            finally:
                operator.close()
        reverse = migrate(args.project, "LOCAL", True, directory / "reverse.json")
        assert reverse["passed"] and all(row["source"] == "S3" for row in reverse["documents"])
        verify_api("after_reverse_including_new_upload")
        final = migrate(args.project, "S3", True, directory / "final-s3.json")
        assert final["passed"] and all(row["action"] == "reused_verified_copy" for row in final["documents"])
        verify_api("after_final_s3")
        report["documents_verified"] = len(final["documents"])
        report["passed"] = True
    finally:
        try:
            # The owned fixture process is terminal before cleanup; recover any interrupted state via its report.
            if (directory / "interrupted.json").exists() and not (directory / "forward.json").exists():
                migrate(args.project, "S3", True, directory / "cleanup-resume.json", resume_path=directory / "interrupted.json")
            if app_id:
                api.json("POST", f"/api/v1/applications/{app_id}/withdraw", {"comment": "Завершение проверки переноса"}, owner["token"])
        except BaseException:
            report["passed"] = False
            report["cleanup_failed"] = True
            raise
        finally:
            report["finished_at"] = datetime.now(timezone.utc).isoformat()
            (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
