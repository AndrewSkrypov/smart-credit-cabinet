#!/usr/bin/env python3
"""Verify the snapshot recovery strategy at every Flyway version on isolated PostgreSQL."""

import argparse
from contextlib import contextmanager
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from uuid import uuid4
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
SERVICES = ("identity", "credit", "engagement")
MIGRATIONS = "db/migration"
VERSION = re.compile(r"V([1-9][0-9]*)__.+\.sql")
HISTORY = """select version, script, checksum, success from flyway_schema_history order by installed_rank"""
DATA = r"""
select format(
    'select %L, to_jsonb(t)::text from only %I.%I as t order by to_jsonb(t)::text collate "C"',
    format('%I.%I', n.nspname, c.relname), n.nspname, c.relname)
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where c.relkind in ('r', 'p') and n.nspname !~ '^pg_' and n.nspname <> 'information_schema'
order by n.nspname, c.relname
\gexec
select format(
    'select %L, jsonb_build_array(last_value, is_called)::text from %I.%I',
    format('%I.%I', n.nspname, c.relname), n.nspname, c.relname)
from pg_class c join pg_namespace n on n.oid = c.relnamespace
where c.relkind = 'S' and n.nspname !~ '^pg_' and n.nspname <> 'information_schema'
order by n.nspname, c.relname
\gexec
"""
FIXTURE = "72000000-0000-4000-8000-000000000001"
SEEDS = {
    "identity": (1, f"""insert into companies(id, inn, name)
        values ('{FIXTURE}', '0000000000', 'Synthetic restore fixture')""",
                 f"select count(*) from companies where id='{FIXTURE}' and inn='0000000000'"),
    "credit": (2, f"""insert into applications(id, company_id, company_inn, legal_form, created_by,
        product_code, purpose, amount, term_months, status, created_at, updated_at, status_changed_at)
        values ('{FIXTURE}', '{FIXTURE}', '0000000000', 'OOO', '{FIXTURE}', 'WORKING_CAPITAL',
                'WORKING_CAPITAL', 1000000, 12, 'DRAFT', now(), now(), now())""",
               f"select count(*) from applications where id='{FIXTURE}' and amount=1000000 and status='DRAFT'"),
    "engagement": (1, f"""insert into notifications(id, recipient_id, type, title, body)
        values ('{FIXTURE}', '{FIXTURE}', 'APPLICATION_STATUS', 'Synthetic notice', 'Restore fixture')""",
                   f"select count(*) from notifications where id='{FIXTURE}' and recipient_id='{FIXTURE}'"),
}


def run(command, sql_input=None):
    result = subprocess.run(command, env={**os.environ, "PGCLIENTENCODING": "UTF8"}, input=sql_input, capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=180)
    if result.returncode:
        raise RuntimeError(f"{Path(command[0]).name} завершился с кодом {result.returncode}:\n{result.stderr[-1800:]}")
    return result.stdout


def executable(directory, name):
    return str(directory / (name + (".exe" if os.name == "nt" else "")))


def prepare_migrations(service, directory):
    source = ROOT / f"services/{service}/src/main/resources/{MIGRATIONS}"
    files = sorted(source.glob("*.sql"))
    assert files and all(VERSION.fullmatch(path.name) for path in files), service
    directory.mkdir()
    with ZipFile(ROOT / f"services/{service}/build/libs/app.jar") as jar:
        prefix = f"BOOT-INF/classes/{MIGRATIONS}/"
        packaged = {name.removeprefix(prefix): name for name in jar.namelist()
                    if name.startswith(prefix) and name.endswith(".sql")}
        assert packaged.keys() == {path.name for path in files}, f"{service}: JAR содержит другой набор миграций"
        for source_file in files:
            data = jar.read(packaged[source_file.name])
            assert data.decode("utf-8").replace("\r\n", "\n") == source_file.read_text(encoding="utf-8"), source_file
            (directory / source_file.name).write_bytes(data)
    versions = sorted(int(VERSION.fullmatch(path.name)[1]) for path in files)
    assert versions == sorted(set(versions)), f"{service}: повтор версии"
    return versions


def java_classpath(java_bin, temporary):
    dependencies = temporary / "dependencies"
    dependencies.mkdir()
    with ZipFile(ROOT / "services/engagement/build/libs/app.jar") as jar:
        for resource in jar.namelist():
            if resource.startswith("BOOT-INF/lib/") and resource.endswith(".jar"):
                (dependencies / Path(resource).name).write_bytes(jar.read(resource))
    jars = str(dependencies / "*")
    run([executable(java_bin, "javac"), "-cp", jars, "-d", str(temporary),
         str(ROOT / "db/rollback/MigrateRollbackFixture.java")])
    return os.pathsep.join((str(temporary), jars))


