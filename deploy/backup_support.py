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


def database_snapshot(deployment):
    snapshot = {}
    for database in DATABASES:
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
    """Accept legacy local v1 and explicit local/S3 v2; never accept arbitrary archive paths."""
    assert manifest["format_version"] in (1, 2) and manifest["backup_complete"], "Копия не завершена или формат неизвестен"
    archives = {"documents.tar.gz": ("cabinet-documents", manifest["documents"])}
    if manifest["format_version"] == 2:
        storage = manifest["storage"]
        assert isinstance(storage["s3_volume_included"], bool)
        assert set(storage["backends"]) <= {"LOCAL", "S3"}
        assert all(isinstance(n, int) and n >= 0 for n in storage["backends"].values())
        assert not storage["backends"].get("S3", 0) or storage["s3_volume_included"], "Нет копии S3 для документов S3"
        if storage["s3_volume_included"]:
            archives["seaweedfs.tar.gz"] = ("cabinet-seaweedfs", storage["seaweedfs_files"])
    expected = {db + ".dump" for db in DATABASES} | set(archives)
    assert set(manifest["files"]) == expected, "Неожиданный состав копии"
    return archives


def checked_docker(*arguments, **kwargs):
    result = subprocess.run(["docker", *arguments], capture_output=not any(key in kwargs for key in ["stdout", "stderr"]), **kwargs)
    if result.returncode:
        raise RuntimeError(f"Docker {arguments[0]} не завершён; код {result.returncode}")
    return result.stdout
