"""SQL-only legacy backup regression on the existing provider's fresh owned PG.

No connection/credentials CLI. This fixture never targets an existing schema:
both databases must be empty and owned by their dedicated owners. Real source
DDL and permissions are applied; history rows are explicitly test fixtures,
not a claim that a targeted JVM/Flyway migration ran.
"""
from hashlib import sha256
from contextlib import nullcontext
import importlib.util
import json
from pathlib import Path
import re
import subprocess
from tempfile import TemporaryDirectory
from uuid import uuid4

REPO = Path(__file__).resolve().parents[3]


def load_transfer():
    path = REPO / "services/servicing/cutover/transfer.py"
    spec = importlib.util.spec_from_file_location("legacy_schema_actual_transfer", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(query):
    """query(database, SQL) is supplied only by the fresh, job-owned PG provider."""
    transfer = load_transfer()
    transfer.sql = query
    for database in ("credit", "servicing"):
        actual = query(database, "select current_database()||':'||current_user;")
        count = query(database, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and c.relkind in ('r','p','v','m','S');")
        if actual != database + ":" + database + "_owner" or count != "0":
            raise RuntimeError("Legacy regression requires the provider's fresh empty owner databases")
    checks, files = {}, {}

    def passed(name, condition=True):
        if not condition:
            raise RuntimeError("Legacy schema regression failed: " + name)
        checks[name] = True

    def denied(name, operation):
        try:
            operation()
        except (SystemExit, subprocess.CalledProcessError) as error:
            if isinstance(error, subprocess.CalledProcessError) and error.returncode != 3:
                raise RuntimeError("Legacy negative case failed outside PostgreSQL SQL rejection") from None
            checks[name] = {"denied": True, "exit_code": error.returncode if isinstance(error, subprocess.CalledProcessError) else None}
        else:
            raise RuntimeError("Legacy schema regression unexpectedly accepted: " + name)

    def source(path):
        raw = path.read_bytes()
        files[str(path.relative_to(REPO))] = sha256(raw).hexdigest()
        return raw.decode("utf-8")

    def history(database, version, rank):
        query(database, f"insert into flyway_schema_history(installed_rank,version,description,type,script,installed_by,execution_time,success) "
                        f"values({rank},'{version}','SQL-only legacy acceptance fixture','SQL','fixture-{version}',current_user,0,true);")

    initialized = False
    try:
        initialized = True
        for database, last in (("credit", 26), ("servicing", 1)):
            query(database, "create table flyway_schema_history(installed_rank integer primary key,version varchar(50),description varchar(200) not null,"
                            "type varchar(20) not null,script varchar(1000) not null,checksum integer,installed_by varchar(100) not null,"
                            "installed_on timestamp not null default now(),execution_time integer not null,success boolean not null);")
            paths = []
            for path in (REPO / "services" / database / "src/main/resources/db/migration").glob("V*.sql"):
                match = re.fullmatch(r"V([1-9][0-9]*)__.+\.sql", path.name)
                if match and int(match[1]) <= last:
                    paths.append((int(match[1]), path))
            if sorted(version for version, _ in paths) != list(range(1, last + 1)):
                raise RuntimeError("Legacy migration source inventory changed")
            for version, path in sorted(paths):
                text = source(path).replace("${demo-accounts}", "false")
                if "${" in text:
                    raise RuntimeError("Unsupported placeholder in legacy SQL fixture")
                query(database, text)
                history(database, version, version)
            permissions = source(REPO / "libs/platform-common/src/main/resources/cabinet/database-permissions.sql")
            query(database, permissions.replace("__DATABASE__", database).replace("__OWNER__", database + "_owner").replace("__APP__", database + "_app"))
            passed(database + "-actual-legacy-ddl", query(database, "select to_regclass('public.loan_operations') is null;") == "t")
        # Real legacy Credit aggregate, owned only by this disposable test database.
        application, account, company, actor, tranche = (str(uuid4()) for _ in range(5))
        query("credit", f"insert into applications(id,company_id,company_inn,company_name,legal_form,created_by,product_code,purpose,amount,term_months,status,created_at,updated_at,status_changed_at,submitted_at,product_catalog_snapshot) "
                        f"values('{application}','{company}','0012345678','Legacy PG fixture','LEGAL_ENTITY','{actor}','OVERDRAFT','CASH_GAP',400000,12,'DISBURSED',now(),now(),now(),now(),'{{\"OVERDRAFT\":1}}'::jsonb);")
        query("credit", f"insert into loan_accounts(id,application_id,company_id,contract_number,approved_limit,disbursed_amount,outstanding_principal,rate,term_months,revolving,disbursed_at,maturity_date) "
                        f"values('{account}','{application}','{company}','LEGACY-OWN',400000,200000,200000.00,20.00,12,true,'2026-01-01T00:00:00Z','2027-01-01');"
                        f"insert into loan_tranches(id,loan_account_id,tranche_number,amount,disbursed_at) values('{tranche}','{account}',1,200000,'2026-01-01T00:00:00Z');")
        old_fences = {db: transfer.fence_info(db) for db in ("credit", "servicing")}
        old_data = {db: transfer.snapshot(db, legacy_backup=True) for db in ("credit", "servicing")}
        old_hash = {db: transfer.checksum(data) for db, data in old_data.items()}
        passed("legacy-seven-key-json-empty-operations", all(set(data) == set(transfer.TABLES) and data["loan_operations"] == [] for data in old_data.values()))
        passed("legacy-existing-aggregate-row", len(old_data["credit"]["loan_accounts"]) == len(old_data["credit"]["loan_tranches"]) == 1)
        for db, active in (("credit", True), ("servicing", False)):
            denied(db + "-legacy-default-grants-strict", lambda db=db, active=active: transfer.require_grants(db, active))
            denied(db + "-legacy-default-snapshot-strict", lambda db=db: transfer.snapshot(db))
            transfer.require_grants(db, active, legacy_backup=True)
            passed(db + "-legacy-grants")
            privileges = [f"has_table_privilege('{db}_app','public.{table}','{right}')" for table in transfer.TABLES for right in ("SELECT", "INSERT", "UPDATE", "DELETE")]
            condition = " and ".join(privileges) if active else "not (" + " or ".join(privileges) + ")"
            condition += f" and has_table_privilege('{db}_app','public.servicing_writer_fence','SELECT') and not has_table_privilege('{db}_app','public.servicing_writer_fence','UPDATE')"
            denied(db + "-original-unconditional-query", lambda db=db, condition=condition: query(db, "select " + condition + ";"))
            for table in (name for name in transfer.TABLES if name != "loan_operations"):
                query(db, f"alter table {table} rename to legacy_missing_table;")
                try:
                    denied(db + "-missing-" + table, lambda db=db, active=active: transfer.require_grants(db, active, legacy_backup=True))
                    denied(db + "-snapshot-missing-" + table, lambda db=db: transfer.snapshot(db, legacy_backup=True))
                finally:
                    query(db, f"alter table legacy_missing_table rename to {table};")
            for right in ("SELECT", "INSERT", "UPDATE", "DELETE"):
                wrong, restore = ("revoke", "grant") if active else ("grant", "revoke")
                query(db, f"{wrong} {right} on loan_accounts {'from' if wrong == 'revoke' else 'to'} {db}_app;")
                try:
                    denied(db + "-legacy-wrong-" + right, lambda db=db, active=active: transfer.require_grants(db, active, legacy_backup=True))
                finally:
                    query(db, f"{restore} {right} on loan_accounts {'from' if restore == 'revoke' else 'to'} {db}_app;")
            for changed, restore in (("revoke SELECT", "grant SELECT"), ("grant UPDATE", "revoke UPDATE")):
                query(db, f"{changed} on servicing_writer_fence {'from' if changed.startswith('revoke') else 'to'} {db}_app;")
                try:
                    denied(db + "-legacy-fence-" + changed.replace(" ", "-"), lambda db=db, active=active: transfer.require_grants(db, active, legacy_backup=True))
                finally:
                    query(db, f"{restore} on servicing_writer_fence {'from' if restore.startswith('revoke') else 'to'} {db}_app;")
            threshold = 27 if db == "credit" else 2
            history(db, threshold, 9000)
            try:
                denied(db + "-modern-history-missing-operations-grants", lambda db=db, active=active: transfer.require_grants(db, active, legacy_backup=True))
                denied(db + "-modern-history-missing-operations-snapshot", lambda db=db: transfer.snapshot(db, legacy_backup=True))
            finally:
                query(db, "delete from flyway_schema_history where installed_rank=9000;")
            query(db, "alter table flyway_schema_history rename to missing_history;")
            try:
                denied(db + "-missing-history", lambda db=db: transfer.snapshot(db, legacy_backup=True))
            finally:
                query(db, "alter table missing_history rename to flyway_schema_history;")
            query(db, "update flyway_schema_history set success=false where installed_rank=1;")
            try:
                denied(db + "-failed-history", lambda db=db, active=active: transfer.require_grants(db, active, legacy_backup=True))
            finally:
                query(db, "update flyway_schema_history set success=true where installed_rank=1;")
            query(db, "update flyway_schema_history set version='unknown' where installed_rank=1;")
            try:
                denied(db + "-unknown-version", lambda db=db: transfer.snapshot(db, legacy_backup=True))
            finally:
                query(db, "update flyway_schema_history set version='1' where installed_rank=1;")
            query(db, "update flyway_schema_history set version=null where installed_rank=1;")
            try:
                denied(db + "-null-version-grants", lambda db=db, active=active: transfer.require_grants(db, active, legacy_backup=True))
                denied(db + "-null-version-snapshot", lambda db=db: transfer.snapshot(db, legacy_backup=True))
            finally:
                query(db, "update flyway_schema_history set version='1' where installed_rank=1;")
        # Mutating owner operations still require every current table.
        denied("legacy-fence-still-strict-seven", lambda: transfer.fence("credit"))
        denied("legacy-activate-still-strict-seven", lambda: transfer.activate("servicing", "REMOTE", old_hash["credit"]))
        denied("legacy-import-still-strict-seven", lambda: transfer.import_snapshot("servicing", old_data["servicing"]))
        passed("legacy-denials-kept-fences-and-rows", old_fences == {db: transfer.fence_info(db) for db in old_fences}
               and old_hash == {db: transfer.checksum(transfer.snapshot(db, legacy_backup=True)) for db in old_hash})
        # Exercise actual recover against real legacy SQL. This provider alone
        # owns these fresh databases; only the external service-process lock is
        # substituted, not SQL/grants/snapshots/fences/manifest verification.
        original_lock, original_sql = transfer.control_lock, transfer.sql
        transfer.control_lock = lambda database: nullcontext()
        try:
            with TemporaryDirectory(prefix="legacy-recovery-manifest-") as temporary:
                directory = Path(temporary)
                manifest = transfer.save(directory, "source-fenced.json", old_data["credit"])
                transfer.save(directory, "target-before.json", old_data["servicing"])
                digest = old_hash["credit"]
                for phase, credit_writer, servicing_writer, abort in (
                    ("completed-after-abort", "LOCAL", "FENCED", True),
                    ("completed-after-activation", "FENCED", "REMOTE", False),
                ):
                    for db, writer in (("credit", credit_writer), ("servicing", servicing_writer)):
                        query(db, f"update servicing_writer_fence set writer='{writer}',manifest_sha256='{digest}' where singleton;")
                        active = writer == ("LOCAL" if db == "credit" else "REMOTE")
                        rights = "grant select,insert,update,delete on " if active else "revoke select,insert,update,delete on "
                        tables = ",".join("public." + table for table in transfer.TABLES if table != "loan_operations")
                        query(db, rights + tables + (" to " if active else " from ") + db + "_app;")
                        transfer.require_grants(db, active, legacy_backup=True)
                    passed("legacy-recovery-" + phase + "-valid-old-grant-profiles")
                    before = {db: transfer.fence_info(db) for db in old_fences}
                    statements = []
                    def recovery_query(database, statement):
                        statements.append(statement)
                        return query(database, statement)
                    transfer.sql = recovery_query
                    try:
                        denied("legacy-recovery-" + phase + "-strict-no-write", lambda: transfer.recover("credit", "servicing", "remote", manifest, abort))
                    finally:
                        transfer.sql = original_sql
                    passed("legacy-recovery-" + phase + "-only-selects", bool(statements) and all(statement.lstrip().lower().startswith("select ") for statement in statements))
                    passed("legacy-recovery-" + phase + "-unchanged-fences", before == {db: transfer.fence_info(db) for db in old_fences})
        finally:
            transfer.control_lock, transfer.sql = original_lock, original_sql
            for db, stamp in old_fences.items():
                query(db, "update servicing_writer_fence set writer=" + transfer.literal(stamp["writer"]) + ",manifest_sha256=null where singleton;")
                rights = "grant select,insert,update,delete on " if db == "credit" else "revoke select,insert,update,delete on "
                tables = ",".join("public." + table for table in transfer.TABLES if table != "loan_operations")
                query(db, rights + tables + (" to " if db == "credit" else " from ") + db + "_app;")
        for db, version, active in (("credit", 27, True), ("servicing", 2, False)):
            path, = (REPO / "services" / db / "src/main/resources/db/migration").glob(f"V{version}__*.sql")
            query(db, source(path)); history(db, version, version)
            transfer.require_grants(db, active)
            passed(db + "-current-seven-table-grants")
            passed(db + "-empty-operation-migration-keeps-checksum", transfer.checksum(transfer.snapshot(db)) == old_hash[db])
            query(db, "alter table loan_operations rename to missing_operations;")
            try:
                denied(db + "-current-missing-operations", lambda db=db, active=active: transfer.require_grants(db, active))
                denied(db + "-current-snapshot-missing-operations", lambda db=db: transfer.snapshot(db))
            finally:
                query(db, "alter table missing_operations rename to loan_operations;")
            for right in ("SELECT", "INSERT", "UPDATE", "DELETE"):
                wrong, restore = ("revoke", "grant") if active else ("grant", "revoke")
                query(db, f"{wrong} {right} on loan_operations {'from' if wrong == 'revoke' else 'to'} {db}_app;")
                try:
                    denied(db + "-current-operation-" + right, lambda db=db, active=active: transfer.require_grants(db, active))
                finally:
                    query(db, f"{restore} {right} on loan_operations {'from' if restore == 'revoke' else 'to'} {db}_app;")
        query("credit", f"insert into loan_operations(loan_account_id,operation_key,fingerprint,result) values('{account}','{uuid4()}','{'a' * 64}',"
                        "'{\"amount\":200000.00,\"precision\":12345678901234567890.123456789}'::jsonb);")
        operations = transfer.snapshot("credit")["loan_operations"]
        passed("current-operation-json-exact-text", len(operations) == 1 and isinstance(operations[0]["result"], str)
               and "12345678901234567890.123456789" in operations[0]["result"])
        passed("current-fences-unchanged", old_fences == {db: transfer.fence_info(db) for db in old_fences})
        return {"checks": checks, "sql_sources_sha256": files, "legacy_snapshot_sha256": old_hash,
                "legacy_fences": old_fences, "history_fixture": "SQL-only actual source DDL plus explicit synthetic Flyway history; no targeted JVM migration",
                "recovery_fixture_lock_scope": "exclusive fresh provider databases; only service-process control_lock substituted; actual SQL and recovery guards retained",
                "schemaVersion": 1, "table_inventory": list(transfer.TABLES)}
    finally:
        if initialized:
            # Only the two initially empty databases created by this provider.
            # The normal existing gate subsequently migrates them from scratch.
            for database in ("credit", "servicing"):
                query(database, f"drop schema public cascade; create schema public authorization {database}_owner;")


if __name__ == "__main__":
    raise SystemExit("Fresh owned PostgreSQL acceptance provider required; no network CLI")
