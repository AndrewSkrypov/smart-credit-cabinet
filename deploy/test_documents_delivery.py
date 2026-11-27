"""Lightweight Documents delivery regression tests, without Docker or JVM."""
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import tarfile
from unittest.mock import Mock, patch
import os
import subprocess
import unittest

from documents_dev import (
    DocumentsDev, FILES, ROOT, SECRET_KEYS, compare_parity, object_requests, published_artifacts, published_documents, rpc_body,
    stable_digest, stable_gates, transition_gates, validate_intents,
)
from backup_support import DATABASES, backup_archives
from runtime_delivery_support import Descriptor, ensure_private, ensure_tls, load_secrets, read_record, write_record

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

    def test_readiness_receipt_requires_live_credit_probe_and_is_idempotent(self):
        deployment = self.mock_migration()
        self.dev.resume_attempt(deployment, self.request)
        current = self.dev.current()
        config = {"Config": {"Env": ["CABINET_DOCUMENTS_MODE=remote", "CABINET_DOCUMENTS_EPOCH=2"]}}
        path = self.dev.state / "ready" / f"{SHA}-2.json"
        self.dev.probe = Mock(side_effect=[TimeoutError, None, None])
        with (patch.object(Descriptor, "verify_image", return_value="sha256:" + SHA),
              patch("documents_dev.verify_container", side_effect=lambda _deployment, _service, image, **_: image),
              patch("documents_dev.inspect_one", return_value=config)):
            deployment.release.local_image_id.return_value = "sha256:" + "b" * 64
            with self.assertRaises(TimeoutError):
                self.dev.verify_credit(deployment)
            self.assertFalse(path.exists())
            receipt = self.dev.verify_credit(deployment)
            self.assertEqual(receipt, self.dev.verify_credit(deployment))
        self.dev.probe.assert_called_with(deployment, from_credit=True)
        self.assertEqual(current["receipt"], receipt["cutover_receipt"])
        self.assertEqual(receipt, read_record(path))
        self.assertEqual(0o600, path.stat().st_mode & 0o777)

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
        validate_intents([intent], [intent])
        for modified in ({**storage, "inputJson": raw + "\n"}, {**storage, "templateVersion": 3}):
            with self.assertRaises(RuntimeError):
                compare_parity([artifact], [modified], [], [])
        invalid = deepcopy(intent)
        invalid[-1] = None
        with self.assertRaises(RuntimeError):
            validate_intents([intent], [invalid])

    def test_artifact_parity_uses_committed_historical_references_without_a_delivery_intent(self):
        artifacts = []
        for value in (1, 2, 3):
            raw = f'{{"amount":{value}}}'
            artifacts.append({"id": str(value), "scope": {"applicationId": "app"}, "offerId": "offer",
                              "phase": "FINAL", "inputHash": sha256(raw.encode()).hexdigest(), "inputJson": raw})
        references = [["app", "offer", "FINAL", row["inputHash"], {"amount": value}]
                      for value, row in enumerate(artifacts[:2], start=1)]
        self.assertEqual(published_artifacts(artifacts, references), artifacts[:2])
        self.assertEqual(len(artifacts), 3)
        with self.assertRaisesRegex(RuntimeError, "Frozen input"):
            published_artifacts([{**artifacts[0], "inputJson": '{"amount":42}'}], references)

    def reverse_fixture(self, *, delivered=None):
        raw = '{"amount":25.00}'
        frozen = '{"amount": 25.00}'
        input_hash = sha256(raw.encode()).hexdigest()
        key = ["app", "offer", "FINAL", input_hash]
        intent = [*key, frozen, "2026-11-01T00:00:00+00:00", delivered, "S3", frozen]
        authority = {"documents": [], "artifacts": [[*key, json.loads(frozen)]], "deletions": []}
        originals = {"inputs": [intent], "authority": authority}
        tasks = [
            {"id": identifier, "scope": {"applicationId": "app", "companyId": "company"}, "offerId": "offer",
             "phase": "FINAL", "kind": kind, "templateVersion": 2, "inputHash": input_hash, "inputJson": frozen,
             "createdAt": intent[5], "backend": "S3", "status": "PENDING", "attempts": 0,
             "checksum": None, "sizeBytes": None, "readyAt": None, "contentType": content_type,
             "storedName": f"generated-{identifier}.{extension}"}
            for kind, identifier, extension, content_type in (
                ("CONTRACT_PDF", "2ee2e80d-2a6d-3907-a398-68f623803132", "pdf", "application/pdf"),
                ("SCHEDULE_PDF", "ce6a8de0-de85-3081-8e66-8cb3e80001e3", "pdf", "application/pdf"),
                ("SCHEDULE_XLSX", "4c3d0894-324c-3995-9d58-8ae0f4145821", "xlsx",
                 "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            )
        ]
        self.dev.credit_authority = Mock(return_value=authority)
        self.dev.credit_sql = Mock(side_effect=lambda *_: "\n".join(map(json.dumps, originals["inputs"])))
        self.dev.object_digests = Mock(return_value=([], []))
        return originals, tasks, raw

    def test_reverse_materializes_exact_unsent_tasks_without_object_bytes(self):
        original, tasks, _ = self.reverse_fixture()
        self.dev.records = Mock(return_value=([], tasks, [], []))
        report = self.dev.parity(object(), original, reversing=True)
        self.assertEqual(report["artifacts"], 0)
        self.assertEqual(report["credit_restored_pending"], {"count": 3, "sha256": stable_digest(tasks)})
        self.assertEqual(self.dev.object_digests.call_args.args[1:], ([], []))
        with self.assertRaisesRegex(RuntimeError, "Неполный набор"):
            self.dev.parity(object(), original)

    def test_reverse_materializes_only_missing_kinds_and_keeps_existing_metadata_and_bytes(self):
        original, tasks, raw = self.reverse_fixture()
        # An existing kind uses its original ID, creation timestamp and object checksum.
        local_ready = {**tasks[0], "id": "original-identifier", "status": "READY", "sizeBytes": 8,
                       "checksum": BYTE_SHA, "readyAt": "published", "createdAt": "original"}
        remote_ready = {**local_ready, "inputJson": raw}
        self.dev.object_digests.return_value = ([BYTE_SHA], [BYTE_SHA])
        self.dev.records = Mock(return_value=([], [local_ready, *tasks[1:]], [], [remote_ready]))
        report = self.dev.parity(object(), original, reversing=True)
        self.assertEqual(report["artifacts"], 1)
        self.assertEqual(report["credit_restored_pending"]["count"], 2)
        self.assertEqual(self.dev.object_digests.call_args.args[1:], ([{**local_ready, "inputJson": json.loads(raw)}], [remote_ready]))
        self.dev.records.return_value = ([], [{**local_ready, "createdAt": "changed"}, *tasks[1:]], [], [remote_ready])
        with self.assertRaisesRegex(RuntimeError, "Различаются metadata"):
            self.dev.parity(object(), original, reversing=True)
        self.dev.records.return_value = ([], [{**local_ready, "inputJson": '{"amount": 25.0}'}, *tasks[1:]], [], [remote_ready])
        with self.assertRaisesRegex(RuntimeError, "Различается frozen input"):
            self.dev.parity(object(), original, reversing=True)
        self.dev.records.return_value = ([], [local_ready, *tasks], [], [remote_ready])
        with self.assertRaisesRegex(RuntimeError, "Лишний артефакт"):
            self.dev.parity(object(), original, reversing=True)

    def test_reverse_rejects_missing_delivered_or_historical_artifacts(self):
        original, tasks, raw = self.reverse_fixture(delivered="delivered")
        self.dev.records = Mock(return_value=([], tasks, [], []))
        with self.assertRaisesRegex(RuntimeError, "Доставленная группа"):
            self.dev.parity(object(), original, reversing=True)
        self.dev.records.return_value = ([], tasks[1:], [], [{**task, "inputJson": raw} for task in tasks[1:]])
        with self.assertRaisesRegex(RuntimeError, "Доставленная группа"):
            self.dev.parity(object(), original, reversing=True)
        original["inputs"] = []
        for local in ([], tasks):
            self.dev.records.return_value = ([], local, [], [])
            with self.assertRaisesRegex(RuntimeError, "Исторический артефакт"):
                self.dev.parity(object(), original, reversing=True)

    def test_reverse_rejects_unexpected_or_invalid_local_tasks(self):
        original, tasks, _ = self.reverse_fixture()
        for changes in ({"phase": "PREVIEW"}, {"offerId": "other"}, {"scope": {"applicationId": "other"}},
                        {"inputHash": SHA}, {"inputJson": '{"amount": 25.0}'}, {"backend": "LOCAL"},
                        {"status": "READY"}, {"sizeBytes": 8}, {"checksum": BYTE_SHA}, {"readyAt": "now"},
                        {"templateVersion": 1}, {"kind": "SCHEDULE_PDF"}, {"id": "unrecognized"}):
            with self.subTest(changes=changes):
                self.dev.records = Mock(return_value=([], [{**tasks[0], **changes}, *tasks[1:]], [], []))
                with self.assertRaisesRegex(RuntimeError, "Локальная задача|Лишний артефакт"):
                    self.dev.parity(object(), original, reversing=True)
        for invalid in (tasks[1:], [*tasks, tasks[0]]):
            self.dev.records.return_value = ([], invalid, [], [])
            with self.assertRaisesRegex(RuntimeError, "отсутствуют локальные задачи|Лишний артефакт"):
                self.dev.parity(object(), original, reversing=True)
        original["inputs"][0][-1] = '{"amount": 25.0}'
        with self.assertRaisesRegex(RuntimeError, "подтверждённой ссылкой"):
            self.dev.parity(object(), original, reversing=True)

    def test_snapshot_probes_backends_from_credit_inputs_even_without_s3_files(self):
        original, _, _ = self.reverse_fixture()
        operation = {"requested_backend": "LOCAL", "operation_id": "upload"}
        self.dev.credit_storage_inputs = Mock(return_value={"artifacts": original["inputs"], "uploads": [operation]})
        self.dev.records = Mock(return_value=([], [], [], []))
        self.dev.sql = Mock(return_value="")
        self.dev.volume = Mock()
        with self.assertRaisesRegex(RuntimeError, "нужен профиль S3"):
            self.dev.store_snapshot(Mock())
        self.dev.volume.assert_not_called()
        self.dev.object_digests.assert_not_called()

        self.dev.has_s3 = True
        deployment = Mock(command=["compose"], environment={})
        deployment.config.return_value = {"services": {"credit": {"environment": {
            "STORAGE_BACKEND": "local", "S3_ENDPOINT": "http://seaweedfs:8333", "S3_BUCKET": "credit", "S3_REGION": "us-east-1"}}}}

        def volume_metadata(_, __, name):
            return {"Labels": {"com.docker.compose.project": "cabinet-dev", "com.docker.compose.volume": name}}

        with (patch("documents_dev.run") as run, patch("documents_dev.storage_volume", side_effect=lambda _, key: key),
              patch("documents_dev.inspect_one", side_effect=volume_metadata)):
            snapshot = self.dev.store_snapshot(deployment)
            self.assertEqual(snapshot["backends"], ["LOCAL", "S3"])
            self.assertEqual(run.call_args.kwargs["stdin"], "credit LOCAL\ncredit S3\ndocuments LOCAL\ndocuments S3\n")
            self.assertEqual(snapshot["credit_inputs_sha256"], stable_digest(self.dev.credit_storage_inputs.return_value))
            operation["operation_id"] = "different request"
            self.assertNotEqual(self.dev.store_snapshot(deployment)["credit_inputs_sha256"], snapshot["credit_inputs_sha256"])

    def test_prepare_blocks_unavailable_backend_before_documents_mutation(self):
        self.dev.preflight = Mock()
        self.dev.assert_writers_stopped = Mock()
        self.dev.prepare_schema = Mock()
        deployment = Mock()
        self.dev.credit_storage_inputs = Mock(return_value={"artifacts": [], "uploads": [{"requested_backend": "S3"}]})
        with (patch.object(Descriptor, "verify_image"), patch("documents_dev.storage_backend_counts", return_value={"LOCAL": 1}),
              self.assertRaisesRegex(RuntimeError, "нужен профиль S3")):
            self.dev.prepare(deployment, ("up",))
        deployment.compose.assert_not_called()
        self.dev.prepare_schema.assert_not_called()

    def test_common_archive_requires_shared_s3_even_when_only_documents_jobs_use_it(self):
        backup = {"format_version": 4, "backup_complete": True, "documents": {}, "runtimes": {"documents": "local"},
                  "databases": {database: {} for database in DATABASES}, "files": {},
                  "storage": {"s3_volume_included": False, "backends": {"LOCAL": 1}},
                  "documents_runtime": {"snapshot": {"backends": ["S3"]}}}
        with self.assertRaisesRegex(RuntimeError, "Нет копии S3 для файлов или заданий"):
            backup_archives(backup)

    def test_reverse_parity_requires_pending_delete_materialized_in_local_metadata(self):
        reference = ["doc", "app"]
        deleted_at = "2026-10-01T13:16:00+00:00"
        document = {"id": "doc", "scope": {"applicationId": "app"}, "deletedAt": None, "backend": "LOCAL",
                    "storedName": "sample.pdf", "sizeBytes": 8, "checksum": BYTE_SHA}
        pending = ["doc", "app", deleted_at, None]
        overlay = {**document, "deletedAt": deleted_at}
        self.assertEqual(published_documents([document], [reference], [pending]), [overlay])
        self.assertIsNone(document["deletedAt"])
        with self.assertRaisesRegex(RuntimeError, "metadata"):
            compare_parity([document], [overlay], [BYTE_SHA], [BYTE_SHA])
        self.assertEqual(compare_parity([overlay], [overlay], [BYTE_SHA], [BYTE_SHA]), [overlay])
        with self.assertRaisesRegex(RuntimeError, "Tombstone"):
            published_documents([document], [reference], [[*pending[:3], "delivered"]])
        with self.assertRaisesRegex(RuntimeError, "другой заявке"):
            published_documents([document], [reference], [["doc", "other", deleted_at, None]])

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

    def test_store_probe_uses_configured_bucket_before_any_file_exists(self):
        fake_curl = self.root / "curl"
        fake_curl.write_text('#!/bin/sh\ncat >/dev/null\nfor arg do url=$arg; done\nprintf "%s\\n" "$url"\nexit "$CURL_RESULT"\n')
        fake_curl.chmod(0o700)
        env = {**os.environ, "PATH": str(self.root) + ":" + os.environ["PATH"], "CURL_RESULT": "0",
               "DOCUMENTS_CREDIT_LOCAL_DIR": str(self.root), "DOCUMENTS_CREDIT_S3_ENDPOINT": "http://seaweedfs:8333",
               "DOCUMENTS_CREDIT_S3_BUCKET": "credit", "DOCUMENTS_CREDIT_S3_REGION": "us-east-1",
               "DOCUMENTS_CREDIT_S3_ACCESS_KEY": "fixture", "DOCUMENTS_CREDIT_S3_SECRET_KEY": "fixture"}
        command = ["/bin/sh", FILES / "object-digests.sh", "--check-stores"]
        result = subprocess.run(command, input="credit LOCAL\ncredit S3\n", env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "http://seaweedfs:8333/credit?list-type=2&max-keys=1&prefix=documents%2F")
        failure = subprocess.run(command, input="credit S3\n", env={**env, "CURL_RESULT": "22"}, text=True, capture_output=True)
        self.assertNotEqual(failure.returncode, 0)

    def test_rpc_frame_encodes_large_epoch(self):
        for number, expected in ((1, b"\x01"), (128, b"\x80\x01"), (16384, b"\x80\x80\x01")):
            body = rpc_body(number)
            self.assertEqual(len(body) - 5, int.from_bytes(body[1:5], "big"))
            self.assertTrue(body.endswith(b"\x18" + expected))
        self.assertEqual(78, len(rpc_body(1)) - 5)

    def test_published_document_membership_uses_credit_reference_scope(self):
        refers_to_credit = {"id": "published", "scope": {"applicationId": "app-a"}}
        rpc_orphan = {"id": "orphan", "scope": {"applicationId": "app-a"}}
        source = [refers_to_credit, rpc_orphan]
        self.assertEqual(published_documents(source, [("published", "app-a")]), [refers_to_credit])
        self.assertEqual(source, [refers_to_credit, rpc_orphan])
        with self.assertRaisesRegex(RuntimeError, "другой заявке"):
            published_documents(source, [("published", "app-b")])

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

    def test_restore_stages_exact_ca_and_credentials_from_verified_backup(self):
        state = self.dev.state
        tls = ensure_tls(state, service="documents", client_uri="spiffe://credit-cabinet/credit")
        credentials = state / "credentials.env"
        load_secrets(credentials, SECRET_KEYS)
        identity = {**IDENTITY, "ca_sha256": sha256((tls / "ca.crt").read_bytes()).hexdigest(),
                    "credentials_sha256": sha256(credentials.read_bytes()).hexdigest()}
        write_record(state / "identity.json", identity)
        ready_path = Path("ready") / f"{SHA}-1.json"
        ensure_private(state / "ready")
        write_record(state / ready_path, {"epoch": 1}, immutable=True)
        archive = self.root / "backup"
        archive.mkdir(mode=0o700)
        paths = {"db": "documents-runtime.dump", "local": "documents-runtime-local.tar.gz",
                 "state": "documents-runtime-state.tar.gz"}
        with self.dev.private_file(archive / paths["db"]) as output:
            output.write(b"dummy-dump")
        with self.dev.private_file(archive / paths["local"]) as output:
            with tarfile.open(fileobj=output, mode="w:gz"):
                pass
        with self.dev.private_file(archive / paths["state"]) as output:
            with tarfile.open(fileobj=output, mode="w:gz") as packaged:
                for path in state.rglob("*"):
                    if path.is_file():
                        packaged.add(path, arcname=str(path.relative_to(state)))
        backup = {"format": 1, "identity": identity, "current": self.dev.current(), "snapshot": {},
                  "volumes": {"local": {}}, "paths": paths,
                  "files": {path: self.dev.file_stamp(archive / path) for path in paths.values()}}
        target = self.root / "restore"
        DocumentsDev.stage_restore(target, archive, backup)
        self.assertEqual(identity, read_record(target / "identity.json"))
        self.assertEqual({"epoch": 1}, read_record(target / ready_path))
        self.assertTrue((tls / "ca.crt").read_bytes() == (target / "tls" / "ca.crt").read_bytes())
        self.assertTrue(credentials.read_bytes() == (target / "credentials.env").read_bytes())
        self.assertEqual(0o600, (target / "credentials.env").stat().st_mode & 0o777)
        with (archive / paths["db"]).open("ab") as stream:
            stream.write(b"changed")
        with self.assertRaisesRegex(RuntimeError, "SHA-256"):
            DocumentsDev.verify_backup(archive, backup)

    def test_request_objects_rejects_traversal_and_wrong_sizes(self):
        known = {"storedName": "generated-1.pdf", "sizeBytes": 8, "backend": "S3"}
        self.assertEqual(["documents S3 generated-1.pdf 8\n"], list(object_requests([known], "documents")))
        for key, value in (("storedName", "../file"), ("sizeBytes", True), ("backend", "FILE")):
            with self.assertRaises(ValueError):
                list(object_requests([{**known, key: value}], "documents"))


if __name__ == "__main__":
    unittest.main()
