"""Lightweight manifest, certificate and interrupted transfer checks."""
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch
from uuid import UUID

from backup_support import DATABASES, backup_archives
from catalog import SECRET_KEYS, append_secrets, ensure_private, ensure_tls, probe_mtls
from catalog_rollout import Operator, reconcile, store
from registry_release import IMAGE_COMPONENTS, RegistryRelease

REVISION = "a" * 40
SHA = "b" * 64
REPOSITORY = "registry.example.invalid/project"
CHECKSUMS = f"{'c' * 64}  products.csv\n{'d' * 64}  product_versions.csv\n"


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.file = Path(self.temp.name) / "release.json"
        self.release = {
            "revision": REVISION,
            "images": {name: f"{REPOSITORY}/{name}@sha256:{SHA}" for name in IMAGE_COMPONENTS},
        }

    def read(self, **options):
        self.file.write_text(json.dumps(self.release))
        return RegistryRelease(self.file, **options)

    def test_old_release_and_alpha(self):
        self.assertIsNone(self.read().catalog)
        self.release["alpha_dev"] = {
            "image": f"{REPOSITORY}/analytics@sha256:{SHA}",
            "jar_sha256": SHA,
            "kafka_image": f"{REPOSITORY}/kafka@sha256:{SHA}",
        }
        self.assertFalse(self.read().legacy_alpha)
        self.release["alpha_dev"]["kafka_image"] = f"apache/kafka:3.8.1@sha256:{SHA}"
        self.assertTrue(self.read(allow_legacy_alpha=True).legacy_alpha)

    def test_new_catalog_requires_complete_provenance(self):
        self.release["catalog"] = {"jar_sha256": SHA, "dev_mode": "disabled"}
        with self.assertRaises(ValueError):
            self.read()
        image = f"{REPOSITORY}/catalog-grpc@sha256:{SHA}"
        self.release["images"]["catalog-grpc"] = image
        self.assertEqual(self.read().environment()["CABINET_IMAGE_CATALOG_GRPC"], image)
        for mode in ("local", "remote"):
            self.release["catalog"]["dev_mode"] = mode
            self.assertEqual(self.read().catalog["dev_mode"], mode)
        self.release["catalog"]["dev_mode"] = "unknown"
        with self.assertRaises(ValueError):
            self.read()

    def test_duplicate_key_rejected(self):
        self.file.write_text('{"revision":"1","revision":"2"}')
        with self.assertRaisesRegex(ValueError, "Повторяющийся"):
            RegistryRelease(self.file)


class TransferTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name)
        self.transfers = ensure_private(self.state / "transfers")
        self.journal = self.state / "authority.json"
        self.release = SimpleNamespace(revision=REVISION, checksum=SHA)
        self.deployment = SimpleNamespace(catalog_mode="remote", release=self.release)
        self.flags = {"credit": True, "catalog": False}
        self.operator = MagicMock(spec=Operator)
        self.snapshot = MagicMock()
        self.snapshot.is_writable.side_effect = lambda _, database: self.flags[database]
        self.snapshot.checked_manifest.return_value = CHECKSUMS
        self.snapshot.switch.side_effect = self.complete

    def complete(self, _credit, _catalog, directory, target):
        directory.mkdir(0o700)
        self.flags = {"credit": target == "credit", "catalog": target == "catalog"}

    def reconcile(self):
        reconcile(self.deployment, self.operator, self.snapshot, self.journal)

    def test_forward_retry_and_reverse_keep_both_receipts(self):
        self.reconcile()
        self.reconcile()
        self.assertEqual(self.snapshot.switch.call_count, 1)
        self.deployment.catalog_mode = "local"
        self.release.checksum = "e" * 64
        self.reconcile()
        self.assertEqual(self.flags, {"credit": True, "catalog": False})
        self.assertEqual(len(list((self.state / "receipts").glob("*.json"))), 2)
        self.operator.assert_inactive_writer.assert_called_with("catalog")

    def test_interruption_after_target_opens_resumes_by_verification(self):
        def interrupted(*args):
            self.complete(*args)
            raise RuntimeError("simulated interruption")
        self.snapshot.switch.side_effect = interrupted
        with self.assertRaisesRegex(RuntimeError, "interruption"):
            self.reconcile()
        self.assertEqual(json.loads(self.journal.read_text())["pending"]["target"], "catalog")
        self.reconcile()
        self.snapshot.switch.assert_called_once()
        self.assertEqual(json.loads(self.journal.read_text())["active"], "catalog")
        self.snapshot.verify.assert_any_call("catalog-owner", "catalog", CHECKSUMS)
        self.snapshot.verify.assert_any_call("credit-owner", "credit", CHECKSUMS)

    def test_reused_remote_manifest_records_new_history_after_activation_failure(self):
        versions = {"credit": [1], "catalog": []}

        def checksums(database):
            history = json.dumps(versions[database]).encode("ascii")
            return f"{'c' * 64}  products.csv\n{sha256(history).hexdigest()}  product_versions.csv\n"

        def transfer(credit, catalog, directory, target):
            source = "catalog" if target == "credit" else "credit"
            versions[target] = versions[source].copy()
            self.complete(credit, catalog, directory, target)
            (directory / "SHA256SUMS").write_text(checksums(source))

        def verify(service, database, expected):
            self.assertEqual(service, database + "-owner")
            self.assertEqual(checksums(database), expected)

        self.snapshot.switch.side_effect = transfer
        self.snapshot.checked_manifest.side_effect = lambda directory: (directory / "SHA256SUMS").read_text()
        self.snapshot.verify.side_effect = verify
        self.reconcile()  # Remote A.
        old = json.loads(self.journal.read_text())["last_transfer"]
        original, = (self.state / "receipts").glob("*.json")
        old_receipt = original.read_bytes()

        self.assertTrue(self.flags["catalog"])
        versions["catalog"].append(2)
        self.deployment.catalog_mode = "local"
        self.release.revision = "2" * 40
        self.release.checksum = "e" * 64
        self.reconcile()  # Local B returns the Catalog-only version to Credit.
        self.assertTrue(self.flags["credit"])
        self.assertEqual(versions["credit"], [1, 2])
        versions["credit"].append(3)

        self.deployment.catalog_mode = "remote"
        self.release.revision = REVISION
        self.release.checksum = SHA

        def fail_after_activation(*args):
            transfer(*args)
            raise RuntimeError("interrupted after activation")

        self.snapshot.switch.side_effect = fail_after_activation
        with self.assertRaisesRegex(RuntimeError, "after activation"):
            self.reconcile()
        pending_id = json.loads(self.journal.read_text())["pending"]["snapshot"]
        self.assertEqual(self.flags, {"credit": False, "catalog": True})
        self.reconcile()  # The same release finishes without a second native switch.
        journal = json.loads(self.journal.read_text())
        latest = journal["last_transfer"]
        self.assertNotIn("pending", journal)
        self.assertEqual(versions, {"credit": [1, 2, 3], "catalog": [1, 2, 3]})
        self.assertEqual(self.snapshot.switch.call_count, 3)
        self.assertEqual(latest["transfer_id"], pending_id)
        self.assertEqual(latest["manifest_sha256"], old["manifest_sha256"])
        self.assertNotEqual(latest["transfer_id"], old["transfer_id"])
        self.assertNotEqual(latest["history_sha256"], old["history_sha256"])
        self.assertEqual(original.read_bytes(), old_receipt)
        self.assertEqual(len(list(original.parent.glob("*.json"))), 3)

    def test_resume_after_immutable_receipt_was_already_written(self):
        def interrupted(path, value, *, immutable=False):
            store(path, value, immutable=immutable)
            if immutable:
                raise RuntimeError("interrupted after receipt")

        with patch("catalog_rollout.store", side_effect=interrupted):
            with self.assertRaisesRegex(RuntimeError, "after receipt"):
                self.reconcile()
        receipt, = (self.state / "receipts").glob("*.json")
        contents = receipt.read_bytes()
        inode = receipt.stat().st_ino
        self.reconcile()
        self.snapshot.switch.assert_called_once()
        self.assertNotIn("pending", json.loads(self.journal.read_text()))
        self.assertEqual(receipt.read_bytes(), contents)
        self.assertEqual(receipt.stat().st_ino, inode)

    def test_receipt_collision_fails_before_switching_writers(self):
        transfer_id = UUID(hex="a" * 32)
        receipts = ensure_private(self.state / "receipts")
        occupied = receipts / f"transfer-{transfer_id.hex}.json"
        occupied.write_text("{}")
        with patch("catalog_rollout.uuid4", return_value=transfer_id):
            with self.assertRaisesRegex(RuntimeError, "имя новой попытки уже занято"):
                self.reconcile()
        self.snapshot.switch.assert_not_called()
        self.assertEqual(self.flags, {"credit": True, "catalog": False})
        self.assertFalse(self.journal.exists())
        self.assertEqual(occupied.read_text(), "{}")

    def test_interruption_with_both_fenced_retries_same_manifest(self):
        def interrupted(*_):
            self.flags = {"credit": False, "catalog": False}
            raise RuntimeError("simulated interruption")
        self.snapshot.switch.side_effect = interrupted
        with self.assertRaisesRegex(RuntimeError, "interruption"):
            self.reconcile()
        self.release.checksum = "e" * 64
        with self.assertRaisesRegex(RuntimeError, "manifest"):
            self.reconcile()
        self.release.checksum = SHA
        self.snapshot.switch.side_effect = self.complete
        self.reconcile()
        self.assertEqual(self.flags, {"credit": False, "catalog": True})

    def test_both_active_rejected(self):
        self.flags["catalog"] = True
        with self.assertRaisesRegex(RuntimeError, "оба писателя"):
            self.reconcile()
        self.snapshot.switch.assert_not_called()

    def test_restore_archive_requires_both_database_dumps(self):
        catalog_databases = (*DATABASES, "catalog")
        manifest = {
            "backup_complete": True,
            "format_version": 3,
            "documents": {},
            "storage": {"s3_volume_included": False, "backends": {}},
            "databases": {db: {} for db in catalog_databases},
            "files": {**{db + ".dump": {} for db in catalog_databases}, "documents.tar.gz": {}},
            "catalog": {"mode": "remote", "release_manifest_sha256": SHA, "authority": {"active": "catalog"}},
        }
        manifest["databases"]["credit"]["catalog_writable"] = "f"
        manifest["databases"]["catalog"]["catalog_writable"] = "t"
        self.assertEqual(set(backup_archives(manifest)), {"documents.tar.gz"})
        del manifest["files"]["catalog.dump"]
        with self.assertRaises(AssertionError):
            backup_archives(manifest)


