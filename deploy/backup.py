#!/usr/bin/env python3
"""Back up three quiesced databases together with their document volume."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import uuid
from common import Deployment, ROOT
from maintenance import Database
from backup_support import DATABASES, checked_docker, database_snapshot, digest, document_inventory, document_volume, storage_volume

parser = argparse.ArgumentParser(description="Согласованная копия БД и документов с короткой остановкой записи")
parser.add_argument("--project", default="cabinet-prod")
parser.add_argument("--env-file", default="deploy/.env")
parser.add_argument("--local", action="store_true")
parser.add_argument("--output")
args = parser.parse_args()
deployment = Deployment(args.project, args.env_file, args.local)
name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
directory = Path(args.output).resolve() if args.output else ROOT / "deploy/backups" / name
directory.mkdir(parents=True, mode=0o700, exist_ok=False)
services = ["identity", "credit", "engagement"]
for service in [*services, "caddy", "postgres"]:
    deployment.container(service)
manifest = {"format_version": 2, "created_at": datetime.now(timezone.utc).isoformat(), "source_project": args.project,
            "release": deployment.values["CABINET_RELEASE"], "backup_complete": False, "source_resumed": False}
stopped = False
seaweed_running = deployment.compose("ps", "-q", "seaweedfs", capture=True).split()
seaweed_volume = storage_volume(deployment, "cabinet-seaweedfs")
volumes = checked_docker("volume", "ls", "--format", "{{.Name}}").decode().splitlines()
include_seaweed = seaweed_volume in volumes
seaweed_stopped = False
maintenance = Database(deployment.container("postgres"), deployment.values.get("POSTGRES_USER", "cabinet"))
try:
    assert maintenance.sql("select pg_try_advisory_lock(16781, 8801);") == "t", "Перенос или другая копия уже выполняется"
    stopped = True
    deployment.compose("stop", "caddy", *services)
    if seaweed_running:
        seaweed_stopped = True
        checked_docker("stop", *seaweed_running)
    has_backend = deployment.sql("credit", "select count(*) from information_schema.columns where table_schema='public' and table_name='documents' and column_name='storage_backend'") == "1"
    backends = json.loads(deployment.sql("credit", "select coalesce(json_object_agg(storage_backend,n),'{}') from (select storage_backend,count(*) n from documents group by storage_backend) t")) if has_backend else {"LOCAL": int(deployment.sql("credit", "select count(*) from documents"))}
    assert not backends.get("S3", 0) or include_seaweed, "Нет локального тома S3; резервная копия документов неполна"
    credit_environment = deployment.config()["services"]["credit"]["environment"]
    if backends.get("S3", 0):
        assert credit_environment["S3_ENDPOINT"] == "http://seaweedfs:8333", "Backup поддерживает только собственный SeaweedFS; внешний S3 требует отдельной копии"
    manifest["storage"] = {"backends": backends, "s3_volume_included": include_seaweed,
                           "s3_bucket": credit_environment.get("S3_BUCKET") if include_seaweed else None}
    manifest["postgres_version"] = deployment.sql("credit", "show server_version")
    manifest["databases"] = database_snapshot(deployment)
    manifest["images"] = {}
    for service in [*services, "external-sim", "frontend", "postgres"]:
        identifiers = deployment.compose("ps", "-a", "-q", service, capture=True).split()
        info = json.loads(subprocess.check_output(["docker", "inspect", identifiers[0]], text=True))[0]
        manifest["images"][service] = info["Image"]
    for database in DATABASES:
        path = directory / f"{database}.dump"
        descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            checked_docker("exec", deployment.container("postgres"), "pg_dump", "-U", deployment.values.get("POSTGRES_USER", "cabinet"),
                           "-d", database, "-Fc", "--no-owner", "--no-acl", stdout=stream, stderr=subprocess.DEVNULL)
    descriptor = os.open(directory / "documents.tar.gz", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        # The invoking user must own the private archive even when Docker runs its helper as root.
        checked_docker("run", "--rm", "--network", "none", "-v", document_volume(deployment) + ":/documents:ro",
                       deployment.config()["services"]["postgres"]["image"],
                       "sh", "-c", "tar -czf - -C /documents .", stdout=stream, stderr=subprocess.DEVNULL)
    manifest["documents"] = document_inventory(directory / "documents.tar.gz")
    if include_seaweed:
        descriptor = os.open(directory / "seaweedfs.tar.gz", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            checked_docker("run", "--rm", "--network", "none", "-v", seaweed_volume + ":/storage:ro",
                           deployment.config()["services"]["postgres"]["image"],
                           "sh", "-c", "tar -czf - -C /storage .", stdout=stream, stderr=subprocess.DEVNULL)
        manifest["storage"]["seaweedfs_files"] = document_inventory(directory / "seaweedfs.tar.gz")
    manifest["files"] = {path.name: {"bytes": path.stat().st_size, "sha256": digest(path)} for path in directory.iterdir() if path.is_file()}
    manifest["backup_complete"] = True
finally:
    try:
        if seaweed_stopped:
            checked_docker("start", *seaweed_running)
        if stopped:
            deployment.compose("up", "-d", "--no-deps", "--no-build", "--wait", "--wait-timeout", "180", *services)
            deployment.compose("start", "caddy")
        manifest["source_resumed"] = True
    finally:
        try:
            descriptor = os.open(directory / "manifest.json", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                json.dump(manifest, stream, ensure_ascii=False, indent=2)
            print(json.dumps({"backup": str(directory), "backup_complete": manifest["backup_complete"],
                              "source_resumed": manifest["source_resumed"]}, ensure_ascii=False))
        finally:
            maintenance.close()
