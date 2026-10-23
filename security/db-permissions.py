#!/usr/bin/env python3
"""Exercise real service credentials over TCP; keep secrets and PG globals out of reports."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
DATABASES = ("identity", "credit", "engagement")
AUDIT = {
    "identity": ["personal_data_consents", "identity_audit"],
    "credit": ["application_events", "product_versions", "application_messages", "message_attachments",
               "application_chat_audit", "application_chat_upload_keys"],
    "engagement": ["notification_receipts"],
}


def main():
    parser = argparse.ArgumentParser(description="Проверить права в отдельном синтетическом проекте")
    parser.add_argument("--project", required=True)
    parser.add_argument("--report-name", default="db-permissions")
    args = parser.parse_args()
    if not re.fullmatch(r"cabinet-(?:(?:submission|reliability|e2e|release)(?:-[a-z0-9]+)?|prod-(?:restore-)?test-[a-f0-9]{12})", args.project):
        parser.error("Разрешены только отдельные тестовые проекты")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.report_name):
        parser.error("Некорректная метка отчёта")
    directory = ROOT / "security/reports" / args.report_name
    directory.mkdir(parents=True, exist_ok=False)
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "passed": False,
              "services": [], "checks": []}

    def docker(*arguments):
        return subprocess.run(["docker", *arguments], capture_output=True, text=True,
                              encoding="utf-8", check=True, timeout=60).stdout

    def instances(service, include_stopped=False):
        return docker("ps", "-aq" if include_stopped else "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                      "--filter", f"label=com.docker.compose.service={service}").split()

    pg_ids = instances("postgres")
    assert len(pg_ids) == 1
    pg = pg_ids[0]
    pg_info = json.loads(docker("inspect", pg))[0]
    pg_environment = dict(item.split("=", 1) for item in pg_info["Config"]["Env"] if "=" in item)
    admin = pg_environment.get("POSTGRES_USER", "cabinet")

    def execute(database, user, password, statement, tcp=True):
        command = ["docker", "exec", "-i"]
        environment = dict(os.environ)
        if tcp:
            environment["PGPASSWORD"] = password
            command += ["-e", "PGPASSWORD"]  # inherited from this subprocess environment, never argv
        command += [pg, "psql", "-X", "-q", "-tA", "-v", "ON_ERROR_STOP=1", "-v", "VERBOSITY=verbose",
                    "-U", user, "-d", database]
        if tcp:
            command += ["-h", "127.0.0.1"]
        return subprocess.run(command, input=statement, env=environment, capture_output=True, text=True,
                              encoding="utf-8", timeout=20)

    def admin_sql(database, statement):
        response = execute(database, admin, None, statement, tcp=False)
        assert response.returncode == 0, "Privileged fixture setup/cleanup failed; no credentials logged"
        return response.stdout.strip()

    def checked(label, database, user, password, statement, expected=None):
        response = execute(database, user, password, statement)
        state = re.search(r"(?:ERROR|FATAL):\s+([A-Z0-9]{5}):", response.stderr)
        actual = state[1] if state else None
        # libpq's connection-failure string does not expose SQLSTATE to psql's verbosity
        # formatter. Require the specific CONNECT-privilege failure, not an auth/network error.
        connect_denied = "FATAL:" in response.stderr and "permission denied for database" in response.stderr
        report["checks"].append({"check": label, "database": database, "role": user,
                                 "expected_error": expected, "sqlstate": actual,
                                 "connect_privilege_denied": connect_denied, "exit_code": response.returncode})
        if expected is None:
            assert response.returncode == 0, f"{label}: expected success, got SQLSTATE {actual}"
        elif expected == "connect_denied":
            assert response.returncode != 0 and connect_denied, f"{label}: CONNECT denial not observed"
        else:
            assert response.returncode != 0 and actual == expected, f"{label}: expected {expected}, got {actual}"
        return response.stdout.strip()

    try:
        for database in DATABASES:
            runtime_ids = instances(database)
            migration_ids = instances(database + "-migrate", True)
            assert runtime_ids and migration_ids
            migration = json.loads(docker("inspect", migration_ids[0]))[0]
            assert migration["State"]["Status"] == "exited" and migration["State"]["ExitCode"] == 0
            migration_env = dict(item.split("=", 1) for item in migration["Config"]["Env"] if "=" in item)
            owner = database + "_owner"
            assert migration_env["DB_USER"] == owner
            runtime = json.loads(docker("inspect", *runtime_ids))
            for container in runtime:
                values = dict(item.split("=", 1) for item in container["Config"]["Env"] if "=" in item)
                user, password = values["DB_USER"], values["DB_PASSWORD"]
                assert user == database + "_app" and values["DB_NAME"] == database
                assert values.get("SPRING_FLYWAY_ENABLED") == "false"
                assert "POSTGRES_PASSWORD" not in values
                assert not any(service.upper() + suffix in values for service in DATABASES
                               for suffix in ("_DB_PASSWORD", "_MIGRATION_PASSWORD"))
                assert password != migration_env["DB_PASSWORD"] and password != pg_environment["POSTGRES_PASSWORD"]
                assert container["Image"] == migration["Image"], "Migration and runtime must use the same tested artifact"
                current = checked("authenticated runtime identity", database, user, password,
                                  "select current_user || ':' || current_database()")
                assert current == user + ":" + database
            report["services"].append({"database": database, "runtime_ids": runtime_ids, "role": user, "owner": owner,
                                       "migration_id": migration["Id"], "image_id": migration["Image"], "owner_secret_absent": True})
            attributes = checked("role attributes and memberships", database, user, password, """
                select json_build_object('superuser',rolsuper,'createdb',rolcreatedb,'createrole',rolcreaterole,
                  'bypassrls',rolbypassrls,'replication',rolreplication,'inherit',rolinherit,
                  'membership',(select count(*) from pg_auth_members where member=r.oid))
                from pg_roles r where rolname=current_user
            """)
            assert not any(json.loads(attributes).values())
            for foreign in (*[db for db in DATABASES if db != database], "postgres", "cabinet", "template1"):
                checked("foreign CONNECT and SELECT", foreign, user, password, "select * from public.delivery_outbox limit 0", "connect_denied")
            for label, query in {
                "create schema": "create schema permission_probe_forbidden",
                "create table": "create table public.permission_probe_forbidden(id int)",
                "create temporary table": "create temporary table permission_probe_forbidden(id int)",
                "alter table": "alter table public.delivery_outbox add column permission_probe_forbidden int",
                "truncate business table": "truncate public.delivery_outbox",
                "disable triggers": "alter table public.delivery_outbox disable trigger all",
                "assume schema owner": f"set role {owner}",
                "disable replication triggers": "set session_replication_role=replica",
                "alter migration history": "delete from public.flyway_schema_history where false",
            }.items():
                checked(label, database, user, password, "begin; " + query + "; rollback;", "42501")
            for table in AUDIT[database]:
                column = admin_sql(database, f"select quote_ident(attname) from pg_attribute where attrelid='public.{table}'::regclass and attnum>0 and not attisdropped order by attnum limit 1")
                for action in (f"update public.{table} set {column}={column} where false",
                               f"delete from public.{table} where false", f"truncate public.{table}"):
                    checked("append-only " + action.split()[0] + " " + table, database, user, password,
                            "begin; " + action + "; rollback;", "42501")
            identifier = str(uuid4())
            result = checked("own business CRUD rollback", database, user, password, f"""
                begin;
                insert into public.delivery_outbox(id,channel,event_key,payload) values
                  ('{identifier}','permission-probe','{identifier}','{{}}');
                update public.delivery_outbox set attempts=1 where id='{identifier}';
                select count(*) from public.delivery_outbox where id='{identifier}' and attempts=1;
                delete from public.delivery_outbox where id='{identifier}';
                select count(*) from public.delivery_outbox where id='{identifier}';
                rollback;
            """)
            assert result.split() == ["1", "0"]
            # Fresh objects created by the actual schema owner must receive usable default
            # privileges too. These named fixtures are the only objects this verifier drops.
            fixture = "permission_probe_" + uuid4().hex
            sequence = fixture + "_seq"
            try:
                admin_sql(database, f"set role {owner}; create sequence public.{sequence}; create table public.{fixture}(id bigint default nextval('public.{sequence}'), value text)")
                value = checked("new owner objects CRUD and sequence", database, user, password, f"""
                    begin; insert into public.{fixture}(value) values ('first');
                    update public.{fixture} set value='second';
                    select id || ':' || value from public.{fixture};
                    delete from public.{fixture}; rollback;
                """)
                assert value == "1:second"
                checked("new owner object DDL denied", database, user, password, f"begin; drop table public.{fixture}; rollback", "42501")
            finally:
                admin_sql(database, f"drop table if exists public.{fixture}; drop sequence if exists public.{sequence}")
            owners = json.loads(admin_sql(database, f"""select json_build_object(
                'database',(select pg_get_userbyid(datdba) from pg_database where datname=current_database()),
                'schema',(select pg_get_userbyid(nspowner) from pg_namespace where nspname='public'),
                'foreign_table_owners',(select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace
                    where n.nspname='public' and pg_get_userbyid(c.relowner)<>'{owner}'))"""))
            assert owners == {"database": owner, "schema": owner, "foreign_table_owners": 0}
        report["passed"] = True
    finally:
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        report["checks_total"] = len(report["checks"])
        report["denials"] = sum(row["expected_error"] is not None for row in report["checks"])
        (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in {"checks", "services"}}, ensure_ascii=False))


if __name__ == "__main__":
    main()
