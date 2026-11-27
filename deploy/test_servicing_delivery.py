from contextlib import nullcontext
from copy import deepcopy
from dataclasses import replace
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from types import SimpleNamespace
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import MagicMock, patch

import servicing_dev
from runtime_delivery_support import Descriptor, read_record


SPEC = spec_from_file_location("actual_servicing_transfer", servicing_dev.TRANSFER)
TRANSFER = module_from_spec(SPEC)
SPEC.loader.exec_module(TRANSFER)


class TransferFixture:
    TABLES = TRANSFER.TABLES
    checksum = staticmethod(TRANSFER.checksum)

    def __init__(self):
        self.fences = {
            "credit": servicing_dev._stamp("LOCAL", 1),
            "servicing": servicing_dev._stamp("FENCED", 1),
        }
        self.active = {"credit": True, "servicing": False}
        self.data = {
            "credit": {table: [{"id": "synthetic"}] for table in self.TABLES},
            "servicing": {table: [] for table in self.TABLES},
        }
        self.last_changes = []
        self.failure = None
        self.unconfirmed = False
        self.disbursing = False

    def commit(self, event, database):
        self.last_changes.append((event, database))
        if sum(self.active.values()) > 1:
            raise AssertionError("Two financial grant sets are active")
        if self.failure == (event, database):
            self.failure = None
            raise RuntimeError("simulated termination after commit")

    def sql(self, database, query):
        if database != "credit":
            raise AssertionError("Unexpected SQL database")
        if "servicing_open_outbox" in query:
            return "1" if self.unconfirmed else "0"
        if "pipeline_jobs" in query:
            return "1" if self.disbursing else "0"
        raise AssertionError("Unexpected cutover query")

    def fence_info(self, database):
        return deepcopy(self.fences[database])

    def require_grants(self, database, active):
        if self.active[database] != active:
            raise AssertionError("Runtime permissions do not match owner")

    def snapshot(self, database):
        return deepcopy(self.data[database])

    def fence(self, database):
        prior = self.fences[database]
        self.fences[database] = servicing_dev._stamp("FENCED", prior["epoch"] + 1, prior["manifest_sha256"])
        self.active[database] = False
        self.commit("fence", database)

    def import_snapshot(self, database, data):
        if self.fences[database]["writer"] != "FENCED":
            raise AssertionError("Transfer overwrote the active writer")
        self.data[database] = deepcopy(data)
        self.commit("import", database)

    def activate(self, database, writer, digest):
        prior = self.fences[database]
        self.fences[database] = servicing_dev._stamp(writer, prior["epoch"] + 1, digest)
        self.active[database] = True
        self.commit("activate", database)

    def finish_credit_marker(self, credit, direction, digest):
        if direction == "remote" and self.fences[credit]["writer"] != "REMOTE":
            self.fences[credit] = servicing_dev._stamp("REMOTE", self.fences[credit]["epoch"] + 1, digest)
            self.commit("marker", credit)

    def validate_databases(self, credit, servicing):
        if (credit, servicing) != ("credit", "servicing"):
            raise AssertionError("Wrong fixture databases")

    def control_lock(self, credit):
        return nullcontext()


class ServicingDeliveryTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        state = Path(temporary.name)
        image = "registry.example.test/group/cabinet/servicing@sha256:" + "c" * 64
        self.remote = Descriptor("a" * 40, "a" * 64, "remote", image, "b" * 64, "servicing")
        self.local = replace(self.remote, manifest_sha256="b" * 64, mode="local")
        self.dev = servicing_dev.ServicingDev(self.remote, state, state / "tls", {
            "CABINET_SERVICING_GENERATION": "d" * 64,
        })
        self.transfer = TransferFixture()

    def attempt_path(self, attempt):
        return self.dev.state / "attempts" / attempt

    def last_attempt(self):
        return self.attempt_path(read_record(self.dev.pending)["attempt"])

    def switch(self):
        return self.dev._switch_authority(self.transfer)

    def test_default_local_records_current_authority_without_transferring(self):
        self.dev.descriptor = self.local
        self.assertEqual({"CABINET_SERVICING_CREDIT_MODE": "local"},
                         {key: value for key, value in self.dev.compose_environment().items()
                          if key.endswith("_MODE")})
        with self.assertRaisesRegex(RuntimeError, "ещё не согласован"):
            self.dev.credit_environment()
        authority = self.switch()
        self.assertEqual(authority, self.switch())
        self.assertEqual(authority["mode"], "local")
        self.assertIsNone(authority["attempt"])
        self.assertEqual(self.transfer.last_changes, [])

    def test_resume_after_source_fence_before_snapshot(self):
        self.transfer.failure = ("fence", "credit")
        with self.assertRaisesRegex(RuntimeError, "after commit"):
            self.switch()
        attempt = self.last_attempt()
        self.assertTrue((attempt / "target-before.json").is_file())
        self.assertFalse((attempt / "source-fenced.json").exists())
        self.assertFalse(any(self.transfer.active.values()))
        authority = self.switch()
        self.assertEqual(authority["attempt"], attempt.name)
        self.assertEqual(self.transfer.last_changes.count(("fence", "credit")), 1)
        self.assertEqual(self.transfer.fences["credit"]["manifest_sha256"],
                         self.transfer.fences["servicing"]["manifest_sha256"])
        self.assertFalse(self.dev.pending.exists())
        self.assertEqual((attempt / "source-fenced.json").stat().st_mode & 0o777, 0o600)

    def test_resume_after_target_fence_before_snapshot(self):
        self.transfer.failure = ("fence", "servicing")
        with self.assertRaisesRegex(RuntimeError, "after commit"):
            self.switch()
        self.assertFalse(any(self.transfer.active.values()))
        self.assertFalse((self.last_attempt() / "source-fenced.json").exists())
        self.assertEqual(self.switch()["mode"], "remote")
        self.assertEqual(self.transfer.last_changes.count(("fence", "servicing")), 1)

    def test_resume_after_import_without_reimport(self):
        self.transfer.failure = ("import", "servicing")
        with self.assertRaisesRegex(RuntimeError, "after commit"):
            self.switch()
        self.assertFalse(any(self.transfer.active.values()))
        self.assertFalse((self.last_attempt() / "verified.json").exists())
        self.assertEqual(self.switch()["mode"], "remote")
        self.assertEqual(self.transfer.last_changes.count(("import", "servicing")), 1)

    def test_reverse_uses_latest_payments_and_repeated_manifest_has_new_attempt(self):
        self.transfer.failure = ("activate", "servicing")
        with self.assertRaisesRegex(RuntimeError, "after commit"):
            self.switch()
        pending_path = self.last_attempt()
        self.assertTrue((pending_path / "verified.json").is_file())
        self.assertEqual(self.transfer.active, {"credit": False, "servicing": True})
        latest = self.transfer.data["servicing"]
        latest["loan_accounts"][0]["balance"] = 83
        latest["loan_payments"].append({"id": "payment", "status": "PAID"})
        latest["loan_tranches"][0]["amount"] = 7
        latest["payment_reserves"][0]["version"] = 2
        latest["payment_reserve_receipt_events"].append({"id": "latest-receipt"})
        self.assertEqual(self.switch()["attempt"], pending_path.name)
        first = self.ready_report()
        self.assertEqual(self.transfer.last_changes.count(("import", "servicing")), 1)
        self.dev.descriptor = self.local
        self.assertEqual(self.switch()["mode"], "local")
        self.assertEqual(self.transfer.data["credit"], latest)
        self.assertEqual(self.transfer.active, {"credit": True, "servicing": False})

        self.transfer.data["credit"]["loan_payments"].append({"id": "new-local-payment", "status": "PAID"})
        self.dev.descriptor = self.remote
        again = self.switch()
        third = self.ready_report()
        self.assertNotEqual(first["attempt"], third["attempt"])
        self.assertEqual(again["attempt"], third["attempt"])
        self.assertEqual(self.transfer.data["servicing"], self.transfer.data["credit"])
        self.assertEqual(len(list((self.dev.state / "ready").iterdir())), 2)

    def ready_report(self):
        deployment = MagicMock()
        with (patch.object(self.dev, "_check_database", return_value={}),
              patch.object(self.dev, "_verify_candidate", return_value="sha256:" + "f" * 64),
              patch.object(servicing_dev, "_set_up_transfer", return_value=self.transfer)):
            return self.dev.verify_credit(deployment)

    def test_stale_fenced_target_change_is_never_overwritten(self):
        self.transfer.failure = ("import", "servicing")
        with self.assertRaisesRegex(RuntimeError, "after commit"):
            self.switch()
        self.transfer.data["servicing"]["loan_payments"].append({"id": "unexpected"})
        with self.assertRaisesRegex(RuntimeError, "более новые"):
            self.switch()
        self.assertFalse(any(self.transfer.active.values()))
        self.assertEqual(self.transfer.last_changes.count(("import", "servicing")), 1)

    def test_unconfirmed_opening_and_running_abs_block_before_fencing(self):
        for flag, message in (("unconfirmed", "открытия"), ("disbursing", "АБС")):
            with self.subTest(flag=flag):
                setattr(self.transfer, flag, True)
                with self.assertRaisesRegex(RuntimeError, message):
                    self.switch()
                setattr(self.transfer, flag, False)
                self.assertEqual(self.transfer.last_changes, [])
                self.assertFalse(self.dev.pending.exists())

    def test_other_manifest_cannot_resume_pending_attempt(self):
        self.transfer.failure = ("fence", "credit")
        with self.assertRaisesRegex(RuntimeError, "after commit"):
            self.switch()
        self.dev.descriptor = replace(self.remote, manifest_sha256="e" * 64)
        with self.assertRaisesRegex(RuntimeError, "тот же"):
            self.switch()
        self.assertFalse(any(self.transfer.active.values()))

    def test_dev_repeat_preserves_private_env_and_mtls_identity(self):
        release = SimpleNamespace(
            revision=self.remote.revision, checksum=self.remote.manifest_sha256,
            images={"servicing": self.remote.image, "credit": self.remote.image.replace("/servicing@", "/credit@")},
            servicing={"jar_sha256": self.remote.jar_sha256, "dev_mode": "local"},
        )
        config = SimpleNamespace(local=False, project="cabinet-dev", release=release,
                                 path=self.dev.state / "cabinet.env", values={})
        first = servicing_dev.ServicingDev.open(config)
        second = servicing_dev.ServicingDev.open(config)
        self.assertEqual(first.secrets, second.secrets)
        self.assertEqual(first._tls_identity(), second._tls_identity())
        self.assertEqual((first.state / "servicing.env").stat().st_mode & 0o777, 0o600)
        self.assertEqual(first.state.stat().st_mode & 0o777, 0o700)
        self.assertEqual(first.volume_name, config.project + "-servicing-" + first.generation)
        config.project = "cabinet-dev-restore-synthetic"
        self.assertNotEqual(replace(first, project=config.project).volume_name, first.volume_name)
        config.project = "cabinet-prod"
        self.assertIsNone(servicing_dev.ServicingDev.open(config))
        config.project = "cabinet-dev"
        release.servicing["dev_mode"] = "disabled"
        with self.assertRaisesRegex(RuntimeError, "После регистрации"):
            servicing_dev.ServicingDev.open(config)

    def test_restore_checks_both_aggregates_before_binding_fresh_system_id(self):
        authority = self.switch()
        self.dev.tls.mkdir()
        for role in ("server", "credit"):
            (self.dev.tls / role).mkdir()
            (self.dev.tls / role / "tls.crt").write_bytes(role.encode())
        (self.dev.tls / "server/ca.crt").write_bytes(b"synthetic-ca")
        (self.dev.state / "servicing.dump").write_bytes(b"synthetic-dump")
        backup = {
            **self.dev.provenance(), **self.dev._tls_identity(), **authority,
            "pg_system_identifier": "123456",
            "aggregates": servicing_dev._aggregates(self.transfer),
        }
        fresh = {
            **self.dev._tls_identity(), "generation": self.dev.generation, "pg_system_identifier": "987654",
        }
        deployment = MagicMock()
        deployment.project = "cabinet-dev-restore-synthetic"
        self.dev.project = deployment.project
        deployment.environment = self.dev.compose_environment()
        deployment.compose.return_value = ""
        with (patch.object(Descriptor, "verify_image"),
              patch.object(self.dev, "_observe_database", return_value=fresh),
              patch.object(servicing_dev, "run", return_value=""),
              patch.object(servicing_dev, "_set_up_transfer", return_value=self.transfer),
              patch.object(servicing_dev.subprocess, "run", return_value=SimpleNamespace(returncode=0))):
            false_backup = deepcopy(backup)
            false_backup["aggregates"]["credit"]["sha256"] = "0" * 64
            with self.assertRaisesRegex(RuntimeError, "После восстановления"):
                self.dev.restore(deployment, self.dev.state, false_backup)
            self.assertIsNone(self.dev.database_identity)
            self.assertEqual(self.dev.restore(deployment, self.dev.state, backup),
                             {**fresh, "restored_from_pg_system_identifier": "123456"})

    def test_restore_rejects_live_dev_volume_before_any_resource_action(self):
        deployment = MagicMock()
        deployment.project = "cabinet-dev-restore-synthetic"
        deployment.environment = self.dev.compose_environment()
        self.dev.project = deployment.project
        with self.assertRaisesRegex(RuntimeError, "собственный том"):
            self.dev.restore(deployment, self.dev.state, {})
        deployment.compose.assert_not_called()


if __name__ == "__main__":
    unittest.main()
