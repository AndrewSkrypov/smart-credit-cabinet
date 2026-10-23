#!/usr/bin/env python3
"""Verified, resumable copy between private local/S3 storage; originals are retained."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
from uuid import UUID, uuid4

from maintenance import Database, private_checkpoint

from botocore.auth import S3SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials


def docker(*args, data=None, timeout=60):
    try:
        result = subprocess.run(["docker", *args], input=data, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"Docker {args[0]} timeout") from None
    if result.returncode:
        # Commands and stderr may contain signed headers. Never expose either in exceptions.
        raise RuntimeError(f"Docker {args[0]} failed with code {result.returncode}")
    return result.stdout


class Transfer:
    def __init__(self, project, run_id):
        if not re.fullmatch(r"cabinet-[a-z0-9-]{1,55}", project):
            raise ValueError("Укажите точное имя проекта cabinet-...")
        self.project = project
        self.run_id = run_id
        self.helper = None
        self.stopped = []
        self.database = None
        self.credits = self.containers("credit")
        postgres = self.containers("postgres")
        storage = self.containers("seaweedfs")
        if not self.credits or len(postgres) != 1 or len(storage) != 1:
            raise ValueError("Нужны credit, один PostgreSQL и работающий профиль storage")
        if not postgres[0]["State"]["Running"] or not storage[0]["State"]["Running"]:
            raise ValueError("PostgreSQL и SeaweedFS должны работать")
        configs = [self.environment(c) for c in self.credits]
        fields = ("S3_ENDPOINT", "S3_BUCKET", "S3_REGION", "S3_ACCESS_KEY", "S3_SECRET_KEY")
        if any(any(config.get(k) != configs[0].get(k) for k in fields) for config in configs):
            raise ValueError("Настройки S3 всех реплик должны совпадать")
        self.env = configs[0]
        if any(not self.env.get(k) for k in fields):
            raise ValueError("Не заполнены настройки S3")
        self.volume = self.document_volume(self.credits[0])
        if any(self.document_volume(c) != self.volume for c in self.credits):
            raise ValueError("Реплики должны использовать общий локальный том")
        networks = set(self.credits[0]["NetworkSettings"]["Networks"]) & set(storage[0]["NetworkSettings"]["Networks"])
        if len(networks) != 1:
            raise ValueError("Не удалось однозначно определить приватную сеть S3")
        self.network = next(iter(networks))
        self.pg = postgres[0]["Id"]
        self.pg_user = self.environment(postgres[0]).get("POSTGRES_USER", "postgres")

    @staticmethod
    def environment(container):
        return dict(v.split("=", 1) for v in container["Config"]["Env"] if "=" in v)

    @staticmethod
    def document_volume(container):
        matches = [m for m in container["Mounts"] if m["Destination"] == "/app/uploads" and m["Type"] == "volume"]
        if len(matches) != 1:
            raise ValueError("Ожидается именованный том /app/uploads")
        return matches[0]["Name"]

    def containers(self, service):
        ids = docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={self.project}",
                     "--filter", f"label=com.docker.compose.service={service}").decode().split()
        return json.loads(docker("inspect", *ids)) if ids else []

    def begin(self, apply, resume=None):
        self.database = Database(self.pg, self.pg_user)
        if self.database.sql("select pg_try_advisory_lock(16781, 8801);") != "t":
            raise RuntimeError("Другой перенос уже удерживает блокировку PostgreSQL")
        if resume:
            current = {c["Id"] for c in self.credits}
            original = {c["id"] for c in resume["credit_writers"]}
            if current != original:
                raise ValueError("Состав credit изменён после прерывания; сначала восстановите состав стенда")
            # Acquired advisory lock proves no live transfer still owns this PostgreSQL session.
            leftovers = docker("ps", "-aq", "--filter", "label=cabinet.storage-transfer=" + self.project,
                               "--filter", "label=cabinet.storage-transfer-run=" + resume["run_id"]).decode().split()
            for helper in leftovers:
                info = json.loads(docker("inspect", helper))[0]
                if self.document_volume(info) != self.volume:
                    raise ValueError("Том прерванного переноса отличается")
                docker("rm", "-f", helper)
            self.stopped = [c["id"] for c in resume["credit_writers"] if c["was_running"]]
        if apply:
            for credit in self.credits:
                if credit["State"]["Running"]:
                    # Track before stopping so interruption still triggers recovery.
                    if credit["Id"] not in self.stopped:
                        self.stopped.append(credit["Id"])
                    docker("stop", credit["Id"])
        self.database.sql("begin isolation level repeatable read;")
        if apply:
            self.database.sql("set local lock_timeout='15s'; lock table documents in share row exclusive mode;")
        name = self.project + "-transfer-" + uuid4().hex[:8]
        self.helper = docker("run", "-d", "--name", name, "--label", "cabinet.storage-transfer=" + self.project,
                             "--label", "cabinet.storage-transfer-run=" + self.run_id,
                             "--label", "com.docker.compose.project=storage-operator",
                             "--label", "com.docker.compose.service=storage-operator", "--network", self.network,
                             "--mount", f"type=volume,source={self.volume},target=/app/uploads" + ("" if apply else ",readonly"),
                             "--entrypoint", "/bin/sh", self.credits[0]["Image"], "-c", "sleep infinity").decode().strip()

    def local(self, name, data=None):
        if data is None:
            script = 'if [ -L "$1" ]; then exit 2; elif [ -f "$1" ]; then cat "$1"; elif [ -e "$1" ]; then exit 2; else exit 44; fi'
            result = subprocess.run(["docker", "exec", self.helper, "sh", "-c", script, "sh", "/app/uploads/" + name],
                                    capture_output=True, timeout=30)
            if result.returncode == 44:
                return None
            if result.returncode:
                raise RuntimeError("Не удалось прочитать обычный локальный файл")
            return result.stdout
        # A hard link publishes only a complete temporary file, atomically and without replacing an existing target.
        script = 'set -eu; tmp=$(mktemp /app/uploads/.transfer-XXXXXXXX); trap \'rm -f "$tmp"\' EXIT; cat > "$tmp"; ln "$tmp" "$1"'
        docker("exec", "-i", self.helper, "sh", "-c", script, "sh", "/app/uploads/" + name, data=data)

    def s3(self, name, data=None):
        url = self.env["S3_ENDPOINT"].rstrip("/") + "/" + self.env["S3_BUCKET"] + "/documents/" + name
        headers = {}
        if data is not None:
            headers = {"If-None-Match": "*", "Content-Length": str(len(data)),
                       "x-amz-checksum-sha256": base64.b64encode(hashlib.sha256(data).digest()).decode()}
        request = AWSRequest(method="GET" if data is None else "PUT", url=url, data=data, headers=headers)
        S3SigV4Auth(Credentials(self.env["S3_ACCESS_KEY"], self.env["S3_SECRET_KEY"]), "s3", self.env["S3_REGION"]).add_auth(request)
        command = ["exec", "-i", self.helper, "curl", "-sS", "--connect-timeout", "2", "--max-time", "25", "-X", request.method]
        for key, value in request.headers.items():
            command += ["-H", key + ": " + value]
        if data is not None:
            command += ["--data-binary", "@-"]
        response = docker(*command, "-w", "\n%{http_code}", url, data=data)
        body, status = response.rsplit(b"\n", 1)
        if data is None and status == b"404":
            return None
        if status not in (b"200", b"204"):
            raise RuntimeError("S3 operation failed with HTTP " + status.decode())
        return body

    def read(self, backend, name):
        return self.local(name) if backend == "LOCAL" else self.s3(name)

    def close(self):
        try:
            if self.database:
                self.database.sql("rollback;")
        finally:
            try:
                if self.helper:
                    docker("rm", "-f", self.helper)
            finally:
                try:
                    if self.stopped:
                        docker("start", *self.stopped)
                        deadline = time.monotonic() + 180
                        while time.monotonic() < deadline:
                            states = json.loads(docker("inspect", *self.stopped))
                            if all(c["State"].get("Health", {}).get("Status") == "healthy" for c in states):
                                break
                            time.sleep(2)
                        else:
                            raise RuntimeError("Credit не восстановил healthy за 180 секунд")
                finally:
                    if self.database:
                        self.database.close()


def migrate(project, target, apply, report_path, after_copy=None, resume_path=None):
    target = target.upper()
    if target not in ("LOCAL", "S3"):
        raise ValueError("target must be LOCAL or S3")
    resume = None
    if resume_path:
        resume = json.loads(Path(resume_path).read_text(encoding="utf-8"))
        if not apply or resume["project"] != project or resume["target"] != target or not resume["apply"]:
            raise ValueError("Возобновление требует того же проекта, направления и --apply")
        if not re.fullmatch(r"[a-f0-9]{32}", resume["run_id"]):
            raise ValueError("Неизвестный идентификатор переноса")
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": project, "target": target, "run_id": uuid4().hex,
              "apply": apply, "passed": False, "metadata_committed": False, "documents": []}
    report_path = Path(report_path)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive private report: a rerun keeps its predecessor's evidence.
    descriptor = os.open(report_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    transfer = None
    os.close(descriptor)

    def checkpoint():
        private_checkpoint(report_path, report)

    try:
        transfer = Transfer(project, report["run_id"])
        report["credit_writers"] = resume["credit_writers"] if resume else [
            {"id": c["Id"], "was_running": c["State"]["Running"]} for c in transfer.credits]
        checkpoint()
        transfer.begin(apply, resume)
        report["helper_id"] = transfer.helper
        checkpoint()
        rows = json.loads(transfer.database.sql("select coalesce(json_agg(t),'[]') from (select id,stored_name,size_bytes,storage_backend,checksum_sha256 from documents order by id) t;"))
        for row in rows:
            identifier = str(UUID(row["id"]))
            name = row["stored_name"]
            if not re.fullmatch(r"[A-Za-z0-9_.-]{1,255}", name) or name in (".", ".."):
                raise ValueError("Небезопасное внутреннее имя документа")
            source = row["storage_backend"]
            if source not in ("LOCAL", "S3"):
                raise ValueError("Неизвестное расположение документа")
            content = transfer.read(source, name)
            if content is None or len(content) != row["size_bytes"]:
                raise ValueError("Отсутствует исходник или не совпадает размер документа " + identifier)
            checksum = hashlib.sha256(content).hexdigest()
            if row["checksum_sha256"] not in (None, checksum):
                raise ValueError("Не совпадает исходный SHA-256 документа " + identifier)
            action = "already_target" if source == target else "would_copy"
            if source != target:
                existing = transfer.read(target, name)
                if existing is not None and existing != content:
                    raise ValueError("Целевой объект отличается; перезапись запрещена: " + identifier)
                if existing is not None:
                    action = "reused_verified_copy"
                elif apply:
                    (transfer.local if target == "LOCAL" else transfer.s3)(name, content)
                    action = "copied"
                if apply and transfer.read(target, name) != content:
                    raise ValueError("Копия не совпала с исходником: " + identifier)
            report["documents"].append({"id": identifier, "source": source, "bytes": len(content), "sha256": checksum, "action": action})
            checkpoint()
            if after_copy:
                after_copy(len(report["documents"]))
            if apply:
                updated = transfer.database.sql(
                    f"update documents set storage_backend='{target}',checksum_sha256='{checksum}' "
                    f"where id='{identifier}' and storage_backend='{source}' and size_bytes={len(content)} "
                    f"and (checksum_sha256 is null or checksum_sha256='{checksum}') returning id;")
                if updated != identifier:
                    raise RuntimeError("Метаданные документа изменились во время переноса")
        transfer.database.sql("commit;" if apply else "rollback;")
        report["metadata_committed"] = apply
        report["passed"] = True
    finally:
        try:
            if transfer:
                transfer.close()
            report["writers_resumed"] = True
        except BaseException:
            report["passed"] = False
            report["writers_resumed"] = False
            raise
        finally:
            report["finished_at"] = datetime.now(timezone.utc).isoformat()
            checkpoint()
    return report


def main():
    parser = argparse.ArgumentParser(description="Проверяемый перенос документов; по умолчанию dry-run без изменений")
    parser.add_argument("--project", required=True)
    parser.add_argument("--target", required=True, choices=("local", "s3"))
    parser.add_argument("--apply", action="store_true", help="Копировать и изменить расположение; временно остановить все credit")
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--resume-report", type=Path, help="Отчёт прерванного переноса для восстановления прежнего состояния credit")
    args = parser.parse_args()
    def interrupted(_number, _frame):
        raise InterruptedError("Перенос прерван сигналом; выполняется восстановление сервисов")
    signal.signal(signal.SIGTERM, interrupted)
    report = migrate(args.project, args.target, args.apply, args.report, resume_path=args.resume_report)
    print(json.dumps({k: v for k, v in report.items() if k != "documents"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
