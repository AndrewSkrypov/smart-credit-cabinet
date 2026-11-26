from pathlib import Path
import shutil
import subprocess
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import yaml

import runtime_delivery_support as support

ROOT = Path(__file__).resolve().parents[1]
REVISION = "1" * 40
MANIFEST = "2" * 64
JAR = "3" * 64
LOCAL_IMAGE = "sha256:" + "4" * 64


def image(component, repository="registry.example.invalid/team/cabinet"):
    return f"{repository}/{component}@sha256:" + "a" * 64


def release(component):
    return SimpleNamespace(revision=REVISION, checksum=MANIFEST,
                           images={"credit": image("credit"), component: image(component)},
                           **{component: {"dev_mode": "remote", "jar_sha256": JAR}})


class DescriptorTest(unittest.TestCase):
    def test_descriptor_binds_component_to_exact_repository_revision_and_jar(self):
        for name in support.COMPONENTS:
            with self.subTest(name=name):
                candidate = release(name)
                descriptor = support.Descriptor.from_release(candidate, name)
                self.assertEqual((descriptor.revision, descriptor.manifest_sha256, descriptor.jar_sha256), (REVISION, MANIFEST, JAR))
                candidate.images[name] = image(name, "registry.example.invalid/other/cabinet")
                with self.assertRaisesRegex(ValueError, "проекта выпуска"):
                    support.Descriptor.from_release(candidate, name)
        candidate = release("documents")
        del candidate.documents
        with self.assertRaisesRegex(ValueError, "descriptor"):
            support.Descriptor.from_release(candidate, "documents")
        del candidate.images["documents"]
        self.assertIsNone(support.Descriptor.from_release(candidate, "documents"))

    def test_oci_verification_requires_digest_labels_and_running_ready_container(self):
        descriptor = support.Descriptor.from_release(release("servicing"), "servicing")
        labels = {"org.opencontainers.image.revision": REVISION, "org.opencontainers.image.jar.sha256": JAR}
        report = {"Id": LOCAL_IMAGE, "RepoDigests": [descriptor.image], "Config": {"Labels": dict(labels)}}
        with patch.object(support, "inspect_one", return_value=report):
            self.assertEqual(descriptor.verify_image(), LOCAL_IMAGE)
            report["Config"]["Labels"]["org.opencontainers.image.jar.sha256"] = "0" * 64
            with self.assertRaisesRegex(RuntimeError, "provenance"):
                descriptor.verify_image()
            report["Config"]["Labels"] = labels
            report["RepoDigests"] = []
            with self.assertRaisesRegex(RuntimeError, "provenance"):
                descriptor.verify_image()
        deployment = SimpleNamespace(container=lambda service: f"{service}-container")
        state = {"Running": True, "Health": {"Status": "starting"}}
        with patch.object(support, "inspect_one", return_value={"Image": LOCAL_IMAGE, "State": state}):
            with self.assertRaisesRegex(RuntimeError, "readiness"):
                support.verify_container(deployment, "servicing", LOCAL_IMAGE, ready=True)
            state["Health"]["Status"] = "healthy"
            self.assertEqual(support.verify_container(deployment, "servicing", LOCAL_IMAGE, ready=True), LOCAL_IMAGE)

    def test_failed_command_does_not_report_child_output_or_arguments(self):
        with patch.object(support.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "sensitive", "sensitive")):
            with self.assertRaises(RuntimeError) as error:
                support.run("tool", "sensitive")
        self.assertNotIn("sensitive", str(error.exception))


class PrivateStateTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)

    def test_private_env_is_stable_and_rejects_shared_secret_and_unsafe_file(self):
        path = support.ensure_private(self.root / "runtime") / "runtime.env"
        keys = ("RUNTIME_APP_PASSWORD", "RUNTIME_OWNER_PASSWORD")
        values = support.load_secrets(path, keys)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(support.load_secrets(path, keys), values)
        self.assertNotEqual(values[keys[0]], values[keys[1]])
        with self.assertRaisesRegex(ValueError, "отдельные"):
            support.load_secrets(path, keys, {"OTHER_APP": values[keys[0]]})
        path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, "0600"):
            support.load_secrets(path, keys)
        original = path.rename(path.with_suffix(".saved"))
        path.symlink_to(original)
        with self.assertRaises(OSError):
            support.load_secrets(path, keys)

    def test_immutable_receipt_accepts_exact_replay_and_refuses_change(self):
        receipt = self.root / "attempt.json"
        record = {"manifest_sha256": MANIFEST, "mode": "remote"}
        support.write_record(receipt, record, immutable=True)
        support.write_record(receipt, record, immutable=True)
        with self.assertRaisesRegex(RuntimeError, "неизменяемую"):
            support.write_record(receipt, {**record, "mode": "local"}, immutable=True)
        self.assertEqual(support.read_record(receipt), record)

    def test_state_scopes_dev_and_separates_restore_projects(self):
        deployment = SimpleNamespace(project="cabinet-dev", path=self.root / "cabinet.env", local=False)
        self.assertEqual(support.private_state(deployment, "documents"), self.root / "documents")
        deployment.project = "cabinet-dev-restore-one"
        self.assertEqual(support.private_state(deployment, "documents"), self.root / "documents-cabinet-dev-restore-one")
        deployment.project = "cabinet-prod"
        self.assertIsNone(support.private_state(deployment, "documents"))
        deployment.project = "cabinet-dev"
        deployment.local = True
        self.assertIsNone(support.private_state(deployment, "documents"))

    @unittest.skipUnless(shutil.which("openssl"), "OpenSSL is required to validate mTLS fixtures")
    def test_mtls_survives_retry_and_rejects_cross_service_peer_and_credentials(self):
        installed = {}
        for component, peer in support.TLS_IDENTITIES.items():
            state = self.root / component
            tls = support.ensure_tls(state, component, peer)
            installed[component] = tls
            ca = (tls / "ca.crt").read_bytes()
            self.assertEqual(support.ensure_tls(state, component, peer), tls)
            self.assertEqual((tls / "ca.crt").read_bytes(), ca)
            self.assertEqual((tls / "ca.key").stat().st_mode & 0o777, 0o600)
            self.assertEqual((tls / "credit/tls.key").stat().st_mode & 0o777, 0o444)
        self.assertNotEqual((installed["servicing"] / "ca.crt").read_bytes(),
                            (installed["documents"] / "ca.crt").read_bytes())
        with self.assertRaisesRegex(ValueError, "identity"):
            support.ensure_tls(self.root / "documents", "documents", support.TLS_IDENTITIES["servicing"])
        directory = installed["documents"] / "credit"
        directory.chmod(0o700)
        certificate = directory / "tls.crt"
        certificate.unlink()
        certificate.write_bytes((installed["servicing"] / "credit/tls.crt").read_bytes())
        certificate.chmod(0o444)
        directory.chmod(0o555)
        with self.assertRaises(RuntimeError):
            support.ensure_tls(self.root / "documents", "documents", support.TLS_IDENTITIES["documents"])


class ComposeConformanceTest(unittest.TestCase):
    def test_credit_can_use_both_private_overlays_without_conflicting_env_or_mounts(self):
        overlays = {
            name: yaml.safe_load((ROOT / "deploy" / f"compose.{name}.yaml").read_text())
            for name in support.COMPONENTS
        }
        environments, targets = {}, set()
        for name, overlay in overlays.items():
            services = overlay["services"]
            self.assertTrue(all(network["internal"] for network in overlay["networks"].values()))
            self.assertNotIn("ports", services[name])
            self.assertTrue(set(services[name]["networks"]).issubset(overlay["networks"]))
            rpc = f"{name}-rpc"
            self.assertIn(rpc, services[name]["networks"])
            self.assertIn(rpc, services["credit"]["networks"])
            self.assertNotIn(f"{name}-store", services["credit"]["networks"])
            for setting, value in services["credit"]["environment"].items():
                self.assertEqual(environments.setdefault(setting, value), value)
            for mount in services["credit"]["volumes"]:
                if isinstance(mount, dict):
                    self.assertTrue(mount["read_only"])
                    target = mount["target"]
                else:
                    _, target, access = mount.rsplit(":", 2)
                    self.assertEqual(access, "ro")
                self.assertNotIn(target, targets)
                targets.add(target)


if __name__ == "__main__":
    unittest.main()
