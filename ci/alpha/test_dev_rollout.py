"""Regression checks for the Dev release boundary without Docker or the registry."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy"))
from alpha_dev import AlphaDev, SECRET_KEYS
from registry_release import IMAGE_COMPONENTS, RegistryRelease

spec = importlib.util.spec_from_file_location("dev_release", ROOT / "deploy/dev-release.py")
dev_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev_release)


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


if __name__ == "__main__":
    unittest.main()
