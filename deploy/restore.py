#!/usr/bin/env python3
"""Restore a verified backup only into an empty, differently named production-check project."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
from common import Deployment, ROOT
from backup_support import DATABASES, checked_docker, database_snapshot, digest, document_inventory, storage_volume, backup_archives

parser = argparse.ArgumentParser(description="Восстановление только в новый пустой cabinet-prod-restore* проект")
parser.add_argument("backup")
parser.add_argument("--project", default="cabinet-prod-restore")
parser.add_argument("--env-file", required=True)
parser.add_argument("--local", action="store_true")
args = parser.parse_args()
assert args.project.startswith("cabinet-prod-restore"), "Для восстановления нужен отдельный новый проект cabinet-prod-restore*"
deployment = Deployment(args.project, args.env_file, args.local)
directory = Path(args.backup).resolve()
manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
archives = backup_archives(manifest)
assert args.project != manifest["source_project"], "Нельзя перезаписать исходный проект копии"
assert deployment.values["CABINET_RELEASE"] == manifest["release"], "Сначала выберите тот же выпуск приложений, что и в копии"
for name, expected in manifest["files"].items():
    path = directory / name
    assert path.is_file() and path.stat().st_size == expected["bytes"] and digest(path) == expected["sha256"], "Нарушена целостность копии: " + name
for archive, (_, inventory) in archives.items():
    assert document_inventory(directory / archive) == inventory, "Архив хранилища не совпадает с manifest"
if manifest.get("storage", {}).get("backends", {}).get("S3", 0):
    assert deployment.values.get("STORAGE_BACKEND") == "s3" or deployment.values.get("STORAGE_ENABLED") == "true", "Для восстановления S3 включите storage"
    credit_environment = deployment.config()["services"]["credit"]["environment"]
    assert credit_environment["S3_ENDPOINT"] == "http://seaweedfs:8333", "Восстановление тома S3 требует собственного SeaweedFS"
    assert credit_environment["S3_BUCKET"] == manifest["storage"]["s3_bucket"], "S3_BUCKET должен совпасть с бакетом копии"
report = {"checked_at": datetime.now(timezone.utc).isoformat(), "source_project": manifest["source_project"], "target_project": args.project,
          "passed": False, "checks": []}
try:
    for service in ["identity", "credit", "engagement", "caddy", "seaweedfs"]:
        assert not deployment.compose("ps", "-a", "-q", service, capture=True).strip(), "Целевой проект уже содержит приложения; выберите новый"
    deployment.compose("up", "-d", "--build", "--wait", "--wait-timeout", "180", "postgres")
    deployment.compose("run", "--rm", "--no-deps", "db-init")
    assert deployment.sql("credit", "show server_version").split(".")[0] == manifest["postgres_version"].split(".")[0], "Нужна та же основная версия PostgreSQL"
    for database in DATABASES:
        tables = int(deployment.sql(database, "select count(*) from pg_tables where schemaname='public'"))
        assert tables == 0, "Целевая БД не пуста; данные не будут перезаписаны"
    image = deployment.config()["services"]["postgres"]["image"]
    for _, (key, _) in archives.items():
        volume = storage_volume(deployment, key)
        checked_docker("volume", "create", "--label", "com.docker.compose.project=" + args.project,
                       "--label", "com.docker.compose.volume=" + key, volume)
        metadata = json.loads(subprocess.check_output(["docker", "volume", "inspect", volume], text=True))[0]
        assert metadata["Labels"].get("com.docker.compose.project") == args.project, "Том принадлежит другому проекту"
        existing = checked_docker("run", "--rm", "--network", "none", "-v", volume + ":/documents", image,
                                  "sh", "-c", "find /documents -mindepth 1 -print -quit")
        assert not existing.strip(), "Том хранилища не пуст; восстановление остановлено"
    for database in DATABASES:
        with (directory / f"{database}.dump").open("rb") as stream:
            checked_docker("exec", "-i", deployment.container("postgres"), "pg_restore", "-U", deployment.values.get("POSTGRES_USER", "cabinet"),
                           "-d", database, "--clean", "--if-exists", "--no-owner", "--no-acl", "--exit-on-error", stdin=stream)
    assert database_snapshot(deployment) == manifest["databases"], "Таблицы или последовательности после восстановления отличаются"
    report["checks"].append("Все таблицы, данные и последовательности трёх БД совпадают до запуска worker")
    for archive, (key, inventory) in archives.items():
        volume = storage_volume(deployment, key)
        checked_docker("run", "--rm", "--network", "none", "-v", volume + ":/documents", "-v", str(directory) + ":/backup:ro", image,
                       "sh", "-c", "tar -xzf /backup/" + archive + " -C /documents")
        output = checked_docker("run", "--rm", "--network", "none", "-v", volume + ":/documents:ro", image,
                                "sh", "-c", "cd /documents && find . -type f -exec sha256sum '{}' +").decode()
        restored = {name.removeprefix("./"): checksum for line in output.splitlines() for checksum, name in [line.split("  ", 1)]}
        assert restored == {name: value["sha256"] for name, value in inventory.items()}, "Содержимое восстановленного тома отличается"
    report["checks"].append("Проверены SHA256 всех файлов, безопасные пути и восстановлен том документов")
    command = [sys.executable, str(ROOT / "deploy/start.py"), "--project", args.project, "--env-file", str(deployment.path), "--no-build"]
    if args.local:
        command.append("--local")
    subprocess.run(command, check=True)
    report["passed"] = True
finally:
    output = ROOT / "deploy/reports"
    output.mkdir(parents=True, exist_ok=True)
    (output / "restore.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))
