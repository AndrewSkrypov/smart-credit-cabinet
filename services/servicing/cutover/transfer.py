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


def query_snapshot():
    return "select jsonb_build_object(" + ",".join(
        f"'{table}',coalesce((select jsonb_agg(to_jsonb(t) order by {','.join(keys)}) from {table} t),'[]'::jsonb)"
        for table, keys in TABLES.items()
    ) + ");"


def snapshot(service):
    return json.loads(sql(service, "begin isolation level repeatable read read only; set local timezone='UTC';\n" + query_snapshot() + "\ncommit;"))


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


def fence(service):
    sql(service, "set lock_timeout='30s'; update servicing_writer_fence set writer='FENCED',epoch=epoch+1,changed_at=now() where singleton;")


def import_snapshot(service, data):
    statements = ["begin; set local lock_timeout='30s'; select writer from servicing_writer_fence where singleton for update;",
                  guard("(select writer='FENCED' from servicing_writer_fence where singleton)", "Target is not fenced")]
    incoming = data["loan_accounts"]
    ids = ",".join(literal(row["id"]) for row in incoming)
    statements.append(guard(f"not exists(select 1 from loan_accounts where not (id=any(array[{ids}]::uuid[])))", "Snapshot would lose a target account"))
    for table in TABLES:
        statements.append(f"lock table {table} in access exclusive mode; alter table {table} disable trigger user;")
    for table, keys in TABLES.items():
        rows = data[table]
        if rows:
            columns = list(rows[0])
            if any(not re.fullmatch(r"[a-z_]+", col) for col in columns):
                raise SystemExit("Invalid snapshot column")
            updates = ",".join(f"{col}=excluded.{col}" for col in columns if col not in keys)
            document = literal(json.dumps(rows, ensure_ascii=False))
            statements.append(f"insert into {table} select * from jsonb_populate_recordset(null::{table},{document}::jsonb) "
                              f"on conflict ({','.join(keys)}) do update set {updates};")
    # Child identifiers change when schedules are recalculated. Retain exactly the authoritative set.
    for table in reversed(TABLES):
        if table == "loan_accounts":
            continue
        keys = TABLES[table]
        document = literal(json.dumps(data[table], ensure_ascii=False))
        matches = " and ".join(f"source.{key}=target.{key}" for key in keys)
        statements.append(f"delete from {table} target where not exists(select 1 from jsonb_populate_recordset(null::{table},{document}::jsonb) source where {matches});")
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


def recover(credit, servicing, direction, manifest, abort):
    validate_databases(credit, servicing)
    source, target = (credit, servicing) if direction == "remote" else (servicing, credit)
    source_writer, target_writer = ("LOCAL", "REMOTE") if direction == "remote" else ("REMOTE", "LOCAL")
    data = load_manifest(manifest)
    digest = checksum(data)
    with control_lock(credit):
        if sql(credit, "select count(*) from servicing_open_outbox where state <> 'CONFIRMED';") != "0":
            raise SystemExit("Unconfirmed commands prevent recovery")
        if sql(source, "select writer from servicing_writer_fence where singleton;") != "FENCED":
            raise SystemExit("Recovery requires the source to remain fenced")
        if sql(target, "select writer from servicing_writer_fence where singleton;") != "FENCED":
            raise SystemExit("Recovery requires the target to remain fenced")
        if checksum(snapshot(source)) != digest:
            raise SystemExit("Source no longer matches the fenced snapshot; do not activate any writer")
        if abort:
            sql(source, "update servicing_writer_fence set writer=" + literal(source_writer) + ",manifest_sha256=" + literal(digest) + ",epoch=epoch+1,changed_at=now() where singleton and writer='FENCED';")
            if direction == "local":
                sql(credit, "update servicing_writer_fence set writer='REMOTE',manifest_sha256=" + literal(digest) + ",changed_at=now() where singleton and writer='FENCED';")
        else:
            import_snapshot(target, data)
            if checksum(snapshot(target)) != digest:
                raise SystemExit("Target checksum mismatch; both writers remain fenced")
            sql(target, "update servicing_writer_fence set writer=" + literal(target_writer) + ",manifest_sha256=" + literal(digest) + ",epoch=epoch+1,changed_at=now() where singleton and writer='FENCED';")
            if direction == "remote":
                sql(credit, "update servicing_writer_fence set writer='REMOTE',manifest_sha256=" + literal(digest) + ",changed_at=now() where singleton and writer='FENCED';")
    print(json.dumps({"recovery": "aborted" if abort else "resumed", "direction": direction, "sha256": digest}, sort_keys=True))


def transfer(credit, servicing, direction, directory):
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
        sql(target, "update servicing_writer_fence set writer=" + literal(target_writer) + ",manifest_sha256=" + literal(digest) + ",epoch=epoch+1,changed_at=now() where singleton and writer='FENCED';")
        if direction == "remote":
            sql(credit, "update servicing_writer_fence set writer='REMOTE',manifest_sha256=" + literal(digest) + ",changed_at=now() where singleton and writer='FENCED';")
        print(json.dumps({"direction": direction, "manifest": str(manifest.resolve()), "sha256": digest,
                          "sourceFenced": True, "targetWriter": target_writer, "counts": {t: len(data[t]) for t in TABLES}}, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("direction", choices=["remote", "local"])
    parser.add_argument("--credit-service", required=True)
    parser.add_argument("--servicing-service", required=True)
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--recover-manifest", type=Path)
    parser.add_argument("--abort", action="store_true", help="Reactivate the unchanged fenced source after an incomplete transfer")
    args = parser.parse_args()
    try:
        if args.recover_manifest:
            recover(args.credit_service, args.servicing_service, args.direction, args.recover_manifest, args.abort)
        else:
            if args.artifacts is None or args.abort:
                parser.error("Normal transfer requires --artifacts; --abort requires --recover-manifest")
            transfer(args.credit_service, args.servicing_service, args.direction, args.artifacts)
    except subprocess.CalledProcessError as error:
        # Do not print DSNs, credentials or full SQL containing financial snapshots.
        raise SystemExit("PostgreSQL cutover operation failed; keep writers fenced and inspect local operator logs") from error


if __name__ == "__main__":
    main()
