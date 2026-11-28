#!/usr/bin/env python3
"""Transfer the sole servicing writer with fenced export/import and verified rollback.

Uses named libpq services, never credentials in arguments or generated receipts.
The Credit HTTP/pipeline writers must be quiesced before invoking this operator tool.
"""
import argparse
from contextlib import contextmanager
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import subprocess

TABLES = {
    "loan_accounts": ["id"],
    "loan_tranches": ["id"],
    "loan_payments": ["id"],
    "payment_reserves": ["loan_id"],
    "payment_reserve_receipt_events": ["loan_id", "event_id"],
    "servicing_opened_commands": ["application_id"],
    "loan_operations": ["loan_account_id", "operation_key"],
}


def args_for(service):
    if not re.fullmatch(r"[A-Za-z0-9_-]+", service):
        raise SystemExit("Invalid libpq service name")
    return [os.environ.get("PSQL", "psql"), "-XAtq", "--no-password", "-v", "ON_ERROR_STOP=1", f"--dbname=service={service}"]


def sql(service, query):
    return subprocess.run(args_for(service), input=query, text=True, check=True, capture_output=True).stdout.strip()


def guard(condition, message):
    return f"do $$ begin if not ({condition}) then raise exception '{message}'; end if; end $$;\n"


def literal(value):
    return "'" + value.replace("'", "''") + "'"


def query_snapshot(operations_exist=True):
    fields = []
    for table, keys in TABLES.items():
        if table == "loan_operations" and not operations_exist:
            fields.append("'loan_operations','[]'::jsonb")
            continue
        row = "to_jsonb(t) || jsonb_build_object('result',t.result::text)" if table == "loan_operations" else "to_jsonb(t)"
        fields.append(f"'{table}',coalesce((select jsonb_agg({row} order by {','.join(keys)}) from {table} t),'[]'::jsonb)")
    return "select jsonb_build_object(" + ",".join(fields) + ");"


def operations_available(service, *, legacy_backup=False):
    present = sql(service, "select to_regclass('public.loan_operations') is not null;")
    if present == "t":
        return True
    if present != "f":
        raise SystemExit("Cannot establish operation receipt table identity")
    if not legacy_backup:
        raise SystemExit("Operation receipts are required outside legacy backup reads")
    database = sql(service, "select current_database();")
    threshold = {"credit": 27, "servicing": 2}.get(database)
    if threshold is None or sql(service, "select to_regclass('public.flyway_schema_history') is not null;") != "t":
        raise SystemExit("Missing operation receipts require a confirmed legacy schema")
    # Flyway versions are numeric components, not lexically ordered strings.
    # Failed/unknown histories must not disguise a dropped modern receipt table.
    legacy = sql(service, "select count(*) filter (where version is not null)>0 "
                 "and coalesce(bool_and(success is true),false) "
                 "and coalesce(bool_and(case when version ~ '^[0-9]+([._][0-9]+)*$' then "
                 f"split_part(replace(version,'_','.'),'.',1)::numeric between 1 and {threshold - 1} "
                 "else false end),false) from public.flyway_schema_history;")
    if legacy != "t":
        raise SystemExit("Missing operation receipts require a confirmed legacy schema")
    return False


def snapshot(service, *, legacy_backup=False):
    # Only an explicit read-only backup may inspect the previous V26/V1 schema.
    # All cutover, recovery and verification callers require the current tables.
    operations_exist = operations_available(service, legacy_backup=legacy_backup)
    return json.loads(sql(service, "begin isolation level repeatable read read only; set local timezone='UTC';\n" + query_snapshot(operations_exist) + "\ncommit;"))