def rehearse(args, report, temporary):
    classpath = java_classpath(args.java_bin, temporary)
    java = executable(args.java_bin, "java")

    def pg(tool, database, *flags, sql_input=None):
        return run([executable(args.pg_bin, tool), "-h", "127.0.0.1", "-p", str(args.port),
                    "-U", args.user, "-w", "-d", database, *map(str, flags)], sql_input)

    def sql(database, query):
        return pg("psql", database, "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c", query).strip()

    @contextmanager
    def disposable_database():
        database = f"cabinet_rollback_{uuid4().hex[:20]}"
        sql("postgres", f'create database "{database}" template template0')
        try:
            yield database
        finally:
            sql("postgres", f'drop database "{database}"')

    def data_digest(database):
        rows = pg("psql", database, "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-f", "-", sql_input=DATA)
        return sha256(rows.encode("utf-8")).hexdigest()

    def schema_digest(database):
        dump = pg("pg_dump", database, "--no-owner", "--no-privileges", "--schema-only")
        lines = (line for line in dump.splitlines() if not line.startswith(("\\restrict ", "\\unrestrict ")))
        return sha256("\n".join(lines).encode("utf-8")).hexdigest()

    def canonical_schema(database):
        # Loading the DDL lets PostgreSQL normalize equivalent casts and expressions.
        archive = temporary / "schema.dump"
        pg("pg_dump", database, "--schema-only", "-Fc", "-f", archive)
        with disposable_database() as normalized:
            pg("pg_restore", normalized, "--exit-on-error", "--no-owner", "--no-privileges", archive)
            return schema_digest(normalized)

    def migrate(database, directory, version):
        run([java, "-Xmx128m", "-cp", classpath, "MigrateRollbackFixture", database, str(args.port),
             str(version), args.user, str(directory)])

    report["postgres_version"] = sql("postgres", "show server_version")
    for service in SERVICES:
        directory = temporary / service
        versions = prepare_migrations(service, directory)
        entries = report["services"][service] = []
        archive = temporary / "checkpoint.dump"
        seed_version, insert_fixture, check_fixture = SEEDS[service]
        with disposable_database() as upgraded:
            expected_versions = []
            for version in versions:
                report["pending"] = {"service": service, "version": version}
                checkpoint = data_digest(upgraded)
                checkpoint_schema = canonical_schema(upgraded)
                pg("pg_dump", upgraded, "-Fc", "-f", archive)
                migrate(upgraded, directory, version)
                expected_versions.append(str(version))
                history = sql(upgraded, HISTORY)
                assert [row.split("|", 1)[0] for row in history.splitlines()] == expected_versions
                with disposable_database() as restored:
                    pg("pg_restore", restored, "--exit-on-error", "--no-owner", "--no-privileges", archive)
                    assert data_digest(restored) == checkpoint, f"{service} V{version}: отличаются данные"
                    assert schema_digest(restored) == checkpoint_schema, f"{service} V{version}: отличается схема снимка"
                    migrate(restored, directory, version)
                    assert sql(restored, HISTORY) == history, f"{service} V{version}: отличается история Flyway"
                    applied_schema = canonical_schema(upgraded)
                    assert canonical_schema(restored) == applied_schema, f"{service} V{version}: отличается DDL"
                    if version > seed_version:
                        assert sql(upgraded, check_fixture) == sql(restored, check_fixture) == "1", service
                if version == seed_version:
                    sql(upgraded, insert_fixture)
                entries.append({"version": version, "restored_data_sha256": checkpoint, "restored_schema_sha256": checkpoint_schema,
                                "reapplied_schema_sha256": applied_schema,
                                "history_verified": True, "existing_fixture_verified": version > seed_version})
    report.pop("pending", None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pg-bin", type=Path, required=True)
    parser.add_argument("--java-bin", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535 or not os.environ.get("PGPASSWORD"):
        parser.error("Необходим порт локальной PostgreSQL и пароль тестового пользователя в PGPASSWORD")
    report = {"passed": False, "services": {}}
    try:
        with tempfile.TemporaryDirectory(prefix="cabinet-flyway-") as temporary:
            rehearse(args, report, Path(temporary))
        report["passed"] = True
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Проверено миграций: {sum(len(entries) for entries in report['services'].values())}")


if __name__ == "__main__":
    main()
