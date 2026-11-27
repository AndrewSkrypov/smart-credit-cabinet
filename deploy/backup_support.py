"""Integrity checks shared by the quiesced backup and fresh-target restore tools."""
import hashlib
import json
from pathlib import PurePosixPath
import re
import subprocess
import tarfile

DATABASES = ("identity", "credit", "engagement")


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for part in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(part)
    return value.hexdigest()


def database_names(manifest):
    return (*DATABASES, "catalog") if manifest["format_version"] == 3 or "catalog" in manifest.get("runtimes", {}) else DATABASES


def database_snapshot(deployment, databases=DATABASES):
    snapshot = {}
    for database in databases:
        tables = deployment.sql(database, "select tablename from pg_tables where schemaname='public' order by tablename").splitlines()
        values = {}
        for table in tables:
            assert re.fullmatch(r"[a-z_][a-z0-9_]*", table), "Неподдерживаемое имя таблицы"
            rows = deployment.sql(database, f"set timezone='UTC'; select row_to_json(t)::text from public.{table} t order by row_to_json(t)::text")
            values[table] = {"rows": len(rows.splitlines()) if rows else 0, "sha256": hashlib.sha256(rows.encode()).hexdigest()}
        sequences = {}
        for sequence in deployment.sql(database, "select sequencename from pg_sequences where schemaname='public' order by sequencename").splitlines():
            assert re.fullmatch(r"[a-z_][a-z0-9_]*", sequence)
            sequences[sequence] = deployment.sql(database, f"select last_value, is_called from public.{sequence}")
        snapshot[database] = {"tables": values, "sequences": sequences}
    if "catalog" in databases:
        for database in ("credit", "catalog"):
            snapshot[database]["catalog_writable"] = deployment.sql(
                database, "select writable from catalog_cutover.authority where singleton",
            )
    return snapshot


def document_inventory(archive):
    values = {}
    with tarfile.open(archive, "r:gz") as stream:
        for member in stream:
            path = PurePosixPath(member.name)
            assert not path.is_absolute() and ".." not in path.parts, "Небезопасный путь в архиве документов"
            assert not any(ord(char) < 32 for char in member.name), "Управляющие символы в пути документа"
            assert member.isdir() or member.isfile(), "В архиве документов допустимы только обычные файлы и каталоги"
            if member.isfile():
                value = hashlib.sha256()
                with stream.extractfile(member) as content:
                    for part in iter(lambda: content.read(1024 * 1024), b""):
                        value.update(part)
                assert str(path) not in values, "Повторяющийся путь в архиве документов"
                values[str(path)] = {"bytes": member.size, "sha256": value.hexdigest()}
    return values


def document_volume(deployment):
    return storage_volume(deployment, "cabinet-documents")


def storage_backend_counts(deployment):
    """Include generated files, even when there are no uploaded client documents in S3."""
    has_backend = deployment.sql("credit", "select count(*) from information_schema.columns where table_schema='public' and table_name='documents' and column_name='storage_backend'") == "1"
    sources = ["select storage_backend from documents" if has_backend else "select 'LOCAL' as storage_backend from documents"]
    has_artifacts = deployment.sql("credit", "select count(*) from information_schema.tables where table_schema='public' and table_name='generated_artifacts'") == "1"
    if has_artifacts:
        sources.append("select storage_backend from generated_artifacts")
    return json.loads(deployment.sql("credit", "select coalesce(json_object_agg(storage_backend,n),'{}') from (select storage_backend,count(*) n from (" + " union all ".join(sources) + ") files group by storage_backend) t"))


def storage_volume(deployment, key):
    assert key in {"cabinet-documents", "cabinet-seaweedfs"}
    config = deployment.config()
    if key not in config.get("volumes", {}):
        # Compose omits resources belonging only to an inactive profile. Render it without starting anything.
        config = json.loads(deployment.compose("--profile", "storage", "config", "--format", "json", capture=True))
    name = config["volumes"][key]["name"]
    assert name.startswith(deployment.project + "_"), "Нужен собственный том документов выбранного проекта"
    return name


