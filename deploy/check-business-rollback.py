#!/usr/bin/env python3
"""Rehearse credit business migration rollback (U18..U13) and reapply (V13..V18) on an isolated temp DB."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS_DIR = ROOT / "services/credit/src/main/resources/db/migration"
ROLLBACK_DIR = ROOT / "db/rollback/credit"
CREDIT_ROLLBACK_STEPS = (
    (13, "product_lifecycle"),
    (14, "agent_channel_and_preapproved"),
    (15, "loan_servicing_and_tranches"),
    (16, "outbox_trace_parent"),
    (17, "schema_comments"),
    (18, "loan_servicing_schema_comments"),
)
SCHEMA_QUERY = """
select coalesce(json_agg(row_to_json(cols) order by table_name, column_name), '[]') from (
    select table_name, column_name, data_type, is_nullable
    from information_schema.columns
    where table_schema = 'public' and table_name <> 'flyway_schema_history'
) as cols
"""
REMOVED_TABLES = {"loan_accounts", "loan_tranches", "loan_payments", "pre_approved_offers"}
REMOVED_COLUMNS = {
    ("delivery_outbox", "trace_parent"),
    ("product_versions", "lifecycle_status"),
    ("product_versions", "agent_commission_rate_bps"),
    ("applications", "agent_id"),
    ("applications", "pre_approved_offer_id"),
}


def version_key(path: Path) -> int:
    match = re.match(r"V(\d+)__", path.name)
    if not match:
        raise ValueError(f"Неожиданное имя миграции: {path.name}")
    return int(match.group(1))


def main():
    parser = argparse.ArgumentParser(description="Прогнать откат бизнес-миграций credit (U18..U13) и повторный накат (V13..V18)")
    parser.add_argument("--pg-bin", type=Path, required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    def run_psql(*arguments, database):
        name = "psql.exe" if os.name == "nt" else "psql"
        command = [str(args.pg_bin / name), "-h", "127.0.0.1", "-p", str(args.port), "-U", args.user]
        command.extend(["-d", database, *map(str, arguments)])
        result = subprocess.run(
            command,
            env={**os.environ, "PGCLIENTENCODING": "UTF8"},
            text=True,
            encoding="utf-8",
            capture_output=True,
            timeout=180,
        )
        if result.returncode:
            raise RuntimeError(f"psql завершился с кодом {result.returncode}: {result.stderr.strip()[:800]}")
        return result.stdout

    def sql(database, statement):
        return run_psql("-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c", statement, database=database)

    def apply(database, path):
        run_psql("-X", "-q", "--single-transaction", "-v", "ON_ERROR_STOP=1", "-f", path, database=database)

    all_forward = sorted(MIGRATIONS_DIR.glob("V*__*.sql"), key=version_key)
    if not all_forward:
        raise FileNotFoundError(f"Миграции не найдены в {MIGRATIONS_DIR}")

    scripts = [
        (
            version,
            MIGRATIONS_DIR / f"V{version}__{description}.sql",
            ROLLBACK_DIR / f"U{version}__{description}.sql",
        )
        for version, description in CREDIT_ROLLBACK_STEPS
    ]
    for version, forward, reverse in scripts:
        if not forward.is_file():
            raise FileNotFoundError(f"Отсутствует миграция V{version}: {forward}")
        if not reverse.is_file():
            raise FileNotFoundError(f"Отсутствует скрипт отката U{version}: {reverse}")

    database = f"cabinet_biz_rollback_{uuid4().hex[:14]}"
    report = {"versions": [v for v, _, _ in scripts], "passed": False}
    try:
        sql("postgres", f'create database "{database}"')
        try:
            # Later versions depend on these tables (reserves, servicing fence, receipts) and are
            # rolled back by snapshot restore, so the rehearsal builds the schema up to V18 only.
            last_version = max(version for version, _, _ in scripts)
            for migration in all_forward:
                if version_key(migration) <= last_version:
                    apply(database, migration)
            schema_before = json.loads(sql(database, SCHEMA_QUERY))
            for _, _, reverse in reversed(scripts):
                apply(database, reverse)
            schema_rolled_back = json.loads(sql(database, SCHEMA_QUERY))
            remaining_tables = {row["table_name"] for row in schema_rolled_back}
            remaining_columns = {(row["table_name"], row["column_name"]) for row in schema_rolled_back}
            assert not (REMOVED_TABLES & remaining_tables), f"Не удалены таблицы: {REMOVED_TABLES & remaining_tables}"
            assert not (REMOVED_COLUMNS & remaining_columns), f"Не удалены колонки: {REMOVED_COLUMNS & remaining_columns}"
            for _, forward, _ in scripts:
                apply(database, forward)
            schema_after = json.loads(sql(database, SCHEMA_QUERY))
            assert schema_after == schema_before, "Схема после цикла U18..U13 -> V13..V18 отличается от исходной"
            report["columns_before"] = len(schema_before)
            report["columns_after_rollback"] = len(schema_rolled_back)
            report["columns_after_reapply"] = len(schema_after)
            report["passed"] = True
        finally:
            sql("postgres", f'drop database "{database}"')
    finally:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
