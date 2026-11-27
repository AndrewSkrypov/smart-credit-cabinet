import importlib.util
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("migration_restore", ROOT / "deploy/check-migration-restore.py")
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)
fixture = restore.credit_upgrade_fixture()


class MigrationUpgradeFixtureTest(unittest.TestCase):
    def test_v23_legacy_relationships_and_bank_document(self):
        rows = {}
        for table, row in fixture.ROWS:
            rows.setdefault(table, []).append(row)
        applications = {row["id"]: row for row in rows["applications"]}
        jobs = {row["id"]: row for row in rows["pipeline_jobs"]}
        eligible = {row["id"] for row in jobs.values()
                    if row["status"] in ("PENDING", "RUNNING")
                    and applications[row["application_id"]]["status"] == "ENRICHMENT"}
        self.assertEqual(eligible, {fixture.PENDING_JOB, fixture.RUNNING_JOB})
        self.assertEqual({jobs[job_id]["status"] for job_id in eligible}, {"PENDING", "RUNNING"})
        self.assertEqual(len({row["company_id"] for row in applications.values()}), 4)
        document, = rows["documents"]
        self.assertIsNotNone(applications[document["application_id"]]["submitted_at"])
        self.assertEqual(applications[document["application_id"]]["status"], "SUBMITTED")
        self.assertEqual((document["context"], document["type"], document["private_to_bank"]),
                         ("BUSINESS", "BANK_STATEMENT", True))
        self.assertRegex(document["checksum_sha256"], r"^[0-9a-f]{64}$")
        loan, = rows["loan_accounts"]
        self.assertEqual(applications[loan["application_id"]]["status"], "DISBURSED")
        for table, key in (("loan_tranches", "loan_account_id"), ("loan_payments", "loan_account_id"),
                           ("payment_reserves", "loan_id"), ("payment_reserve_receipt_events", "loan_id")):
            self.assertEqual(rows[table][0][key], loan["id"])

    def test_sql_literals_preserve_content_without_injection(self):
        self.assertEqual(fixture.literal("D'Angelo; select 1"), "'D''Angelo; select 1'")
        self.assertEqual(fixture.literal({"file": "a'b.pdf"}), "'{\"file\": \"a''b.pdf\"}'::jsonb")
        self.assertEqual(fixture.literal(None), "NULL")
        self.assertEqual(fixture.literal(True), "true")
        self.assertEqual(fixture.insert_sql().count("insert into "), len(fixture.ROWS))

    def test_version_boundaries_require_backfill_without_table_skip(self):
        self.assertEqual(fixture.checks(22), [])
        names = lambda version: {name for name, _, _ in fixture.checks(version)}
        self.assertEqual(len(names(23)), len(fixture.ROWS))
        self.assertIn("v24_local_cycles", names(24))
        self.assertNotIn("v25_local_writer", names(24))
        self.assertIn("v25_local_writer", names(25))
        self.assertNotIn("v26_reference_backfill", names(25))
        self.assertIn("v26_reference_backfill", names(26))
        self.assertTrue(all(query.lstrip().lower().startswith("select ") for _, query, _ in fixture.checks(26)))
        with self.assertRaisesRegex(RuntimeError, "document_references"):
            def missing_table(database, query):
                if "document_references" in query:
                    raise RuntimeError('relation "document_references" does not exist')
                return dict((sql, expected) for _, sql, expected in fixture.checks(26))[query]
            fixture.verify(missing_table, "isolated", 26)

    def test_every_legacy_or_backfill_failure_is_fatal(self):
        checks = fixture.checks(26)
        answers = {query: expected for _, query, expected in checks}
        self.assertEqual(fixture.verify(lambda database, query: answers[query], "isolated", 26),
                         [name for name, _, _ in checks])
        for name, failed_query, _ in checks:
            with self.subTest(check=name), self.assertRaisesRegex(AssertionError, name):
                fixture.verify(lambda database, query: "unexpected" if query == failed_query else answers[query],
                               "isolated", 26)


if __name__ == "__main__":
    unittest.main()
