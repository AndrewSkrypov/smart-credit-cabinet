"""Regression checks for the Dev release boundary without Docker or the registry."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy"))
from alpha_dev import AlphaDev, SECRET_KEYS
from registry_release import IMAGE_COMPONENTS, RegistryRelease

spec = importlib.util.spec_from_file_location("dev_release", ROOT / "deploy/dev-release.py")
dev_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev_release)
spec = importlib.util.spec_from_file_location("verify_release", ROOT / "deploy/verify-release.py")
verify_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify_release)


class DevRolloutTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.state = Path(directory.name)
        self.repository = "registry.example.invalid/cabinet/project"
        self.revision = "a" * 40
        self.alpha = AlphaDev("g1234567890abcdef", False)
        self.images = {name: f"{self.repository}/{name}@sha256:" + "b" * 64 for name in IMAGE_COMPONENTS}
        self.alpha_metadata = {"image": f"{self.repository}/analytics@sha256:" + "c" * 64,
                               "jar_sha256": "d" * 64, "kafka_image": self.alpha.kafka_image}

    def manifest(self, **extra):
        path = self.state / "release.json"
        path.write_text(json.dumps({"revision": self.revision, "images": self.images, **extra}))
        return RegistryRelease(path)

    def config(self, **extra):
        return {"CABINET_ALPHA_DEV_ENABLED": "true", "CABINET_ALPHA_DEV_GENERATION": self.alpha.generation,
                **{key: str(index) * 64 for index, key in enumerate(SECRET_KEYS)}, **extra}

    def test_prod_helm_uses_the_base_image_set(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        result = self.state / "values.json"
        subprocess.run(
            [sys.executable, str(ROOT / "ci/gitlab/release-manifest.py"), str(release.path),
             self.revision, self.repository, "--helm-values", str(result)], check=True,
        )
        self.assertEqual(json.loads(result.read_text())["images"], self.images)
        self.assertNotIn("CABINET_IMAGE_ALPHA_ANALYTICS", release.environment())

    def test_manifest_rejects_wrong_analytics_repository_and_bad_hashes(self):
        for invalid in (
            {"image": "registry.example.invalid/other/analytics@sha256:" + "c" * 64},
            {"image": f"{self.repository}/credit@sha256:" + "c" * 64},
            {"jar_sha256": "d" * 63},
            {"kafka_image": "apache/kafka:latest"},
        ):
            with self.subTest(field=next(iter(invalid))), self.assertRaises(ValueError):
                self.manifest(alpha_dev={**self.alpha_metadata, **invalid})

    def test_revision_and_jar_labels_are_both_checked(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        digest = "sha256:" + "e" * 64
        for jar_sha, matches in (("d"*64, True), ("e"*64, False)):
            result = subprocess.CompletedProcess([], 0, f"{digest} {self.revision} {jar_sha}", "")
            with self.subTest(matches=matches), patch("registry_release.subprocess.run", return_value=result) as inspect:
                if matches:
                    self.assertEqual(release.local_image_id("analytics"), digest)
                    self.assertEqual(inspect.call_args.args[0][-1], self.alpha_metadata["image"])
                else:
                    with self.assertRaises(RuntimeError):
                        release.local_image_id("analytics")

    def test_only_dev_with_an_explicit_manifest_and_config_enables_alpha(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        self.assertIsNone(AlphaDev.from_config("cabinet-dev", False, {}, release))
        for project, local in (("cabinet-prod", False), ("cabinet-dev-demo", False), ("cabinet-dev", True)):
            with self.subTest(project=project, local=local), self.assertRaises(ValueError):
                AlphaDev.from_config(project, local, self.config(), release)
        self.assertEqual(AlphaDev.from_config("cabinet-dev", False, self.config(), release), self.alpha)
        wrong_kafka = self.manifest(alpha_dev={**self.alpha_metadata, "kafka_image": "apache/kafka:4.3.2@sha256:"+"f"*64})
        with self.assertRaises(ValueError):
            self.alpha.environment(wrong_kafka)

    def test_rollout_requires_shadow_and_rejects_reusing_retired_generation(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        deployment = SimpleNamespace(values=self.config(), alpha_dev=self.alpha)
        desired, history, generation_set = dev_release.rollout_metadata(deployment, self.state, release, {}, {})
        self.assertEqual(generation_set, {self.alpha.generation})
        history.write_text(json.dumps(sorted(generation_set)))
        with self.assertRaises(ValueError):
            dev_release.rollout_metadata(deployment, self.state, release, {}, {})
        self.assertEqual(dev_release.rollout_metadata(deployment, self.state, release, {}, desired)[0], desired)

        deployment.alpha_dev = AlphaDev(self.alpha.generation, True)
        with self.assertRaises(ValueError):
            dev_release.rollout_metadata(deployment, self.state, release, {}, desired)
        activated, _, _ = dev_release.rollout_metadata(deployment, self.state, release, desired, {})
        self.assertTrue(activated["alpha_dev"]["read_enabled"])

    def test_alpha_gateway_requires_a_definitive_not_found(self):
        deployment = SimpleNamespace(alpha_dev=self.alpha, release=self.manifest(alpha_dev=self.alpha_metadata))
        containers = {"credit": "credit", "analytics-kafka": "kafka"}
        origin = "https://dev.example.invalid"
        url = origin + verify_release.ALPHA_ROUTE
        image = b"sha256:" + b"e" * 64
        for status in (404, 200, 302, 401, None):
            opener = MagicMock()
            if status is None:
                opener.open.side_effect = URLError("unavailable")
            elif status != 200:
                opener.open.side_effect = HTTPError(url, status, "probe", {}, None)
            with (
                self.subTest(status=status),
                patch.object(verify_release, "docker", side_effect=[b"true " + image, image, b"401", b"503"]),
                patch.object(verify_release, "analytics_owner", return_value=f"{self.alpha.topic} " + "f" * 64),
            ):
                if status == 404:
                    report = verify_release.verify_alpha(deployment, opener, origin, containers)
                    self.assertEqual(report["public_http"], 404)
                    self.assertEqual(report["http_with_token"], 503)
                else:
                    with self.assertRaises(RuntimeError):
                        verify_release.verify_alpha(deployment, opener, origin, containers)
                opener.open.assert_called_once_with(url, timeout=15)

    def test_failed_shadow_verification_blocks_promotion_until_retry(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        (self.state / "cabinet.env").touch(mode=0o600)
        args = SimpleNamespace(verify_only=False, host_memory_gib=8)
        config = self.config(PUBLIC_CABINET_URL="https://dev.example.invalid")
        deployment = SimpleNamespace(alpha_dev=self.alpha, values=config)
        current_file = self.state / "current.json"
        pending_file = self.state / "pending.json"
        verified = {"passed": True}
        with (
            patch.object(dev_release, "Deployment", return_value=deployment),
            patch.object(dev_release, "read_credentials", return_value={}),
            patch.object(dev_release, "provision"),
            patch.object(dev_release, "verify", return_value={}),
            patch.object(dev_release, "run", side_effect=[None, RuntimeError("failed shadow probe"), None, json.dumps(verified)]),
            patch("sys.stdout", new_callable=io.StringIO),
        ):
            with self.assertRaisesRegex(RuntimeError, "failed shadow probe"):
                dev_release.deploy(args, self.state, release)
            self.assertFalse(current_file.exists())
            self.assertEqual(json.loads(pending_file.read_text())["alpha_dev"], self.alpha.state())

            deployment.alpha_dev = AlphaDev(self.alpha.generation, True)
            with self.assertRaisesRegex(ValueError, "в теневом режиме"):
                dev_release.deploy(args, self.state, release)
            deployment.alpha_dev = self.alpha
            dev_release.deploy(args, self.state, release)

        self.assertEqual(json.loads(current_file.read_text())["alpha_dev"], self.alpha.state())
        self.assertFalse(pending_file.exists())

    def test_failed_activation_can_resume_the_same_release_in_shadow(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        (self.state / "cabinet.env").touch(mode=0o600)
        args = SimpleNamespace(verify_only=False, host_memory_gib=8)
        config = self.config(PUBLIC_CABINET_URL="https://dev.example.invalid")
        deployment = SimpleNamespace(alpha_dev=self.alpha, values=config)
        state, _, _ = dev_release.rollout_metadata(deployment, self.state, release, {}, {})
        current_file = self.state / "current.json"
        pending_file = self.state / "pending.json"
        dev_release.write_state(current_file, state)
        deployment.alpha_dev = AlphaDev(self.alpha.generation, True)
        active, _, _ = dev_release.rollout_metadata(deployment, self.state, release, state, {})
        dev_release.write_state(pending_file, active)
        deployment.alpha_dev = self.alpha
        with (
            patch.object(dev_release, "Deployment", return_value=deployment),
            patch.object(dev_release, "read_credentials", return_value={}),
            patch.object(dev_release, "provision"),
            patch.object(dev_release, "verify", return_value={}),
            patch.object(dev_release, "run", side_effect=[RuntimeError("failed shadow start"), None, '{"passed":true}']) as run,
            patch("sys.stdout", new_callable=io.StringIO),
        ):
            with self.assertRaises(RuntimeError):
                dev_release.deploy(SimpleNamespace(verify_only=True), self.state, release)
            with self.assertRaisesRegex(RuntimeError, "failed shadow start"):
                dev_release.deploy(args, self.state, release)
            self.assertEqual(json.loads(pending_file.read_text()), state)
            with self.assertRaises(RuntimeError):
                dev_release.deploy(SimpleNamespace(verify_only=True), self.state, release)
            dev_release.deploy(args, self.state, release)

        self.assertEqual([call.args[1] for call in run.call_args_list], ["start.py", "start.py", "verify-release.py"])
        self.assertEqual(json.loads(current_file.read_text()), state)
        self.assertFalse(pending_file.exists())


if __name__ == "__main__":
    unittest.main()
