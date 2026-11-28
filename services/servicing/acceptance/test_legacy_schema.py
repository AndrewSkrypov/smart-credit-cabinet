"""Offline SQL selection contracts; the real PG fixture runs in the existing gate."""
from pathlib import Path
import unittest
from unittest.mock import patch

from legacy_schema import load_transfer, run


class LegacySchemaBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.transfer = load_transfer()

    def test_fixture_rejects_nonempty_or_wrong_owner_before_any_mutation(self):
        for values in (("credit:credit_owner", "1"), ("credit:other", "0")):
            with patch("legacy_schema.load_transfer") as load:
                calls = []
                def query(database, statement):
                    calls.append(statement)
                    return values[len(calls) - 1]
                with self.assertRaises(RuntimeError):
                    run(query)
                self.assertEqual(len(calls), 2)
                self.assertTrue(all(statement.startswith("select ") for statement in calls))

    def test_present_operations_never_uses_legacy_fallback(self):
        with patch.object(self.transfer, "sql", return_value="t") as sql:
            self.assertTrue(self.transfer.operations_available("credit"))
        sql.assert_called_once()

    def test_absent_operations_deny_by_default_without_legacy_history_lookup(self):
        with (patch.object(self.transfer, "sql", return_value="f") as sql,
              self.assertRaisesRegex(SystemExit, "outside legacy backup")):
            self.transfer.operations_available("credit")
        sql.assert_called_once_with("credit", "select to_regclass('public.loan_operations') is not null;")

    def test_absent_operations_require_known_database_history_and_legacy_generation(self):
        for database in ("credit", "servicing"):
            with patch.object(self.transfer, "sql", side_effect=["f", database, "t", "t"]) as sql:
                self.assertFalse(self.transfer.operations_available(database, legacy_backup=True))
                self.assertIn("bool_and(success is true)", sql.call_args.args[1])
                self.assertIn("between 1 and " + ("26" if database == "credit" else "1"), sql.call_args.args[1])
        for values in (("unknown",), ("f", "other"), ("f", "credit", "f"), ("f", "credit", "t", "f")):
            with patch.object(self.transfer, "sql", side_effect=values), self.assertRaises(SystemExit):
                self.transfer.operations_available("credit", legacy_backup=True)

    def test_only_legacy_operations_privileges_are_omitted_and_fence_stays_required(self):
        for active in (True, False):
            with (patch.object(self.transfer, "operations_available", return_value=False) as available,
                  patch.object(self.transfer, "sql", side_effect=["credit", "t"]) as sql):
                self.transfer.require_grants("credit", active, legacy_backup=True)
            available.assert_called_once_with("credit", legacy_backup=True)
            query = sql.call_args.args[1]
            self.assertNotIn("public.loan_operations", query)
            for table in self.transfer.TABLES:
                if table != "loan_operations":
                    self.assertEqual(query.count("'public." + table + "'"), 4)
            self.assertIn("public.servicing_writer_fence", query)
            self.assertIn("'UPDATE'", query)
            self.assertEqual(query.startswith("select not ("), not active)

    def test_current_schema_keeps_all_operation_crud_and_denies_bad_privileges(self):
        with (patch.object(self.transfer, "operations_available", return_value=True),
              patch.object(self.transfer, "sql", side_effect=["servicing", "f"]) as sql,
              self.assertRaises(SystemExit)):
            self.transfer.require_grants("servicing", True)
        self.assertEqual(sql.call_args.args[1].count("'public.loan_operations'"), 4)


if __name__ == "__main__":
    unittest.main()
