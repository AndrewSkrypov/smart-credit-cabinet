"""Lightweight Documents delivery regression tests, without Docker or JVM."""
from copy import deepcopy
from hashlib import sha256
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
from unittest.mock import Mock, patch
import os
import subprocess
import unittest

from documents_dev import (
    DocumentsDev, FILES, ROOT, SECRET_KEYS, compare_parity, object_requests, rpc_body,
    stable_digest, stable_gates, transition_gates, validate_intents,
)
from runtime_delivery_support import Descriptor, ensure_private, read_record, write_record

SHA = "a" * 64
REVISION = "1" * 40
IMAGE = "registry.test/cabinet/documents@sha256:" + SHA
BYTE_SHA = sha256(b"contract").hexdigest()
IDENTITY = {"generation": "g" + "1" * 16, "source_storage": "local", "has_s3": False,
            "ca_sha256": SHA, "credentials_sha256": SHA}


class DocumentsDeliveryTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        state = ensure_private(self.root / "state")
        self.dev = DocumentsDev(
            Descriptor(REVISION, SHA, "remote", IMAGE, SHA, "documents"), state,
            "cabinet-dev", "local", False, IDENTITY, {}, state / "tls",
        )
        self.request = {"attempt": "a" * 32, "from_mode": "local", "to_mode": "remote", "epoch": 1,
                        "backup_sha256": SHA, "revision": REVISION, "manifest_sha256": SHA}
        self.db_gates = stable_gates("local", 1)

    def mock_migration(self):
        deployment = Mock()
        self.dev.assert_writers_stopped = Mock()
        self.dev.gates = Mock(side_effect=lambda _: self.db_gates)
        self.dev.credit_sql = Mock(return_value="")
        self.dev.sql = Mock(return_value="")
        self.dev.migration = Mock()
        self.dev.parity = Mock(return_value={"metadata_and_bytes_sha256": SHA})

        def move(_, side, before, after):
            index = 0 if side == "credit" else 1
            assert self.db_gates[index] == before
            gates = list(self.db_gates)
            gates[index] = after
            self.db_gates = tuple(gates)

        self.dev.move_gate = Mock(side_effect=move)
        return deployment

    def test_forward_resume_does_not_replay_after_one_activation(self):
        deployment = self.mock_migration()
        pair = transition_gates("local", 1)
        move = self.dev.move_gate.side_effect

        def crash_after_credit_switch(*args):
            move(*args)
            if self.db_gates == pair[3]:
                raise InterruptedError

        self.dev.move_gate.side_effect = crash_after_credit_switch
        with self.assertRaises(InterruptedError):
            self.dev.resume_attempt(deployment, self.request)
        self.assertEqual(pair[3], self.db_gates)
        self.assertIsNotNone(read_record(self.dev.state / "attempts" / self.request["attempt"] / "verified.json"))
        self.dev.move_gate.side_effect = move
        self.dev.resume_attempt(deployment, self.request)
        self.dev.migration.assert_called_once()
        self.dev.parity.assert_called_once()
        self.assertEqual(pair[-1], self.db_gates)
        current = self.dev.current()
        self.assertEqual(("remote", 2), (current["mode"], current["epoch"]))
        receipt = read_record(self.dev.state / "receipts" / (current["receipt"] + ".json"))
        self.assertEqual(stable_digest(self.request), receipt["request"])

    def test_crash_during_copy_keeps_both_frozen_and_retry_runs_migration(self):
        deployment = self.mock_migration()
        self.dev.migration.side_effect = [InterruptedError, None]
        with self.assertRaises(InterruptedError):
            self.dev.resume_attempt(deployment, self.request)
        self.assertEqual((("FROZEN", 2), ("FROZEN", 2)), self.db_gates)
        self.assertEqual("local", self.dev.current()["mode"])
        self.dev.resume_attempt(deployment, self.request)
        self.assertEqual(2, self.dev.migration.call_count)
        self.assertEqual((("REMOTE", 2), ("DOCUMENTS", 2)), self.db_gates)

    def test_partial_activation_without_receipt_fails_closed(self):
        deployment = self.mock_migration()
        self.db_gates = transition_gates("local", 1)[3]
        with self.assertRaisesRegex(RuntimeError, "Нет квитанции"):
            self.dev.resume_attempt(deployment, self.request)
        self.dev.migration.assert_not_called()
        self.dev.move_gate.assert_not_called()

    def test_reverse_starts_frozen_and_new_forward_attempt_advances_epoch(self):
        for mode, other in (("local", "remote"), ("remote", "local")):
            sequence = transition_gates(mode, 2)
            self.assertEqual(stable_gates(mode, 2), sequence[0])
            self.assertEqual((("FROZEN", 3), ("FROZEN", 3)), sequence[2])
            self.assertEqual(stable_gates(other, 3), sequence[4])
            self.assertFalse(any(a[0] == "LOCAL" and b[0] == "DOCUMENTS" for a, b in sequence))
        self.assertNotEqual(
            stable_digest(self.request),
            stable_digest({**self.request, "attempt": "b" * 32, "epoch": 3}),
        )

    def test_exact_content_and_deleted_metadata(self):
        base = {
            "id": "doc", "backend": "LOCAL", "sizeBytes": 8, "storedName": "sample.pdf",
            "checksum": BYTE_SHA, "context": "CHAT", "deletedAt": "2026-10-01T13:16:00+00:00",
        }
        absent_checksum = {**base, "checksum": None}
        self.assertEqual([base], compare_parity([absent_checksum], [base], [BYTE_SHA], [BYTE_SHA]))
        for modified in ({**base, "deletedAt": None}, {**base, "backend": "S3"}, {**base, "privateToBank": True}):
            with self.assertRaises(RuntimeError):
                compare_parity([base], [modified], [BYTE_SHA], [BYTE_SHA])
        with self.assertRaises(RuntimeError):
            compare_parity([base], [base], [BYTE_SHA], ["0" * 64])
        with self.assertRaises(RuntimeError):
            compare_parity([base], [base, base], [BYTE_SHA], [BYTE_SHA, BYTE_SHA])

    def test_prepared_artifact_exact_input_and_intent(self):
        raw = '{"amount":25,"terms":{"rule":"fixed"}}'
        artifact = {
            "id": "prepared", "scope": {"applicationId": "app", "companyId": "company"},
            "status": "PENDING", "inputJson": {"amount": 25, "terms": {"rule": "fixed"}},
            "inputHash": sha256(raw.encode()).hexdigest(), "offerId": "offer", "phase": "FINAL",
            "sizeBytes": None, "checksum": None, "templateVersion": 2,
        }
        storage = {**artifact, "inputJson": raw}
        self.assertEqual([artifact], compare_parity([artifact], [storage], [], []))
        intent = ["app", "offer", "FINAL", artifact["inputHash"], artifact["inputJson"], "created", "delivered"]
        validate_intents([intent], [intent], [artifact])
        for modified in ({**storage, "inputJson": raw + "\n"}, {**storage, "templateVersion": 3}):
            with self.assertRaises(RuntimeError):
                compare_parity([artifact], [modified], [], [])
        invalid = deepcopy(intent)
        invalid[-1] = None
        with self.assertRaises(RuntimeError):
            validate_intents([intent], [invalid], [artifact])
        with self.assertRaises(RuntimeError):
            validate_intents([], [invalid], [artifact])

    def test_local_object_script_validates_path_size_and_digest(self):
        (self.root / "sample.pdf").write_bytes(b"contract")
        env = {**os.environ, "CABINET_STORAGE_LOCAL_DIR": str(self.root)}
        for side in ("CABINET_STORAGE", "DOCUMENTS_CREDIT"):
            for suffix in ("ENDPOINT", "REGION", "BUCKET", "ACCESS_KEY", "SECRET_KEY"):
                env[f"{side}_S3_{suffix}"] = ""
        command = ["/bin/sh", FILES / "object-digests.sh"]
        success = subprocess.run(command, input="documents LOCAL sample.pdf 8\n", env=env, text=True, capture_output=True)
        self.assertEqual((0, BYTE_SHA), (success.returncode, success.stdout.strip()))
        (self.root / "link.pdf").symlink_to(self.root / "sample.pdf")
        for payload in ("documents LOCAL sample.pdf 7\n", "documents LOCAL ../sample.pdf 8\n", "documents LOCAL link.pdf 8\n"):
            result = subprocess.run(command, input=payload, env=env, text=True, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", result.stdout)

    def test_rpc_frame_encodes_large_epoch(self):
        for number, expected in ((1, b"\x01"), (128, b"\x80\x01"), (16384, b"\x80\x80\x01")):
            body = rpc_body(number)
            self.assertEqual(len(body) - 5, int.from_bytes(body[1:5], "big"))
            self.assertTrue(body.endswith(b"\x18" + expected))
        self.assertEqual(78, len(rpc_body(1)) - 5)

    def test_chart_schema_and_grants_match_runtime_source(self):
        source = ROOT / "services/documents"
        for name, path in (
            ("schema.sql", source / "src/main/resources/db/migration/V1__documents.sql"),
            ("grants.sql", source / "cutover/grants.sql"),
            ("credit-grants.sql", source / "cutover/credit-grants.sql"),
        ):
            self.assertEqual(path.read_bytes(), (FILES / name).read_bytes())
        self.assertIn("CHECK(singleton)", (FILES / "fence-documents.sql").read_text())
        self.assertIn("grant update(singleton)", (FILES / "fence-documents.sql").read_text())
        self.assertIn("security definer set search_path = pg_catalog, public, pg_temp", (FILES / "fence-credit.sql").read_text())

    def test_enrollment_preserves_credentials_and_uses_local_interpolation(self):
        metadata = {"jar_sha256": SHA, "dev_mode": "local"}
        release = SimpleNamespace(documents=metadata, images={"documents": IMAGE, "credit": IMAGE.replace("/documents@", "/credit@")},
                                  revision=REVISION, checksum=SHA)
        deployment = SimpleNamespace(local=False, project="cabinet-dev", path=self.root / "env", release=release,
                                     profiles={"prod"}, values={"STORAGE_BACKEND": "local"})
        def fake_tls(path, **_):
            tls = ensure_private(path / "tls")
            ca = tls / "ca.crt"
            if not ca.exists():
                ca.write_text("test CA")
            return tls

        with patch("documents_dev.ensure_tls", side_effect=fake_tls):
            first = DocumentsDev.open(deployment)
            second = DocumentsDev.open(deployment)
            self.assertEqual(first.identity, second.identity)
            self.assertEqual(first.credentials, second.credentials)
            self.assertEqual(set(SECRET_KEYS), set(first.credentials))
            self.assertEqual(0o600, (first.state / "credentials.env").stat().st_mode & 0o777)
            self.assertEqual("local", first.compose_environment()["CABINET_DOCUMENTS_MODE"])
            deployment.release = SimpleNamespace(images={})
            with self.assertRaises(RuntimeError):
                DocumentsDev.open(deployment)

    def test_request_objects_rejects_traversal_and_wrong_sizes(self):
        known = {"storedName": "generated-1.pdf", "sizeBytes": 8, "backend": "S3"}
        self.assertEqual(["documents S3 generated-1.pdf 8\n"], list(object_requests([known], "documents")))
        for key, value in (("storedName", "../file"), ("sizeBytes", True), ("backend", "FILE")):
            with self.assertRaises(ValueError):
                list(object_requests([{**known, key: value}], "documents"))


if __name__ == "__main__":
    unittest.main()
