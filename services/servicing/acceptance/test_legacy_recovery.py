"""Recovery must not inherit the read-only legacy-backup exemption."""
from contextlib import ExitStack, nullcontext, redirect_stdout
from copy import deepcopy
from io import StringIO
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

from legacy_schema import load_transfer


class OwnerSql:
    """Local SQL responses with real grant-query evaluation and a write trap."""

    def __init__(self, tables, fences, active, missing=(), allow_marker=False):
        self.tables = set(tables)
        self.fences, self.active = deepcopy(fences), active
        self.missing, self.allow_marker = set(missing), allow_marker
        self.queries, self.updates = [], []

    def __call__(self, database, statement):
        self.queries.append((database, statement))
        if statement == "select current_database()||':'||current_user;":
            return database + ":" + database + "_owner"
        if statement == "select current_database();":
            return database
        if statement == "select count(*) from servicing_open_outbox where state <> 'CONFIRMED';":
            return "0"
        if statement == "select row_to_json(f) from servicing_writer_fence f where singleton;":
            return json.dumps(self.fences[database])
        if statement == "select to_regclass('public.loan_operations') is not null;":
            return "f" if database in self.missing else "t"
        if statement == "select to_regclass('public.flyway_schema_history') is not null;":
            return "t"
        if statement.startswith("select count(*) filter (where version is not null)"):
            # The old Credit V26 / Servicing V1 histories are otherwise valid.
            return "t"
        if statement.startswith("select ") and "has_table_privilege(" in statement:
            privileges = re.findall(r"has_table_privilege\('([^']+)','public\.([^']+)','([^']+)'\)", statement)
            expected = self.tables - ({"loan_operations"} if database in self.missing else set())
            finance = [(table, right) for role, table, right in privileges if table != "servicing_writer_fence"]
            if (any(role != database + "_app" for role, _, _ in privileges)
                    or set(finance) != {(table, right) for table in expected for right in ("SELECT", "INSERT", "UPDATE", "DELETE")}
                    or len(finance) != 4 * len(expected)
                    or [(table, right) for _, table, right in privileges if table == "servicing_writer_fence"]
                    != [("servicing_writer_fence", "SELECT"), ("servicing_writer_fence", "UPDATE")]
                    or "and not has_table_privilege(" not in statement):
                raise AssertionError("Unexpected owner grant-query shape")
            wants_active = not statement.startswith("select not (")
            return "t" if wants_active == self.active[database] else "f"
        if statement.startswith("update servicing_writer_fence set writer='REMOTE'"):
            self.updates.append((database, statement))
            if not self.allow_marker:
                raise AssertionError("Legacy recovery attempted an owner-marker UPDATE")
            digest = re.search(r"manifest_sha256='([a-f0-9]{64})'", statement)
            if not digest or database != "credit" or self.fences[database]["writer"] != "FENCED":
                raise AssertionError("Unexpected owner-marker UPDATE")
            self.fences[database].update(writer="REMOTE", manifest_sha256=digest[1])
            self.fences[database]["epoch"] += 1
            return ""
        raise AssertionError("Unexpected SQL in recovery fixture")


class LegacyRecoveryBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.transfer = load_transfer()
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for name in ("subprocess.run", "subprocess.Popen", "socket.create_connection", "socket.getaddrinfo"):
            self.stack.enter_context(patch(name, side_effect=AssertionError("Offline recovery attempted external I/O")))
        directory = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.data = {table: [] for table in self.transfer.TABLES}
        self.digest = self.transfer.checksum(self.data)
        self.manifest = directory / "source-fenced.json"
        record = {"schemaVersion": 1, "sha256": self.digest, "tables": self.data}
        self.manifest.write_text(json.dumps(record))
        (directory / "target-before.json").write_text(json.dumps(record))

    def fixture(self, direction, phase, missing=(), *, allow_marker=False):
        source, target = ("credit", "servicing") if direction == "remote" else ("servicing", "credit")
        active_writer = {"credit": "LOCAL", "servicing": "REMOTE"}
        writers = {source: "FENCED", target: active_writer[target] if phase == "activated" else "FENCED"}
        if phase == "aborted":
            writers[source] = active_writer[source]
        fences = {database: {"writer": writer, "epoch": 3, "manifest_sha256": self.digest}
                  for database, writer in writers.items()}
        active = {database: writer == active_writer[database] for database, writer in writers.items()}
        return OwnerSql(self.transfer.TABLES, fences, active, missing, allow_marker), source, target

    def recover(self, sql, direction, abort):
        mutators = []
        with ExitStack() as stack:
            stack.enter_context(patch.object(self.transfer, "sql", side_effect=sql))
            stack.enter_context(patch.object(self.transfer, "control_lock", return_value=nullcontext()))
            stack.enter_context(patch.object(self.transfer, "snapshot", return_value=deepcopy(self.data)))
            for name in ("fence", "activate", "import_snapshot", "finish_credit_marker"):
                original = getattr(self.transfer, name)
                def observed(*args, _name=name, _original=original, **kwargs):
                    mutators.append(_name)
                    return _original(*args, **kwargs)
                stack.enter_context(patch.object(self.transfer, name, new=observed))
            self.mutators = mutators
            with redirect_stdout(StringIO()) as output:
                self.transfer.recover("credit", "servicing", direction, self.manifest, abort)
        return json.loads(output.getvalue())

    def deny_legacy_recovery(self, phase):
        for direction in ("remote", "local"):
            for missing_side in ("source", "target", "both"):
                with self.subTest(direction=direction, missing_side=missing_side):
                    sql, source, target = self.fixture(direction, phase)
                    missing = target if missing_side == "target" else source
                    sql.missing.update((source, target) if missing_side == "both" else (missing,))
                    with self.assertRaises(SystemExit):
                        self.recover(sql, direction, abort=phase != "activated")
                    self.assertIn((missing, "select to_regclass('public.loan_operations') is not null;"), sql.queries)
                    self.assertFalse(any("flyway_schema_history" in query for _, query in sql.queries),
                                     "Recovery must not enter the legacy-backup history exemption")
                    self.assertEqual(sql.updates, [])
                    self.assertEqual(self.mutators, [])

    def test_completed_after_activation_denies_legacy_before_finishing_owner_marker(self):
        self.deny_legacy_recovery("activated")

    def test_completed_after_abort_denies_legacy_before_finishing_owner_marker(self):
        self.deny_legacy_recovery("aborted")

    def test_abort_denies_legacy_before_reactivating_the_source(self):
        self.deny_legacy_recovery("fenced")

    def test_current_completed_after_activation_reaches_the_actual_marker_update(self):
        sql, _, _ = self.fixture("remote", "activated", allow_marker=True)
        result = self.recover(sql, "remote", abort=False)
        self.assertEqual(result["recovery"], "completed-after-activation")
        self.assertEqual(self.mutators, ["finish_credit_marker"])
        self.assertEqual(len(sql.updates), 1)
        self.assertEqual(sql.fences["credit"]["writer"], "REMOTE")
        self.assertEqual(sql.fences["credit"]["manifest_sha256"], self.digest)

    def test_only_explicit_backup_opt_in_reads_legacy_grants_without_writing(self):
        sql, _, _ = self.fixture("remote", "fenced", missing=("credit", "servicing"))
        sql.active = {"credit": True, "servicing": False}
        with patch.object(self.transfer, "sql", side_effect=sql):
            for database, active in sql.active.items():
                self.transfer.require_grants(database, active, legacy_backup=True)
        grant_queries = [query for _, query in sql.queries if "has_table_privilege(" in query]
        self.assertEqual(len(grant_queries), 2)
        self.assertTrue(all("public.loan_operations" not in query for query in grant_queries))
        self.assertEqual(sql.updates, [])


if __name__ == "__main__":
    unittest.main()