class MtlsProbeTest(unittest.TestCase):
    @staticmethod
    def curl(code, body):
        marker = f"\n__CATALOG_CURL_EXIT__={code}\n".encode("ascii")
        return subprocess.CompletedProcess([], 0, body + marker, b"")

    def probe(self, negative):
        current = self.curl(0, b"HTTP/2 200\r\ngrpc-status: 0\r\n")
        history = self.curl(0, b"HTTP/2 200\r\ngrpc-status: 5\r\n")
        with patch("catalog.subprocess.run", side_effect=[current, history, negative]) as run:
            probe_mtls(["kubectl", "exec", "-i", "credit", "--"], "remote")
            self.assertIn("--cert", run.call_args_list[1].args[0])
            self.assertNotIn("--cert", run.call_args_list[2].args[0])

    def test_remote_certificate_required_or_grpc_denial_is_positive_evidence(self):
        self.probe(self.curl(56, b"curl: (56) OpenSSL SSL_read: tlsv13 alert certificate required\n"))
        self.probe(self.curl(0, b"HTTP/2 200\r\ngrpc-status: 7\r\n"))

    def test_curl_timeout_and_generic_tls_failure_are_inconclusive(self):
        for code, message in ((28, b"curl: (28) Operation timed out"),
                              (56, b"curl: (56) Recv failure: Connection reset by peer")):
            with self.subTest(code=code), self.assertRaisesRegex(RuntimeError, "не подтверждён отказ"):
                self.probe(self.curl(code, message))

    def test_exec_failure_and_missing_curl_marker_are_inconclusive(self):
        alert = b"curl: (56) tlsv13 alert certificate required"
        completed = subprocess.CompletedProcess
        for result in (
            completed([], 1, b"", alert),
            completed([], 0, alert, b""),
            completed([], 1, alert + b"\n__CATALOG_CURL_EXIT__=56\n", alert),
        ):
            with self.subTest(result=result), self.assertRaisesRegex(RuntimeError, "выполнение curl"):
                self.probe(result)

    def test_exec_transport_timeout_is_inconclusive(self):
        with self.assertRaisesRegex(RuntimeError, "транспорт проверки"):
            self.probe(subprocess.TimeoutExpired(["kubectl"], 12))


class CredentialTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL required")
    def test_bootstrap_keeps_credentials_on_resume(self):
        lines = append_secrets(b"EXISTING=value\n").decode().splitlines()
        values = dict(line.split("=", 1) for line in lines)
        self.assertEqual(values["EXISTING"], "value")
        self.assertNotEqual(*(values[key] for key in SECRET_KEYS))
        with TemporaryDirectory() as directory:
            state = Path(directory) / "catalog"
            ensure_tls(state)
            identity = (state / "tls/credit/tls.crt").read_bytes()
            ensure_tls(state)
            self.assertEqual(identity, (state / "tls/credit/tls.crt").read_bytes())
            self.assertEqual(state.stat().st_mode & 0o777, 0o700)
            self.assertEqual((state / "tls").stat().st_mode & 0o777, 0o700)
            self.assertEqual({path.name for path in (state / "tls").iterdir()}, {"server", "credit"})
            details = subprocess.check_output(
                ["openssl", "x509", "-noout", "-ext", "subjectAltName", "-in", state / "tls/credit/tls.crt"],
                text=True,
            )
            self.assertIn("URI:spiffe://creditcabinet/credit", details)


if __name__ == "__main__":
    unittest.main()
