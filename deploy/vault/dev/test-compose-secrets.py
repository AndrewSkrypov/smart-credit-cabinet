#!/usr/bin/env python3
"""Synthetic input and boundary tests for the standalone Dev Vault adapter."""
import argparse
import fcntl
import importlib.util
import os
from pathlib import Path
import stat
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location("compose_secrets", Path(__file__).with_name("compose-secrets.py"))
adapter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapter)


def secret_map():
    return {key: f"synthetic_test_{index}_" + "x" * 48 for index, key in enumerate(sorted(adapter.REQUIRED))}


class DevEnvTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.state = Path(temporary.name)
        self.path = self.state / "cabinet.env"
        self.path.touch(mode=0o600)
        self.token = self.state / "test-token"
        self.token.touch(mode=0o600)
        self.token.write_text("example.synthetictoken\n")
        self.settings = (
            b"# arbitrary preserved comment\r\n"
            b"CABINET_DOMAIN=dev.credit-cabinet.ru\r\n"
            b"PUBLIC_CABINET_URL=https://dev.credit-cabinet.ru\r\n"
            b"CABINET_ALPHA_DEV_GENERATION=synthetic_only\r\n"
            b"CABINET_ALPHA_DEV_READ_TOKEN=synthetic_alpha_ignored\r\n"
            b"CABINET_ALPHA_DEV_ENABLED=false\r\n"
        )
        self.values = secret_map()
        self.before = self.settings + self.format_secrets(self.values)
        self.path.write_bytes(self.before)
        self.vault = Mock()
        self.vault.read.return_value = (self.values, 1)
        self.vault.seed.return_value = 1
        patcher = patch.object(adapter, "Vault", return_value=self.vault)
        patcher.start()
        self.addCleanup(patcher.stop)

    def format_secrets(self, values):
        return "".join(f"{key}={value}\r\n" for key, value in values.items()).encode()

    def call(self, operation, **arguments):
        args = dict(
            operation=operation,
            vault_origin="https://vault.test",
            connect_ip=None,
            ca_file=self.state / "unused-test-ca",
            token_file=self.token,
            include_service_smtp=False,
        )
        return adapter.execute(argparse.Namespace(**(args | arguments)), self.state)

    def backup_paths(self):
        return list(self.state.glob("vault-rollback-*.json"))

    def test_seed_excludes_alpha_and_unknown_fields_also_during_alpha(self):
        with self.path.open("ab") as stream:
            stream.write(b"SMTP_USERNAME=synthetic_mail_user\r\nSMTP_PASSWORD=synthetic_mail_secret\r\n"
                         b"CABINET_GRAFANA_HTTPS=true\r\nGRAFANA_PASSWORD=synthetic_grafana_login\r\n")
        raw = self.path.read_bytes().replace(b"DEV_ENABLED=false", b"DEV_ENABLED=true")
        self.path.write_bytes(raw)
        self.call("seed")
        self.vault.seed.assert_called_once_with(self.values)
        self.assertEqual(self.path.read_bytes(), raw)
        self.assertEqual(self.backup_paths(), [])
        self.call("seed", include_service_smtp=True)
        self.assertEqual(set(self.vault.seed.call_args.args[0]) - self.values.keys(), adapter.SMTP_KEYS)

    def test_equal_apply_is_read_only_even_if_alpha_active(self):
        raw = self.before.replace(b"DEV_ENABLED=false", b"DEV_ENABLED=true")
        self.path.write_bytes(raw)
        initial = self.path.stat()
        self.assertIn("parity", self.call("apply"))
        after = self.path.stat()
        self.assertEqual((initial.st_ino, initial.st_mtime_ns), (after.st_ino, after.st_mtime_ns))
        self.assertEqual(self.path.read_bytes(), raw)
        self.assertEqual(self.backup_paths(), [])

    def test_verify_uses_existing_lock_and_leaves_active_alpha_and_pending_untouched(self):
        self.path.write_bytes(self.before.replace(b"DEV_ENABLED=false", b"DEV_ENABLED=true"))
        pending = self.state / "pending.json"
        pending.write_bytes(b"synthetic-state")
        lock = self.state / "deploy.lock"
        lock.touch(mode=0o600)
        files = (self.path, pending, lock)
        before = [(file.stat().st_mtime_ns, file.read_bytes()) for file in files]
        self.assertIn("dry-run passed", self.call("verify"))
        self.assertEqual(before, [(file.stat().st_mtime_ns, file.read_bytes()) for file in files])
        self.assertEqual(self.backup_paths(), [])
        self.vault.seed.assert_not_called()

    def test_verify_refuses_busy_or_missing_lock_without_touching_vault(self):
        lock = self.state / "deploy.lock"
        with self.assertRaises(FileNotFoundError):
            self.call("verify")
        self.assertFalse(lock.exists())
        lock.touch(mode=0o600)
        with lock.open("rb") as writer:
            fcntl.flock(writer, fcntl.LOCK_EX)
            with self.assertRaisesRegex(adapter.MigrationError, "lock is busy"):
                self.call("verify")
        self.vault.read.assert_not_called()

    def test_verify_refuses_recovery_without_creating_snapshot_or_changing_env(self):
        (self.state / "deploy.lock").touch(mode=0o600)
        self.path.write_bytes(self.settings)
        with self.assertRaisesRegex(adapter.MigrationError, "missing Vault fields"):
            self.call("verify")
        self.assertEqual(self.path.read_bytes(), self.settings)
        self.assertEqual(self.backup_paths(), [])

    def test_restore_missing_preserves_original_bytes_and_allows_rollback(self):
        missing_key = "JWT_SECRET"
        before = self.settings + self.format_secrets({k: v for k, v in self.values.items() if k != missing_key})
        self.path.write_bytes(before)
        owner = (self.path.stat().st_uid, self.path.stat().st_gid)
        output = self.call("apply")
        self.assertEqual(self.path.read_bytes(), before + self.format_secrets({missing_key: self.values[missing_key]}))
        backups = self.backup_paths()
        self.assertEqual(len(backups), 1)
        self.assertIn(backups[0].name, output)
        for saved in (self.path, backups[0]):
            self.assertEqual(stat.S_IMODE(saved.stat().st_mode), 0o600)
            self.assertEqual((saved.stat().st_uid, saved.stat().st_gid), owner)
        self.call("rollback", backup=backups[0].name)
        self.assertEqual(self.path.read_bytes(), before)

    def test_rollback_refuses_to_overwrite_external_change(self):
        self.path.write_bytes(self.settings)
        self.call("apply")
        with self.path.open("ab") as stream:
            stream.write(b"# operator comment\r\n")
        raw = self.path.read_bytes()
        with self.assertRaisesRegex(adapter.MigrationError, "changed since"):
            self.call("rollback", backup=self.backup_paths()[0].name)
        self.assertEqual(self.path.read_bytes(), raw)

    def test_no_write_on_divergence_or_incomplete_unknown_vault_schema(self):
        cases = [
            self.values | {"JWT_SECRET": "different_synthetic_test_" + "z" * 48},
            {key: value for key, value in self.values.items() if key != "SIM_API_KEY"},
            self.values | {"CABINET_ALPHA_DEV_READ_TOKEN": "synthetic_alpha"},
        ]
        for values in cases:
            with self.subTest(keys=values.keys()):
                self.vault.read.return_value = (values, 2)
                with self.assertRaises(adapter.MigrationError):
                    self.call("apply")
                self.assertEqual(self.path.read_bytes(), self.before)
                self.assertEqual(self.backup_paths(), [])

    def test_recovery_is_blocked_when_alpha_or_release_is_active(self):
        raw = self.settings.replace(b"DEV_ENABLED=false", b"DEV_ENABLED=true")
        self.path.write_bytes(raw)
        with self.assertRaisesRegex(adapter.MigrationError, "Alpha"):
            self.call("apply")
        self.assertEqual(self.path.read_bytes(), raw)
        self.path.write_bytes(self.settings)
        (self.state / "pending.json").touch(mode=0o600)
        with self.assertRaisesRegex(adapter.MigrationError, "release is pending"):
            self.call("apply")
        self.assertEqual(self.path.read_bytes(), self.settings)
        self.assertEqual(self.backup_paths(), [])

    def test_ineligible_local_files_fail_before_vault(self):
        self.path.chmod(0o640)
        with self.assertRaises(adapter.MigrationError):
            self.call("seed")
        self.path.chmod(0o600)
        self.path.rename(self.state / "real")
        self.path.symlink_to("real")
        with self.assertRaises(OSError):
            self.call("seed")
        self.vault.seed.assert_not_called()

    def test_common_deployment_lock_serializes_vault_transport(self):
        entered = threading.Event()
        unlocked = threading.Event()
        lock = self.state / "deploy.lock"
        lock.touch(mode=0o600)
        self.vault.seed.side_effect = lambda expected: unlocked.set() or 1

        def seed():
            entered.set()
            self.call("seed")

        with lock.open("wb") as deployment:
            fcntl.flock(deployment, fcntl.LOCK_EX)
            thread = threading.Thread(target=seed, daemon=True)
            thread.start()
            self.assertTrue(entered.wait(2))
            self.assertFalse(unlocked.wait(0.1))
        self.assertTrue(unlocked.wait(2))
        thread.join(2)