def backup_archives(manifest):
    """Validate the exact archive set, including Catalog when enabled."""
    assert manifest["format_version"] in (1, 2, 3, 4) and manifest["backup_complete"], "Копия не завершена или формат неизвестен"
    runtime_fields = {"runtimes", "assessment_runtime", "servicing_runtime", "documents_runtime", "alpha_runtime"}
    if manifest["format_version"] != 4 and runtime_fields.intersection(manifest):
        raise ValueError("Legacy резерв не поддерживает дополнительные runtime")
    archives = {"documents.tar.gz": ("cabinet-documents", manifest["documents"])}
    if manifest["format_version"] >= 2:
        storage = manifest["storage"]
        assert isinstance(storage["s3_volume_included"], bool)
        assert set(storage["backends"]) <= {"LOCAL", "S3"}
        assert all(isinstance(n, int) and n >= 0 for n in storage["backends"].values())
        assert not storage["backends"].get("S3", 0) or storage["s3_volume_included"], "Нет копии S3 для документов S3"
        if storage["s3_volume_included"]:
            archives["seaweedfs.tar.gz"] = ("cabinet-seaweedfs", storage["seaweedfs_files"])
    databases = database_names(manifest)
    if "catalog" in databases:
        catalog = manifest["catalog"]
        assert catalog["mode"] in {"local", "remote"} and re.fullmatch(r"[0-9a-f]{64}", catalog["release_manifest_sha256"])
        writer = "catalog" if catalog["mode"] == "remote" else "credit"
        assert catalog["authority"]["active"] == writer and not catalog["authority"].get("pending")
        assert manifest["databases"]["credit"]["catalog_writable"] == ("t" if writer == "credit" else "f")
        assert manifest["databases"]["catalog"]["catalog_writable"] == ("t" if writer == "catalog" else "f")
    assert set(manifest["databases"]) == set(databases)
    expected = {db + ".dump" for db in databases} | set(archives)
    if manifest["format_version"] == 4:
        expected.update(runtime_backup_files(manifest))
    assert set(manifest["files"]) == expected, "Неожиданный состав копии"
    return archives


def runtime_backup_files(manifest):
    runtimes = manifest.get("runtimes")
    permitted = {"catalog": {"local", "remote"}, "assessment": {"local", "remote"},
                 "servicing": {"local", "remote"}, "documents": {"local", "remote"},
                 "alpha_dev": {"shadow", "read"}}
    if (not isinstance(runtimes, dict) or not runtimes
            or any(name not in permitted or mode not in permitted[name] for name, mode in runtimes.items())
            or not re.fullmatch(r"[0-9a-f]{64}", str(manifest.get("release_manifest_sha256")))):
        raise ValueError("Некорректный состав runtime в резерве")
    metadata_fields = {"alpha_dev": "alpha_runtime", "catalog": "catalog", "assessment": "assessment_runtime",
                       "servicing": "servicing_runtime", "documents": "documents_runtime"}
    if any((name in runtimes) != (field in manifest) for name, field in metadata_fields.items()):
        raise ValueError("Метаданные резерва не совпадают с составом runtime")
    files = set()
    if "catalog" in runtimes:
        if manifest.get("catalog", {}).get("mode") != runtimes["catalog"]:
            raise ValueError("Режим Catalog изменился")
    if "assessment" in runtimes:
        if not isinstance(manifest.get("assessment_runtime"), dict):
            raise ValueError("Отсутствует checkpoint Assessment")
        files.update(("assessment.dump", "assessment-kafka.tar.gz", "assessment-state.tar.gz"))
    if "servicing" in runtimes:
        if manifest.get("servicing_runtime", {}).get("mode") != runtimes["servicing"]:
            raise ValueError("Не подтверждён состав копии Servicing")
        files.update(("servicing.dump", "servicing-state.tar.gz"))
    if "documents" in runtimes:
        documents = manifest.get("documents_runtime", {})
        names = {"documents-runtime.dump", "documents-runtime-local.tar.gz", "documents-runtime-state.tar.gz"}
        if documents.get("identity", {}).get("has_s3") is True:
            names.add("documents-runtime-s3.tar.gz")
        if documents.get("current", {}).get("mode") != runtimes["documents"] or set(documents.get("files", {})) != names:
            raise ValueError("Не подтверждён состав копии Documents")
        files.update(names)
    if "alpha_dev" in runtimes:
        if not isinstance(manifest.get("alpha_runtime"), dict):
            raise ValueError("Не подтверждён состав копии Analytics")
        files.update(("alpha-preflight.json", "alpha-runtime.env", "alpha-runtime-db.tar.gz", "alpha-runtime-kafka.tar.gz"))
    return files


def checked_docker(*arguments, **kwargs):
    result = subprocess.run(["docker", *arguments], capture_output=not any(key in kwargs for key in ["stdout", "stderr"]), **kwargs)
    if result.returncode:
        raise RuntimeError(f"Docker {arguments[0]} не завершён; код {result.returncode}")
    return result.stdout
