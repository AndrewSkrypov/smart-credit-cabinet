#!/usr/bin/env python3
"""Verify the patched PostgreSQL entrypoint, initialization and persisted restart."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import time
import uuid

parser = argparse.ArgumentParser(description="Изолированная проверка PostgreSQL без опубликованных портов")
parser.add_argument("--image", default="cabinet/postgres:16.15-alpine-su-exec-0.3")
args = parser.parse_args()
name = "cabinet-submission-pg-check-" + uuid.uuid4().hex[:12]
volume = name + "-data"
report = {"checked_at": datetime.now(timezone.utc).isoformat(), "image": args.image, "checks": [], "passed": False}


def docker(*command):
    result = subprocess.run(["docker", *command], text=True, encoding="utf-8", capture_output=True)
    if result.returncode:
        raise AssertionError(f"Docker {command[0]}: {result.stderr[-1500:]}")
    return result.stdout.strip()


def ready():
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        result = subprocess.run(["docker", "exec", name, "pg_isready", "-U", "cabinet", "-d", "cabinet"], capture_output=True)
        if result.returncode == 0:
            # initdb starts a temporary server before the final one; wait for the actual PID 1.
            command = docker("exec", name, "sh", "-c", "tr '\\0' ' ' </proc/1/cmdline")
            if command.startswith("postgres"):
                return
        time.sleep(1)
    raise AssertionError("PostgreSQL не запустился за 90 с")


try:
    report["image_id"] = json.loads(docker("image", "inspect", args.image))[0]["Id"]
    docker("volume", "create", volume)
    docker("run", "-d", "--name", name, "--network", "none", "-v", f"{volume}:/var/lib/postgresql/data",
           "-e", "POSTGRES_USER=cabinet", "-e", "POSTGRES_PASSWORD=synthetic-test-password", "-e", "POSTGRES_DB=cabinet", args.image)
    ready()
    docker("exec", name, "psql", "-U", "cabinet", "-d", "cabinet", "-v", "ON_ERROR_STOP=1", "-c",
           "CREATE TABLE bootstrap_probe(id integer PRIMARY KEY, value text); INSERT INTO bootstrap_probe VALUES (1, 'persisted');")
    report["checks"].append("Новый том и SQL от root entrypoint")
    uid = docker("exec", name, "sh", "-c", "awk '/^Uid:/{print $2}' /proc/1/status")
    assert uid != "0", "Сервер PostgreSQL запущен от root"
    report["checks"].append("Сервер работает без root")
    docker("exec", name, "cmp", "/usr/local/bin/gosu", "/sbin/su-exec")
    report["checks"].append("Переключение UID/GID через su-exec")
    docker("restart", name)
    ready()
    value = docker("exec", name, "psql", "-U", "cabinet", "-d", "cabinet", "-tAc", "SELECT value FROM bootstrap_probe WHERE id=1")
    assert value == "persisted", "Данные не пережили перезапуск"
    report["checks"].append("Повторный запуск с существующей БД сохраняет данные")
    report["version"] = docker("exec", name, "psql", "-U", "cabinet", "-d", "cabinet", "-tAc", "SHOW server_version")
    report["passed"] = True
finally:
    # Both identifiers were generated above, and never refer to another project's resources.
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    subprocess.run(["docker", "volume", "rm", volume], capture_output=True)
    path = Path(__file__).resolve().parent / "reports/postgres-image.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
