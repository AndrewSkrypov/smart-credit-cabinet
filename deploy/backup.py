#!/usr/bin/env python3
"""Back up the quiesced Credit stack and each enabled runtime."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import uuid
from catalog import fsync_dir
from catalog_rollout import validated_journal
from common import Deployment, ROOT, deploy_lock
from maintenance import Database
from runtime_backup import alpha_preflight, backup_private, runtime_modes
from runtime_delivery_support import write_record
from registry_release import SERVICE_COMPONENTS
from runtime_rollout import RUNTIME_WRITERS
from backup_support import (
    backup_needs_s3, checked_docker, database_names, database_snapshot, digest, document_inventory, document_volume,
    storage_backend_counts, storage_intent_backends, storage_volume,
)

parser = argparse.ArgumentParser(description="Согласованная копия БД и документов с короткой остановкой записи")
parser.add_argument("--project", default="cabinet-prod")
parser.add_argument("--env-file", default="deploy/.env")
parser.add_argument("--local", action="store_true")
parser.add_argument("--release-manifest")
parser.add_argument("--output")
parser.add_argument("--resume-existing", action="store_true", help=argparse.SUPPRESS)
args = parser.parse_args()
with deploy_lock(args.env_file, args.project):
    deployment = Deployment(args.project, args.env_file, args.local, args.release_manifest,
                            historical_backup=args.project == "cabinet-dev" and args.resume_existing)
    name = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    directory = Path(args.output).resolve() if args.output else ROOT / "deploy/backups" / name
    if args.project == "cabinet-dev" and deployment.values.get("CABINET_ALPHA_DEV_ENABLED") == "true" and not deployment.alpha_dev:
        raise RuntimeError("Защищённый backup требует manifest работающего поколения Analytics")
    modes = runtime_modes(deployment)
    directory.mkdir(parents=True, mode=0o700, exist_ok=False)
    fsync_dir(directory.parent)
    services = ["identity", "credit", "engagement", *(RUNTIME_WRITERS[name] for name in deployment.runtime_modes)]
    if deployment.alpha_dev:
        services.append("analytics")
    for service in [*services, "caddy", "postgres"]:
        deployment.container(service)
    if args.project == "cabinet-dev":
        for service in [*services, "external-sim", "frontend", "postgres"]:
            image_id = checked_docker("inspect", "--type", "container", "--format", "{{.Image}}",
                                      deployment.container(service)).decode("ascii").strip()
            if image_id != deployment.release.local_image_id(SERVICE_COMPONENTS[service]):
                raise RuntimeError(f"Backup требует фактический исходный образ {service}")
    manifest = {"format_version": 4 if modes else 2, "created_at": datetime.now(timezone.utc).isoformat(), "source_project": args.project,
                "release": deployment.values["CABINET_RELEASE"], "backup_complete": False, "source_resumed": False}
    if modes or args.project == "cabinet-dev":
        manifest["release_manifest_sha256"] = deployment.release.checksum
    if modes:
        manifest["runtimes"] = modes
    stopped = False
    seaweed_running = deployment.compose("ps", "-q", "seaweedfs", capture=True).split()
    seaweed_volume = storage_volume(deployment, "cabinet-seaweedfs")
    volumes = checked_docker("volume", "ls", "--format", "{{.Name}}").decode().splitlines()
    include_seaweed = seaweed_volume in volumes
    seaweed_stopped = False
    maintenance = Database(deployment.container("postgres"), deployment.values.get("POSTGRES_USER", "cabinet"))
    try:
        assert maintenance.sql("select pg_try_advisory_lock(16781, 8801);") == "t", "Перенос или другая копия уже выполняется"
        if deployment.documents:
            backends = set(storage_backend_counts(deployment)) | storage_intent_backends(deployment)
            deployment.documents.verify_source_store(deployment, backends)
        preflight = alpha_preflight(deployment) if deployment.alpha_dev else None
        if preflight:
            write_record(directory / "alpha-preflight.json", preflight, immutable=True)
        stopped = True
        deployment.compose("stop", "caddy")
        deployment.compose("stop", "-t", "150", *services)
        if modes:
            backup_private(deployment, directory, manifest, preflight)
        if seaweed_running:
            seaweed_stopped = True
            checked_docker("stop", *seaweed_running)
        backends = storage_backend_counts(deployment)
        requested_backends = storage_intent_backends(deployment)
        credit_environment = deployment.config()["services"]["credit"]["environment"]
        manifest["storage"] = {"backends": backends, "s3_volume_included": include_seaweed,
                               "s3_bucket": credit_environment.get("S3_BUCKET") if include_seaweed else None}
        if requested_backends:
            manifest["storage"]["requested_backends"] = sorted(requested_backends)
        if backup_needs_s3(manifest):
            if not include_seaweed:
                raise RuntimeError("Нет локального тома S3 для резервной копии файлов или заданий Documents")
            if credit_environment["S3_ENDPOINT"] != "http://seaweedfs:8333":
                raise RuntimeError("Резервная копия требует собственного SeaweedFS для файлов или заданий Documents")
        manifest["postgres_version"] = deployment.sql("credit", "show server_version")
        databases = database_names(manifest)
        manifest["databases"] = database_snapshot(deployment, databases)
        if deployment.catalog_enabled:
            journal = validated_journal(deployment.catalog_state / "authority.json")
            writer = "catalog" if deployment.catalog_mode == "remote" else "credit"
            assert journal.get("active") == writer and not journal.get("pending"), "Нужна завершённая смена владельца Catalog"
            assert all(manifest["databases"][db]["catalog_writable"] == ("t" if db == writer else "f")
                       for db in ("catalog", "credit")), "Резерв не подтвердил единственного писателя Catalog"
            manifest["catalog"] = {"mode": deployment.catalog_mode, "release_manifest_sha256": deployment.release.checksum,
                                   "authority": journal}
        manifest["images"] = {}
        for service in [*services, "external-sim", "frontend", "postgres"]:
            identifiers = deployment.compose("ps", "-a", "-q", service, capture=True).split()
            info = json.loads(subprocess.check_output(["docker", "inspect", identifiers[0]], text=True))[0]
            manifest["images"][service] = info["Image"]
        for database in databases:
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
        manifest["files"] = {}
        for path in directory.iterdir():
            if path.is_file():
                with path.open("rb") as stream:
                    os.fsync(stream.fileno())
                manifest["files"][path.name] = {"bytes": path.stat().st_size, "sha256": digest(path)}
        manifest["backup_complete"] = True
    finally:
        try:
            if seaweed_stopped:
                checked_docker("start", *seaweed_running)
            if stopped:
                resume_options = ["--no-recreate"] if args.resume_existing or modes else []
                deployment.compose("up", "-d", "--no-deps", "--no-build", "--wait", "--wait-timeout", "180",
                                   *resume_options, *services)
                deployment.compose("start", "caddy")
            manifest["source_resumed"] = True
        finally:
            try:
                descriptor = os.open(directory / "manifest.json", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                    json.dump(manifest, stream, ensure_ascii=False, indent=2)
                    stream.flush()
                    os.fsync(stream.fileno())
                fsync_dir(directory)
                print(json.dumps({"backup": str(directory), "backup_complete": manifest["backup_complete"],
                                  "source_resumed": manifest["source_resumed"]}, ensure_ascii=False))
            finally:
                maintenance.close()
