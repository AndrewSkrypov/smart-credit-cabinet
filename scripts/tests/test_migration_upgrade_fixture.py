import importlib.util
from decimal import Decimal, ROUND_HALF_UP
from hashlib import sha256
import json
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("migration_restore", ROOT / "deploy/check-migration-restore.py")
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)
fixture = restore.credit_upgrade_fixture()


class MigrationUpgradeFixtureTest(unittest.TestCase):
    def test_historical_ready_inputs_match_models_and_exact_loan_math(self):
        model = (ROOT / "libs/documents-model/src/main/kotlin/ru/creditcabinet/document/generated/ArtifactModels.kt").read_text()
        math = (ROOT / "libs/loan-math/src/main/kotlin/ru/creditcabinet/product/domain/LoanMath.kt").read_text()
        fields = lambda source, name: re.findall(r"val (\w+):", source.split(f"data class {name}(", 1)[1].split(")", 1)[0])
        rows = [row for table, row in fixture.ROWS if table == "generated_artifacts"]
        offer, = [row for table, row in fixture.ROWS if table == "offers"]
        application, = [row for table, row in fixture.ROWS if table == "applications" and row["id"] == fixture.SERVICING_APP]
        self.assertEqual(len(rows), 2)
        self.assertEqual({row["template_version"] for row in rows}, {1, 2})
        self.assertEqual(len({row["input_hash"] for row in rows}), 2)
        self.assertEqual({(row["application_id"], row["offer_id"], row["phase"]) for row in rows},
                         {(fixture.SERVICING_APP, fixture.OFFER, "FINAL")})
        inputs = []
        for row in rows:
            with self.subTest(template=row["template_version"]):
                frozen = row["input_json"]
                self.assertEqual(sha256(frozen.encode()).hexdigest(), row["input_hash"])
                data = json.loads(frozen, parse_float=Decimal)
                inputs.append(data)
                self.assertEqual(list(data), fields(model, "ArtifactInput"))
                self.assertEqual((data["applicationId"], data["offerId"], data["phase"]),
                                 (row["application_id"], row["offer_id"], row["phase"]))
                self.assertEqual(data["applicationNumber"], 73004)
                self.assertEqual(data["applicationNumber"], application["number"])
                self.assertEqual((data["amount"], data["termMonths"]), (offer["amount"], offer["term_months"]))
                self.assertEqual(data["contractTextHash"], sha256(data["contractText"].encode()).hexdigest())
                self.assertFalse(data["legacySummary"])
                calculation = data["calculation"]
                self.assertEqual(list(calculation), fields(math, "Calculation"))
                self.assertTrue(calculation["revolving"])
                self.assertEqual(str(calculation["rate"]), "15.00")
                self.assertEqual(len(calculation["schedule"]), data["termMonths"])
                balance = Decimal(data["amount"]).quantize(Decimal("0.01"))
                monthly_rate = calculation["rate"] / 100 / 12
                for month, payment in enumerate(calculation["schedule"], 1):
                    self.assertEqual(list(payment), fields(math, "ScheduleRow"))
                    interest = (balance * monthly_rate).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
                    principal = balance if month == data["termMonths"] else (balance * Decimal("0.10")).quantize(
                        Decimal("0.01"), rounding=ROUND_HALF_UP)
                    balance -= principal
                    self.assertEqual(payment, dict(month=month, payment=principal + interest,
                                                   principal=principal, interest=interest, balance=balance))
                    self.assertTrue(all(value.as_tuple().exponent == -2 for key, value in payment.items() if key != "month"))
                total = sum(payment["payment"] for payment in calculation["schedule"])
                self.assertEqual(calculation["monthlyPayment"], calculation["schedule"][0]["payment"])
                self.assertEqual(calculation["monthlyPayment"], Decimal(offer["monthly_payment"]))
                self.assertEqual(calculation["totalPayment"], total)
                self.assertEqual(calculation["totalPayment"], Decimal(offer["total_payment"]))
                self.assertEqual(calculation["overpayment"], total - data["amount"])
                self.assertEqual(balance, 0)
                self.assertEqual((row["status"], row["storage_backend"], row["content_type"]),
                                 ("READY", "LOCAL", "application/pdf"))
                self.assertEqual(row["stored_name"], f"generated-{row['id']}.pdf")
                self.assertGreater(row["size_bytes"], 0)
                self.assertRegex(row["checksum"], r"^[0-9a-f]{64}$")
                self.assertIsNotNone(row["ready_at"])
        self.assertIsNone(inputs[0]["signedAt"])
        self.assertIsNotNone(inputs[1]["signedAt"])
        inputs[0]["signedAt"] = inputs[1]["signedAt"]
        self.assertEqual(*inputs)

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
        self.assertTrue(all(row["product_catalog_snapshot"] == {"WORKING_CAPITAL": 1} for row in applications.values()))
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
        self.assertIn('"rate":15.00', fixture.literal(fixture.ARTIFACT_INPUT))
        self.assertIn("input_json::text", fixture.predicate(dict(input_json=fixture.ARTIFACT_INPUT)))

    def test_version_boundaries_require_backfill_without_table_skip(self):
        self.assertEqual(fixture.checks(22), [])
        names = lambda version: {name for name, _, _ in fixture.checks(version)}
        self.assertEqual(len(names(23)), len(fixture.ROWS))
        self.assertIn("v24_local_cycles", names(24))
        self.assertNotIn("v25_local_writer", names(24))
        self.assertIn("v25_local_writer", names(25))
        self.assertNotIn("v26_reference_backfill", names(25))
        self.assertIn("v26_reference_backfill", names(26))
        self.assertNotIn("v26_historical_artifact_reference", names(25))
        self.assertTrue({"v26_historical_artifact_reference", "v26_signed_artifact_reference"} <= names(26))
        self.assertTrue(all(query.lstrip().lower().startswith("select ") for _, query, _ in fixture.checks(26)))
        for table in ("document_references", "document_artifact_references"):
            with self.subTest(table=table), self.assertRaisesRegex(RuntimeError, table):
                def missing_table(database, query):
                    if table in query:
                        raise RuntimeError(f'relation "{table}" does not exist')
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