def checksum(data):
    return sha256(json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def save(directory, name, data):
    path = directory / name
    with path.open("x", encoding="utf-8") as out:
        json.dump({"schemaVersion": 1, "sha256": checksum(data), "tables": data}, out, ensure_ascii=False, sort_keys=True)
    return path


@contextmanager
def control_lock(credit):
    """One cluster-wide cutover lock; block new opening commands/receipts while transferring."""
    with subprocess.Popen(args_for(credit), stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True) as session:
        try:
            session.stdin.write("begin; set local lock_timeout='30s'; select pg_advisory_xact_lock(731941528328);\n"
                                "lock table servicing_open_outbox in share mode;\n"
                                "\\echo servicing_transfer_locked\n")
            session.stdin.flush()
            if not any(line.strip() == "servicing_transfer_locked" for line in session.stdout):
                raise SystemExit("Cannot acquire Credit servicing cutover lock")
            yield
        finally:
            if session.stdin and not session.stdin.closed:
                session.stdin.write("rollback;\n")
                session.stdin.close()
            session.wait()


def finance_grants(service, grant):
    database = sql(service, "select current_database();")
    if database not in ("credit", "servicing"):
        raise SystemExit("Unexpected financial database")
    privilege = "grant select,insert,update,delete on " if grant else "revoke select,insert,update,delete on "
    return privilege + ",".join("public." + table for table in TABLES) + (" to " if grant else " from ") + database + "_app;"


def fence(service):
    sql(service, "begin; set local lock_timeout='30s'; update servicing_writer_fence set writer='FENCED',epoch=epoch+1,changed_at=now() where singleton;" + finance_grants(service, False) + "commit;")


def activate(service, writer, digest):
    sql(service, "begin; select writer from servicing_writer_fence where singleton for update;" + guard("(select writer='FENCED' from servicing_writer_fence where singleton)", "Activation requires a fenced target") + finance_grants(service, True) + "update servicing_writer_fence set writer=" + literal(writer) + ",manifest_sha256=" + literal(digest) + ",epoch=epoch+1,changed_at=now() where singleton and writer='FENCED';commit;")


def import_snapshot(service, data):
    # The first response contains BigDecimal values: keep PostgreSQL's exact JSON
    # text opaque to Python float decoding, including scale and arbitrary precision.
    if any(not isinstance(row.get("result"), str) for row in data["loan_operations"]):
        raise SystemExit("Operation receipt result must be exact PostgreSQL JSON text")
    statements = ["begin; set local lock_timeout='30s'; select writer from servicing_writer_fence where singleton for update;",
                  guard("(select writer='FENCED' from servicing_writer_fence where singleton)", "Target is not fenced")]
    incoming = data["loan_accounts"]
    ids = ",".join(literal(row["id"]) for row in incoming)
    statements.append(guard(f"not exists(select 1 from loan_accounts where not (id=any(array[{ids}]::uuid[])))", "Snapshot would lose a target account"))
    for table in TABLES:
        statements.append(f"lock table {table} in access exclusive mode; alter table {table} disable trigger user;")
    # Detach only obsolete reserve FKs before removing superseded tranche identities.
    # The authoritative reserve (including its confirmation) is restored in this same transaction.
    tranche_ids = literal(json.dumps(data["loan_tranches"], ensure_ascii=False))
    statements.append("update payment_reserves target set confirmed_version=null,drawn_tranche_id=null,drawn_amount=null,confirmed_by=null,confirmed_at=null "
                      "where target.drawn_tranche_id is not null and not exists(select 1 from jsonb_populate_recordset(null::loan_tranches," + tranche_ids + "::jsonb) source where source.id=target.drawn_tranche_id);")
    # Delete superseded child identities BEFORE insertion: payment number is also unique.
    for table in reversed(TABLES):
        if table == "loan_accounts":
            continue
        keys = TABLES[table]
        document = literal(json.dumps(data[table], ensure_ascii=False))
        matches = " and ".join(f"source.{key}=target.{key}" for key in keys)
        statements.append(f"delete from {table} target where not exists(select 1 from jsonb_populate_recordset(null::{table},{document}::jsonb) source where {matches});")
    for table, keys in TABLES.items():
        rows = data[table]
        if rows:
            columns = list(rows[0])
            if any(not re.fullmatch(r"[a-z_][a-z0-9_]*", col) for col in columns):
                raise SystemExit("Invalid snapshot column")
            updates = ",".join(f"{col}=excluded.{col}" for col in columns if col not in keys)
            document = literal(json.dumps(rows, ensure_ascii=False))
            selection = ",".join("(result #>> '{}')::jsonb" if table == "loan_operations" and column == "result" else column for column in columns)
            statements.append(f"insert into {table} ({','.join(columns)}) select {selection} from jsonb_populate_recordset(null::{table},{document}::jsonb) "
                              f"on conflict ({','.join(keys)}) do update set {updates};")
    for table in TABLES:
        statements.append(f"alter table {table} enable trigger user;")
    statements.append("commit;")
    sql(service, "\n".join(statements))


def validate_databases(credit, servicing):
    for service, expected in [(credit, "credit"), (servicing, "servicing")]:
        if sql(service, "select current_database()||':'||current_user;") != expected + ":" + expected + "_owner":
            raise SystemExit("Cutover requires the exact dedicated Credit/Servicing owners and databases")


def load_manifest(path):
    if path.is_symlink() or not path.is_file():
        raise SystemExit("Manifest must be a regular file")
    document = json.loads(path.read_text(encoding="utf-8"))
    if document.get("schemaVersion") != 1 or set(document.get("tables", {})) != set(TABLES):
        raise SystemExit("Unsupported snapshot schema")
    if document.get("sha256") != checksum(document["tables"]):
        raise SystemExit("Snapshot checksum mismatch")
    return document["tables"]


def fence_info(service):
    return json.loads(sql(service, "select row_to_json(f) from servicing_writer_fence f where singleton;"))


def require_grants(service, active, *, legacy_backup=False):
    database = sql(service, "select current_database();")
    role = database + "_app"
    operations_exist = operations_available(service, legacy_backup=legacy_backup)
    privileges = [f"has_table_privilege('{role}','public.{table}','{privilege}')" for table in TABLES
                  if table != "loan_operations" or operations_exist for privilege in ("SELECT", "INSERT", "UPDATE", "DELETE")]
    condition = " and ".join(privileges) if active else "not (" + " or ".join(privileges) + ")"
    condition += f" and has_table_privilege('{role}','public.servicing_writer_fence','SELECT') and not has_table_privilege('{role}','public.servicing_writer_fence','UPDATE')"
    if sql(service, "select " + condition + ";") != "t":
        raise SystemExit("Writer grants do not match the recorded authority")


def finish_credit_marker(credit, direction, digest):
    if direction != "remote":
        return
    info = fence_info(credit)
    if info["writer"] == "REMOTE" and info["manifest_sha256"] == digest:
        return
    if info["writer"] != "FENCED":
        raise SystemExit("Unexpected Credit authority during completion")
    sql(credit, "update servicing_writer_fence set writer='REMOTE',manifest_sha256=" + literal(digest) + ",epoch=epoch+1,changed_at=now() where singleton and writer='FENCED';")


def recover(credit, servicing, direction, manifest, abort, fail_after_source_activation=False):
    validate_databases(credit, servicing)
    source, target = (credit, servicing) if direction == "remote" else (servicing, credit)
    source_writer, target_writer = ("LOCAL", "REMOTE") if direction == "remote" else ("REMOTE", "LOCAL")
    data = load_manifest(manifest)
    digest = checksum(data)
    with control_lock(credit):
        if sql(credit, "select count(*) from servicing_open_outbox where state <> 'CONFIRMED';") != "0":
            raise SystemExit("Unconfirmed commands prevent recovery")
        source_info, target_info = fence_info(source), fence_info(target)
        if abort and source_info["writer"] == source_writer and source_info["manifest_sha256"] == digest:
            # The previous abort already reactivated its verified source. It may now have later payments.
            target_is_retired = target_info["writer"] == "FENCED" or (direction == "local" and target_info["writer"] == "REMOTE" and target_info["manifest_sha256"] == digest)
            if not target_is_retired:
                raise SystemExit("Post-abort completion requires the other writer to remain retired")
            require_grants(source, True)
            require_grants(target, False)
            backup = load_manifest(manifest.parent / "target-before.json")
            if checksum(snapshot(target)) not in (digest, checksum(backup)):
                raise SystemExit("Retired target changed after abort; do not restore stale state")
            if direction == "local":
                finish_credit_marker(credit, "remote", digest)
            print(json.dumps({"recovery": "completed-after-abort", "direction": direction, "sha256": digest,
                              "currentSourceSha256": checksum(snapshot(source))}, sort_keys=True))
            return
        source_is_retired = source_info["writer"] == "FENCED" or (direction == "remote" and source_info["writer"] == "REMOTE" and source_info["manifest_sha256"] == digest)
        if not source_is_retired or checksum(snapshot(source)) != digest:
            raise SystemExit("Source is not fenced and unchanged at the recorded snapshot")
        require_grants(source, False)
        if target_info["writer"] == target_writer:
            if abort:
                raise SystemExit("Target is already active; abort requires a full reverse of its latest state")
            if target_info["manifest_sha256"] != digest:
                raise SystemExit("Active target belongs to another accepted manifest")
            require_grants(target, True)
            # Never import over an active target: it may already contain later legitimate payments.
            finish_credit_marker(credit, direction, digest)
            outcome = "completed-after-activation"
        elif target_info["writer"] == "FENCED":
            require_grants(target, False)
            backup = load_manifest(manifest.parent / "target-before.json")
            if checksum(snapshot(target)) not in (digest, checksum(backup)):
                raise SystemExit("Fenced target contains later changes; use a full reverse of its latest state")
            if abort:
                activate(source, source_writer, digest)
                if fail_after_source_activation:
                    raise RuntimeError("INJECTED_AFTER_SOURCE_REACTIVATION")
                if direction == "local":
                    finish_credit_marker(credit, "remote", digest)
                outcome = "aborted"
            else:
                import_snapshot(target, data)
                if checksum(snapshot(target)) != digest:
                    raise SystemExit("Target checksum mismatch; both writers remain fenced")
                activate(target, target_writer, digest)
                finish_credit_marker(credit, direction, digest)
                outcome = "resumed"
        else:
            raise SystemExit("Unexpected target authority")
    print(json.dumps({"recovery": outcome, "direction": direction, "sha256": digest}, sort_keys=True))


def transfer(credit, servicing, direction, directory, fail_after_activation=False, fail_before_activation=False):
    source, target = (credit, servicing) if direction == "remote" else (servicing, credit)
    source_writer, target_writer = ("LOCAL", "REMOTE") if direction == "remote" else ("REMOTE", "LOCAL")
    validate_databases(credit, servicing)
    directory.mkdir(mode=0o700)
    with control_lock(credit):
        if sql(credit, "select count(*) from servicing_open_outbox where state <> 'CONFIRMED';") != "0":
            raise SystemExit("Unconfirmed opening commands must be drained or reconciled before cutover")
        if sql(source, "select writer from servicing_writer_fence where singleton;") != source_writer:
            raise SystemExit("Source is not the active writer")
        target_current = sql(target, "select writer from servicing_writer_fence where singleton;")
        if target_current == target_writer:
            raise SystemExit("Target writer is already enabled; possible dual writer")
        save(directory, "target-before.json", snapshot(target))
        fence(source)  # waits for every account transaction, including old binaries' triggers
        fence(target)
        data = snapshot(source)
        manifest = save(directory, "source-fenced.json", data)
        import_snapshot(target, data)
        if checksum(snapshot(target)) != checksum(data):
            raise SystemExit("Target checksum mismatch; both writers remain fenced")
        digest = checksum(data)
        # Runtime cannot enable itself: fence UPDATE is owner-only.
        if fail_before_activation:
            raise RuntimeError("INJECTED_BEFORE_TARGET_ACTIVATION")
        activate(target, target_writer, digest)
        if fail_after_activation:
            raise RuntimeError("INJECTED_AFTER_TARGET_ACTIVATION")
        finish_credit_marker(credit, direction, digest)
        print(json.dumps({"direction": direction, "manifest": str(manifest.resolve()), "sha256": digest,
                          "sourceFenced": True, "targetWriter": target_writer, "counts": {t: len(data[t]) for t in TABLES}}, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("direction", choices=["remote", "local"])
    parser.add_argument("--credit-service", required=True)
    parser.add_argument("--servicing-service", required=True)
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--recover-manifest", type=Path)
    parser.add_argument("--test-fail-before-target-activation", action="store_true", help="Acceptance-only preactivation checkpoint")
    parser.add_argument("--test-fail-after-source-reactivation", action="store_true", help="Acceptance-only abort checkpoint")
    parser.add_argument("--test-fail-after-target-activation", action="store_true", help="Acceptance-only crash checkpoint; leaves an active verified target")
    parser.add_argument("--abort", action="store_true", help="Reactivate the unchanged fenced source after an incomplete transfer")
    args = parser.parse_args()
    if args.test_fail_after_source_reactivation and not (args.recover_manifest and args.abort):
        parser.error("Source reactivation checkpoint requires recovery abort")
    if args.recover_manifest and (args.test_fail_before_target_activation or args.test_fail_after_target_activation):
        parser.error("Target activation checkpoints require a normal transfer")
    try:
        if args.recover_manifest:
            recover(args.credit_service, args.servicing_service, args.direction, args.recover_manifest, args.abort, args.test_fail_after_source_reactivation)
        else:
            if args.artifacts is None or args.abort:
                parser.error("Normal transfer requires --artifacts; --abort requires --recover-manifest")
            transfer(args.credit_service, args.servicing_service, args.direction, args.artifacts, args.test_fail_after_target_activation, args.test_fail_before_target_activation)
    except subprocess.CalledProcessError as error:
        # Do not print DSNs, credentials or full SQL containing financial snapshots.
        raise SystemExit("PostgreSQL cutover operation failed; inspect recorded authority and manifest before verified recovery") from error


if __name__ == "__main__":
    main()
