#!/usr/bin/env python3
"""Restore a verified backup into an empty, separately named project."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import stat
import subprocess
import sys
from tempfile import TemporaryDirectory
from catalog import SECRET_KEYS, append_secrets, ensure_private, ensure_tls, requested_mode
from catalog_rollout import Operator, store
from common import Deployment, ROOT, deploy_lock, lock_environment, replace_env
from registry_release import RegistryRelease
from runtime_backup import alpha_volumes, restore_private, runtime_modes, stage_private, verify_alpha_catchup
from runtime_rollout import runtime_services
from alpha_dev import SECRET_KEYS as ALPHA_SECRETS
from backup_support import backup_archives, backup_needs_s3, checked_docker, database_names, database_snapshot, digest, document_inventory, storage_volume


def require_restore_s3(deployment, archive):
    if "storage" not in deployment.profiles:
        raise RuntimeError("Для восстановления S3 включите storage")
    credit_environment = deployment.config()["services"]["credit"]["environment"]
    if credit_environment["S3_ENDPOINT"] != "http://seaweedfs:8333":
        raise RuntimeError("Восстановление тома S3 требует собственного SeaweedFS")
    if credit_environment["S3_BUCKET"] != archive["storage"]["s3_bucket"]:
        raise RuntimeError("S3_BUCKET должен совпасть с бакетом копии")

parser = argparse.ArgumentParser(description="Восстановление только в новый пустой cabinet-prod-restore* проект")
parser.add_argument("backup")
parser.add_argument("--project", default="cabinet-prod-restore")
parser.add_argument("--env-file", required=True)
parser.add_argument("--local", action="store_true")
parser.add_argument("--release-manifest")
parser.add_argument("--host-memory-gib", type=int, default=8)
args = parser.parse_args()
if args.host_memory_gib < 4:
    parser.error("Укажите объём RAM сервера в GiB, минимум 4")
with deploy_lock(args.env_file, args.project) as lock_fd:
    directory = Path(args.backup).resolve()
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    archives = backup_archives(manifest)
    unified = manifest["format_version"] == 4
    if unified:
        valid_project = (not args.local and args.project.startswith("cabinet-dev-restore-")
                         and bool(args.project.removeprefix("cabinet-dev-restore-")))
    else:
        valid_project = args.project.startswith("cabinet-prod-restore")
    if not valid_project or args.project == manifest["source_project"]:
        raise ValueError("Восстановление разрешено только в новый изолированный проект")
    databases = database_names(manifest)
    catalog = manifest.get("catalog") if "catalog" in databases else None
    for name, expected in manifest["files"].items():
        path = directory / name
        assert path.is_file() and not path.is_symlink() and path.stat().st_size == expected["bytes"] and digest(path) == expected["sha256"], "Нарушена целостность копии: " + name
    if catalog or unified:
        assert args.release_manifest, "Для копии runtime нужен исходный release manifest"
        release = RegistryRelease(args.release_manifest)
        expected = (manifest["release_manifest_sha256"] if unified else catalog["release_manifest_sha256"])
        assert release.checksum == expected and release.revision == manifest["release"], "Нужен точный manifest исходного выпуска"
        env_file = Path(args.env_file).absolute()
        if unified and (env_file.parent / "current.json").exists():
            raise RuntimeError("Для восстановления нужен отдельный каталог ENV и состояния")
        env_stat = env_file.lstat()
        assert stat.S_ISREG(env_stat.st_mode) and stat.S_IMODE(env_stat.st_mode) == 0o600, "Нужен отдельный env 0600"
        env_contents = env_file.read_bytes()
        initialized_env = env_contents
        present = {line.split(b"=", 1)[0] for line in env_contents.splitlines() if b"=" in line}
        if catalog:
            assert requested_mode(args.project, args.local, release) == catalog["mode"]
            if not present.intersection(key.encode("ascii") for key in SECRET_KEYS):
                initialized_env = append_secrets(initialized_env)
        if unified:
            expected_modes = {name: item["dev_mode"] for name in ("catalog", "assessment", "servicing", "documents")
                              if (item := getattr(release, name)) is not None and item["dev_mode"] != "disabled"}
            assert expected_modes == {name: mode for name, mode in manifest["runtimes"].items() if name != "alpha_dev"}
            if "alpha_dev" in manifest["runtimes"]:
                keys = {key.encode("ascii") for key in (*ALPHA_SECRETS, "CABINET_ALPHA_DEV_GENERATION",
                                                        "CABINET_ALPHA_DEV_READ_ENABLED", "CABINET_ALPHA_DEV_ENABLED")}
                source = (directory / "alpha-runtime.env").read_bytes()
                source_keys = [line.split(b"=", 1)[0] for line in source.splitlines() if b"=" in line]
                assert set(source_keys) == keys and len(source_keys) == len(keys) and not keys.intersection(present)
                initialized_env = initialized_env.rstrip(b"\r\n") + b"\n" + source
            if backup_needs_s3(manifest):
                preview = Deployment(args.project, env_file, args.local, args.release_manifest,
                                     env_contents=initialized_env, open_hooks=False)
                require_restore_s3(preview, manifest)
            stage_private(args.project, env_file, directory, manifest)
        deployment = Deployment(args.project, env_file, args.local, args.release_manifest, env_contents=initialized_env)
        if unified and runtime_modes(deployment) != manifest["runtimes"]:
            raise RuntimeError("Состав восстанавливаемых runtime не совпадает с резервом")
    else:
        deployment = Deployment(args.project, args.env_file, args.local, args.release_manifest)
        assert not deployment.runtime_modes, "Этот архив был создан до ввода runtime"
    assert deployment.values["CABINET_RELEASE"] == manifest["release"], "Сначала выберите тот же выпуск приложений, что и в копии"
    for archive, (_, inventory) in archives.items():
        assert document_inventory(directory / archive) == inventory, "Архив хранилища не совпадает с manifest"
    if not unified and backup_needs_s3(manifest):
        require_restore_s3(deployment, manifest)
    report = {"checked_at": datetime.now(timezone.utc).isoformat(), "source_project": manifest["source_project"], "target_project": args.project,
              "passed": False, "checks": []}
    command = [sys.executable, str(ROOT / "deploy/start.py"), "--project", args.project, "--env-file", str(deployment.path),
               "--no-build", "--host-memory-gib", str(args.host_memory_gib)]
    if args.local:
        command.append("--local")
    if deployment.release:
        command.extend(["--release-manifest", str(deployment.release.path)])
    try:
        for service in ["identity", "credit", "engagement", "caddy", "seaweedfs", *runtime_services(deployment),
                        *(["analytics", "analytics-db", "analytics-kafka"] if deployment.alpha_dev else [])]:
            assert not deployment.compose("ps", "-a", "-q", service, capture=True).strip(), "Целевой проект уже содержит приложения; выберите новый"
        if (catalog or unified) and initialized_env != env_contents:
            replace_env(env_file, env_stat, initialized_env)
        if catalog:
            assert not (deployment.catalog_state / "authority.json").exists(), "Целевой журнал уже содержит данные"
            ensure_tls(deployment.catalog_state)
        if unified:
            subprocess.run([*command, "--check-only"], check=True, **lock_environment(lock_fd))
        if deployment.release:
            deployment.release.pull_and_verify(deployment.active_services())
        build_option = "--no-build" if deployment.release else "--build"
        deployment.compose("up", "-d", build_option, "--wait", "--wait-timeout", "180", "postgres")
        deployment.compose("run", "--rm", "--no-deps", "db-init")
        if catalog:
            with TemporaryDirectory(prefix=".restore-", dir=ensure_private(deployment.catalog_state)) as work:
                Operator(deployment, Path(work)).create_database(deployment.values)
        assert deployment.sql("credit", "show server_version").split(".")[0] == manifest["postgres_version"].split(".")[0], "Нужна та же основная версия PostgreSQL"
        for database in databases:
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
        for database in databases:
            with (directory / f"{database}.dump").open("rb") as stream:
                owner = ["--role=" + database + "_owner"] if catalog and database in {"catalog", "credit"} else []
                checked_docker("exec", "-i", deployment.container("postgres"), "pg_restore", "-U", deployment.values.get("POSTGRES_USER", "cabinet"),
                               "-d", database, "--clean", "--if-exists", "--no-owner", "--no-acl", "--exit-on-error", *owner, stdin=stream)
        # Dumps deliberately omit roles/ACLs/passwords. Adopt restored objects to their schema
        # owner and restore the current least-privilege grants before any HTTP process starts.
        deployment.compose("run", "--rm", "--no-deps", "db-init")
        if catalog:
            with TemporaryDirectory(prefix=".restore-", dir=deployment.catalog_state) as work:
                operator = Operator(deployment, Path(work))
                operator.restore_permissions()
                operator.validate_roles_and_fences()
                operator.assert_inactive_writer("credit" if catalog["mode"] == "remote" else "catalog")
        assert database_snapshot(deployment, databases) == manifest["databases"], "Таблицы или последовательности после восстановления отличаются"
        report["checks"].append("Данные, последовательности и записанные состояния Catalog совпадают до запуска worker")
        for archive, (key, inventory) in archives.items():
            volume = storage_volume(deployment, key)
            checked_docker("run", "--rm", "--network", "none", "-v", volume + ":/documents", "-v", str(directory) + ":/backup:ro", image,
                           "sh", "-c", "tar -xzf /backup/" + archive + " -C /documents")
            output = checked_docker("run", "--rm", "--network", "none", "-v", volume + ":/documents:ro", image,
                                    "sh", "-c", "cd /documents && find . -type f -exec sha256sum '{}' +").decode()
            restored = {name.removeprefix("./"): checksum for line in output.splitlines() for checksum, name in [line.split("  ", 1)]}
            assert restored == {name: value["sha256"] for name, value in inventory.items()}, "Содержимое восстановленного тома отличается"
        report["checks"].append("Проверены SHA256 всех файлов, безопасные пути и восстановлен том документов")
        if catalog:
            store(deployment.catalog_state / "authority.json", catalog["authority"])
        if unified:
            restore_private(deployment, directory, manifest)
        subprocess.run(command, check=True, **lock_environment(lock_fd))
        if unified and deployment.alpha_dev:
            proof = verify_alpha_catchup(deployment, {
                "volumes": alpha_volumes(deployment), "checkpoint": manifest["alpha_runtime"]["frozen"]["checkpoint"],
            })
            report["alpha_projection_sha256"] = proof["projection_sha256"]
            report["checks"].append("Alpha догнала восстановленный Credit на сохранённом поколении Kafka")
        report["passed"] = True
    finally:
        output = ROOT / "deploy/reports"
        output.mkdir(parents=True, exist_ok=True)
        (output / "restore.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
