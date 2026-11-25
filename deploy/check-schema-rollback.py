#!/usr/bin/env python3
"""Rehearse comment migration, rollback, and reapply on copies of synthetic PostgreSQL databases."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]
VERSIONS = {"identity": 8, "credit": 17, "engagement": 5}
CATALOG = """
select coalesce(json_agg(row_to_json(results) order by name), '[]') from (
    select c.relname as name, obj_description(c.oid, 'pg_class') as table_comment,
        (select json_object_agg(a.attname, col_description(c.oid, a.attnum) order by a.attnum)
         from pg_attribute a where a.attrelid=c.oid and a.attnum>0 and not a.attisdropped) as columns
    from pg_class c join pg_namespace n on c.relnamespace=n.oid
    where n.nspname='public' and c.relkind in ('r','p') and c.relname<>'flyway_schema_history'
) as results
"""


def main():
    parser = argparse.ArgumentParser(description="Прогнать комментарии и откат в отдельных копиях синтетических БД")
    parser.add_argument("--pg-bin", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--source-prefix", default="final_native_")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"(?:final_native|cabinet_test|cabinet_demo)_[a-z0-9_]*", args.source_prefix):
        parser.error("Разрешены только имена локальных синтетических БД")

    def run(tool, *arguments, database):
        name = f"{tool}.exe" if os.name == "nt" else tool
        command = [str(args.pg_bin / name), "-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
        command.extend(["-d", database, *map(str, arguments)])
        result = subprocess.run(command, env={**os.environ, "PGCLIENTENCODING": "UTF8"}, text=True, encoding="utf-8",
                                capture_output=True, timeout=180)
        if result.returncode:
            raise RuntimeError(f"{tool} завершился с кодом {result.returncode}: {result.stderr.strip()[:800]}")
        return result.stdout

    def sql(database, statement):
        return run("psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c", statement, database=database)

    def apply(database, path):
        run("psql", "-X", "-q", "--single-transaction", "-v", "ON_ERROR_STOP=1", "-f", path, database=database)

    def data_hash(database):
        dump = run("pg_dump", "--data-only", "--no-owner", "--no-privileges", database=database)
        canonical = "\n".join(line for line in dump.splitlines() if not line.startswith(("--", "\\restrict", "\\unrestrict")))
        return hashlib.sha256(canonical.encode()).hexdigest()

    report = {"services": {}, "passed": False}
    try:
        for service, version in VERSIONS.items():
            source = args.source_prefix + service
            database = f"cabinet_rollback_{service}_{uuid4().hex[:14]}"
            forward = ROOT / "services" / service / f"src/main/resources/db/migration/V{version}__schema_comments.sql"
            reverse = ROOT / "db/rollback" / service / f"U{version}__schema_comments.sql"
            with tempfile.TemporaryDirectory() as temporary:
                archive = Path(temporary) / "before.dump"
                run("pg_dump", "-Fc", "-f", archive, database=source)
                sql("postgres", f'create database "{database}"')
                try:
                    run("pg_restore", "--exit-on-error", "--no-owner", "--no-privileges", archive, database=database)
                    data_before = data_hash(database)
                    applied = sql(database, f"select exists (select 1 from flyway_schema_history where version = '{version}' and success)").strip() == "t"
                    original_comments = json.loads(sql(database, CATALOG)) if applied else None
                    if applied:
                        apply(database, reverse)
                    before = json.loads(sql(database, CATALOG))
                    apply(database, forward)
                    after = json.loads(sql(database, CATALOG))
                    assert all(row["table_comment"] for row in after), f"{service}: есть таблица без комментария"
                    assert after != before and data_hash(database) == data_before, f"{service}: изменились данные или отсутствует результат"
                    if original_comments is not None:
                        assert after == original_comments, f"{service}: комментарии из Flyway отличаются от повторного наката"
                    apply(database, reverse)
                    assert json.loads(sql(database, CATALOG)) == before, f"{service}: откат комментариев неполный"
                    assert data_hash(database) == data_before, f"{service}: откат изменил данные"
                    apply(database, forward)
                    assert json.loads(sql(database, CATALOG)) == after and data_hash(database) == data_before
                    report["services"][service] = {"version": version, "tables": len(after), "data_sha256": data_before,
                                                   "commented_columns": sum(bool(value) for row in after for value in row["columns"].values())}
                finally:
                    sql("postgres", f'drop database "{database}"')
        report["passed"] = True
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
