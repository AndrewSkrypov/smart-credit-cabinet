"""Offline proofs of the real MCP bootstrap and enrollment boundary."""
from hashlib import sha256
import importlib.util
import os
from pathlib import Path
import shutil
import stat
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import mcp_bootstrap as bootstrap
import mcp_dev
from registry_release import IMAGE_COMPONENTS, RegistryRelease
from runtime_delivery_support import read_record, write_record


DEV = "cabinet-dev"
spec = importlib.util.spec_from_file_location("bootstrap_dev_release", Path(__file__).with_name("dev-release.py"))
controller = importlib.util.module_from_spec(spec)
spec.loader.exec_module(controller)


class StopBeforeBackup(Exception):
    pass


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL unavailable")
class McpBootstrapDeliveryTest(unittest.TestCase):
    def setUp(self):
        scratch = TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.scratch = Path(scratch.name)
        self.state = self.scratch / "dev-state"
        self.state.mkdir(mode=0o700)
        self.env = self.state / "cabinet.env"
        self.original = (
            b"# Existing protected Dev environment\r\n"
            b"CABINET_DOMAIN=dev.credit-cabinet.ru\r\nPUBLIC_CABINET_URL=https://dev.credit-cabinet.ru\r\n"
            b"CABINET_ALPHA_DEV_ENABLED=true\r\nCABINET_ALPHA_DEV_GENERATION=g8b1a02f4d62af8c0\r\n"
            b"CABINET_ALPHA_DEV_READ_ENABLED=false\r\nDEMO_ACCOUNTS=false\r\nSIM_CONTROL_ENABLED=false"
        )
        self.set_live_env(self.original)
        manifest = self.scratch / "release.json"
        write_record(manifest, {
            "revision": "2" * 40,
            "images": {name: f"registry.example.invalid/pump/{name}@sha256:" + "3" * 64
                       for name in {*IMAGE_COMPONENTS, "mcp"}},
            "mcp": {"config_digest": "sha256:" + "4" * 64,
                    "base_image": "node:22.16.0-bookworm-slim@sha256:" + "5" * 64},
        })
        self.release = RegistryRelease(manifest)
        self.live = self.state / bootstrap.TLS_DIR
        self.private = self.state / bootstrap.PRIVATE_DIR

    def set_live_env(self, contents):
        self.env.write_bytes(contents)
        self.env.chmod(0o600)

    def prepare(self, contents=None, **kwargs):
        return bootstrap.prepare_env(kwargs.pop("project", DEV), self.env, kwargs.pop("release", self.release),
                                     self.original if contents is None else contents,
                                     allow_new=kwargs.pop("allow_new", True), **kwargs)

    def assert_no_new_state(self):
        self.assertEqual(list(self.state.iterdir()), [self.env])
        self.assertTrue(self.env.read_bytes() == self.original)

    def test_actual_registry_release_creates_private_tokens_and_real_tls(self):
        candidate = self.prepare()
        values = bootstrap.read_values(candidate)
        self.assertTrue(candidate.startswith(self.original + b"\r\n"))
        self.assertNotIn(b"\n", candidate.replace(b"\r\n", b""))
        self.assertTrue(self.env.read_bytes() == self.original)
        self.assertEqual(values[mcp_dev.FLAG], "true")
        self.assertEqual({key: values[key] for key in bootstrap.DEV_CLIENT}, bootstrap.DEV_CLIENT)
        self.assertTrue(all(bootstrap.TOKEN.fullmatch(values[key]) for key in bootstrap.TOKENS))
        self.assertTrue(values[bootstrap.TOKENS[0]] != values[bootstrap.TOKENS[1]])
        self.assertEqual(values["CABINET_ALPHA_DEV_GENERATION"], "g8b1a02f4d62af8c0")
        self.assertEqual(values["CABINET_ALPHA_DEV_READ_ENABLED"], "false")
        for path in (self.live, self.private):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700)
        self.assertEqual(stat.S_IMODE((self.private / "ca.key").stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE((self.private / bootstrap.RECORD).stat().st_mode), 0o600)
        self.assertEqual({path.name for path in self.live.iterdir()}, bootstrap.TLS_PAIRS.keys())
        for pair, host in bootstrap.TLS_PAIRS.items():
            directory = self.live / pair
            self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o555)
            self.assertEqual({path.name for path in directory.iterdir()}, bootstrap.TLS_FILES)
            self.assertTrue(all(stat.S_IMODE(path.stat().st_mode) == 0o444 for path in directory.iterdir()))
            leaf = str(directory / "tls.crt")
            if host:
                san = mcp_dev.run_openssl("x509", "-in", leaf, "-noout", "-ext", "subjectAltName")
                self.assertEqual(san.splitlines()[-1].strip(), f"DNS:{host}".encode())
            eku = mcp_dev.run_openssl("x509", "-in", leaf, "-noout", "-ext", "extendedKeyUsage")
            self.assertEqual(eku.splitlines()[-1].strip(),
                             b"TLS Web Server Authentication" if host else b"TLS Web Client Authentication")
            with self.assertRaises(ValueError):
                mcp_dev.run_openssl("verify", "-purpose", "sslclient" if host else "sslserver",
                                    "-CAfile", str(directory / "ca.crt"), leaf)
        client_der = mcp_dev.run_openssl("x509", "-in", str(self.live / "client/tls.crt"), "-outform", "DER")
        self.assertEqual(mcp_dev.environment(self.env, values)["CABINET_MCP_CREDIT_TLS_PEER_SHA256"],
                         sha256(client_der).hexdigest())

    def test_retry_before_and_after_env_commit_uses_the_same_settings_and_tls(self):
        first = self.prepare()
        files = bootstrap.tls_hashes(self.live)
        with patch.object(bootstrap, "generate_tls", side_effect=AssertionError("unexpected issuance")):
            self.assertTrue(self.prepare() == first)
            self.set_live_env(first)
            self.assertTrue(self.prepare(first, allow_new=False) == first)
        self.assertEqual(bootstrap.tls_hashes(self.live), files)

    def test_retry_after_private_bundle_without_live_tls(self):
        real_publish = bootstrap.publish_directory

        def interrupt_tls(source, target):
            if target == self.live:
                raise OSError("fixture interruption before TLS rename")
            real_publish(source, target)

        with patch.object(bootstrap, "publish_directory", side_effect=interrupt_tls), self.assertRaises(OSError):
            self.prepare()
        staged = self.private / bootstrap.TLS_DIR
        self.assertFalse(self.live.exists())
        self.assertTrue(self.env.read_bytes() == self.original)
        settings = read_record(self.private / bootstrap.RECORD)["settings"]
        files = bootstrap.tls_hashes(staged)
        with patch.object(bootstrap, "generate_tls", side_effect=AssertionError("unexpected issuance")):
            candidate = bootstrap.read_values(self.prepare())
        self.assertTrue(all(candidate[key] == value for key, value in settings.items()))
        self.assertEqual(bootstrap.tls_hashes(self.live), files)
        self.assertFalse(staged.exists())

    def test_retry_after_tls_publication_before_returning_env(self):
        real_publish = bootstrap.publish_directory

        def interrupt_after_tls(source, target):
            real_publish(source, target)
            if target == self.live:
                raise OSError("fixture interruption after TLS rename")

        with patch.object(bootstrap, "publish_directory", side_effect=interrupt_after_tls), self.assertRaises(OSError):
            self.prepare()
        self.assertTrue(self.live.is_dir())
        self.assertTrue(self.env.read_bytes() == self.original)
        settings = read_record(self.private / bootstrap.RECORD)["settings"]
        with patch.object(bootstrap, "generate_tls", side_effect=AssertionError("unexpected issuance")):
            candidate = bootstrap.read_values(self.prepare())
        self.assertTrue(all(candidate[key] == value for key, value in settings.items()))

    def test_fully_configured_manual_mcp_is_validated_and_preserved(self):
        provisioned = self.prepare()
        self.private.rename(self.scratch / "fixture-manual-ca")
        manual = provisioned.replace(b"alpha-hack-dev", b"operator-client").replace(b"38765/callback", b"35000/return")
        self.set_live_env(manual)
        files = bootstrap.tls_hashes(self.live)
        with patch.object(bootstrap, "prepare_bundle", side_effect=AssertionError("unexpected bootstrap")):
            self.assertTrue(self.prepare(manual) == manual)
        self.assertFalse(self.private.exists())
        self.assertEqual(bootstrap.tls_hashes(self.live), files)

    def test_partial_manual_env_and_explicit_disable_are_preserved(self):
        for attachment in (b"CABINET_DEV_MCP_ENABLED=true", b"CABINET_MCP_OAUTH_CLIENT_ID=operator-client"):
            partial = self.original + b"\r\n" + attachment
            self.set_live_env(partial)
            with self.assertRaisesRegex(ValueError, "Неполная ручная"):
                self.prepare(partial)
            self.assertTrue(self.env.read_bytes() == partial)
            self.assertEqual(list(self.state.iterdir()), [self.env])
        disabled = self.original + b"\r\nCABINET_DEV_MCP_ENABLED=false"
        self.set_live_env(disabled)
        self.assertTrue(self.prepare(disabled) == disabled)
        self.assertEqual(list(self.state.iterdir()), [self.env])

    def test_unknown_tls_and_private_paths_are_preserved(self):
        self.live.mkdir()
        marker = self.live / "operator-marker"
        marker.touch()
        with self.assertRaisesRegex(ValueError, "mcp-tls без подтвержденной"):
            self.prepare()
        self.assertTrue(marker.is_file())
        marker.unlink()
        self.live.rmdir()
        self.private.mkdir(mode=0o700)
        marker = self.private / "operator-marker"
        marker.touch()
        with self.assertRaisesRegex(ValueError, "конфликтующая подготовка"):
            self.prepare()
        self.assertTrue(marker.is_file())
        self.assertFalse(self.live.exists())

    def test_atomic_publication_does_not_replace_an_empty_existing_directory(self):
        candidate = self.state / "fixture-new"
        candidate.mkdir()
        self.live.mkdir()
        previous = self.live.stat()
        with self.assertRaisesRegex(ValueError, "уже занят"):
            bootstrap.publish_directory(candidate, self.live)
        self.assertTrue(candidate.is_dir())
        self.assertTrue(os.path.samestat(previous, self.live.stat()))

    def test_scope_checks_do_not_materialize_secrets(self):
        for project in ("cabinet-prod", "cabinet-dev-demo", "cabinet-dev-restore-fixture"):
            with self.subTest(project=project):
                self.assertTrue(self.prepare(project=project) == self.original)
        for target in (b"http://dev.credit-cabinet.ru", b"https://dev.credit-cabinet.ru:443",
                       b"https://dev.credit-cabinet.ru/other", b"https://cabinet.example.invalid"):
            wrong = self.original.replace(b"https://dev.credit-cabinet.ru", target)
            self.assertTrue(self.prepare(wrong) == wrong)
        demo = self.original + b"\r\nCABINET_ISOLATED_DEMO=true"
        self.assertTrue(self.prepare(demo) == demo)
        for release in (None, SimpleNamespace(images={}),
                        SimpleNamespace(images={"mcp": "registry.example.invalid/pump/mcp:latest"})):
            self.assertTrue(self.prepare(release=release) == self.original)
        self.assertTrue(self.prepare(verify_only=True) == self.original)
        self.assertTrue(self.prepare(allow_new=False) == self.original)
        self.assert_no_new_state()

    def test_changed_managed_leaf_is_rejected_without_replacing_state(self):
        self.prepare()
        leaf = self.live / "client/tls.crt"
        leaf.chmod(0o644)
        leaf.write_bytes(b"fixture changed")
        leaf.chmod(0o444)
        with self.assertRaisesRegex(ValueError, "Изменились подготовленные"):
            self.prepare()
        self.assertTrue(leaf.read_bytes() == b"fixture changed")
        self.assertTrue(self.env.read_bytes() == self.original)

    def test_controller_enrolls_the_new_env_without_changing_old_env_before_backup(self):
        generation = "g8b1a02f4d62af8c0"
        alpha_state = {"generation": generation, "read_enabled": False}
        modes = {name: "remote" for name in ("catalog", "assessment", "servicing", "documents")}
        previous = {"revision": "1" * 40, "manifest_sha256": "6" * 64, "alpha_dev": alpha_state,
                    **{name: {"mode": mode} for name, mode in modes.items()}}
        write_record(self.state / "current.json", previous)
        release = SimpleNamespace(path=self.release.path, revision=self.release.revision, checksum=self.release.checksum,
                                  images=self.release.images, has_all_runtimes=True, alpha_dev={})
        alpha = SimpleNamespace(state=lambda: alpha_state, generation=generation, read_enabled=False)
        seen = []

        def deployment(*args, env_contents, **kwargs):
            seen.append(env_contents)
            return SimpleNamespace(values=bootstrap.read_values(env_contents), alpha_dev=alpha,
                                   catalog_enabled=True, catalog_mode=modes["catalog"],
                                   catalog_state=self.state / "catalog", runtime_modes=modes)

        def stopped_before_backup(*args):
            self.assertTrue(self.env.read_bytes() == self.original)
            raise StopBeforeBackup

        options = SimpleNamespace(verify_only=False)
        with (patch.object(controller, "Deployment", side_effect=deployment),
              patch.object(controller, "requested_mode", return_value="disabled"),
              patch.object(controller, "capture_previous", side_effect=stopped_before_backup)):
            with self.assertRaises(StopBeforeBackup):
                controller.deploy(options, self.state, release)
            entry = controller.runtime_attempts.enrollment(self.state)
            with patch.object(bootstrap, "prepare_bundle", side_effect=AssertionError("unexpected issuance")):
                with self.assertRaises(StopBeforeBackup):
                    controller.deploy(options, self.state, release)
        self.assertEqual(entry, controller.runtime_attempts.enrollment(self.state))
        self.assertEqual(entry["previous"], previous)
        self.assertEqual(entry["target"]["alpha_dev"], alpha_state)
        self.assertEqual({name: entry["target"][name]["mode"] for name in modes}, modes)
        self.assertEqual(entry["target"]["mcp"], {"enabled": True})
        self.assertTrue(len(seen) == 2 and seen[0] == seen[1])
        self.assertTrue(controller.runtime_attempts.enrolled_env(self.state, entry, self.original) == seen[0])
        self.assertFalse((self.state / "pending.json").exists())

    def test_verify_only_controller_does_not_provision(self):
        def stop_on_deployment(*args, env_contents, **kwargs):
            self.assertTrue(env_contents == self.original)
            raise StopBeforeBackup

        with (patch.object(controller, "Deployment", side_effect=stop_on_deployment),
              patch.object(bootstrap, "prepare_bundle", side_effect=AssertionError("unexpected bootstrap")),
              self.assertRaises(StopBeforeBackup)):
            controller.deploy(SimpleNamespace(verify_only=True), self.state, self.release)
        self.assert_no_new_state()


if __name__ == "__main__":
    unittest.main()