class VaultTests(unittest.TestCase):
    def test_explicit_route_keeps_the_origin_as_tls_server_name(self):
        with patch.object(adapter.socket, "create_connection") as dial:
            context = Mock()
            conn = adapter.AddressPinnedHTTPSConnection("vault.example.test", 8200, "192.0.2.1",
                                                        timeout=10, context=context)
            conn.connect()
            dial.assert_called_once_with(("192.0.2.1", 8200), 10)
            context.wrap_socket.assert_called_once_with(dial.return_value, server_hostname="vault.example.test")

    def test_path_has_no_override_and_redirect_is_not_followed(self):
        with patch.object(adapter.http.client, "HTTPSConnection") as transport, patch.object(adapter.ssl, "SSLContext"):
            response = transport.return_value.getresponse.return_value
            response.status = 307
            vault = adapter.Vault("https://vault.example.test:8200", Path("ca.crt"), "synthetic-token")
            with self.assertRaisesRegex(adapter.MigrationError, "HTTP 307"):
                vault.read()
            self.assertEqual(transport.call_count, 1)
            transport.return_value.request.assert_called_once_with(
                "GET", "/v1/pump/data/dev/compose", body=None,
                headers={"X-Vault-Token": "synthetic-token"},
            )

    def test_origin_rejects_url_suffixes_and_plain_http(self):
        for origin in (
            "http://vault.example.test", "https://vault.example.test/", "https://vault.example.test?",
            "https://vault.example.test/#", "https://operator@vault.example.test",
            "https://vault.example.test:65536", "https://vault.example.test:0",
            "https://vault.example.test\n", "https://vault.example.test@attacker.test",
        ):
            with self.subTest(origin=origin), self.assertRaises(adapter.MigrationError):
                adapter.vault_authority(origin)
        self.assertEqual(adapter.vault_authority("https://vault.example.test:8200"), ("vault.example.test", 8200))

    def test_seed_is_create_only_and_a_subsequent_seed_is_read_only(self):
        vault = object.__new__(adapter.Vault)
        values = secret_map()
        payload = {"data": {"data": values, "metadata": {"version": 1, "destroyed": False, "deletion_time": ""}}}
        vault.request = Mock(side_effect=[(404, None), (200, None), (200, payload), (200, payload)])
        self.assertEqual(vault.seed(values), 1)
        self.assertEqual(vault.seed(values), 1)
        self.assertEqual(
            [(c.args[0], c.args[1:] if len(c.args) > 1 else ()) for c in vault.request.call_args_list],
            [("GET", ()), ("POST", ({"options": {"cas": 0}, "data": values},)), ("GET", ()), ("GET", ())],
        )

    def test_seed_does_not_overwrite_existing_vault_version(self):
        vault = object.__new__(adapter.Vault)
        expected = secret_map()
        other = expected | {"JWT_SECRET": "different_synthetic_fixture"}
        vault.read = Mock(return_value=(other, 1))
        vault.request = Mock()
        with self.assertRaises(adapter.MigrationError):
            vault.seed(expected)
        vault.request.assert_not_called()


if __name__ == "__main__":
    unittest.main()
