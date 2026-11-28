"""Cover the immutable MCP release artifact and its deployment consumers."""
from copy import deepcopy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy"))
from registry_release import IMAGE_COMPONENTS, RegistryRelease

REVISION = "a" * 40
REPOSITORY = "registry.example.invalid/pump/cabinet"
IMAGE_DIGEST = "sha256:" + "b" * 64
CONFIG_DIGEST = "sha256:" + "c" * 64
BASE_IMAGE = "node:24.13.0-bookworm-slim@sha256:" + "d" * 64


class McpReleaseTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "release.json"
        self.values = Path(directory.name) / "helm.json"
        self.legacy = {"revision": REVISION,
                       "images": {name: f"{REPOSITORY}/{name}@{IMAGE_DIGEST}" for name in IMAGE_COMPONENTS}}
        self.mcp_image = f"{REPOSITORY}/mcp@{IMAGE_DIGEST}"
        self.current = deepcopy(self.legacy)
        self.current["images"]["mcp"] = self.mcp_image
        self.current["mcp"] = {"config_digest": CONFIG_DIGEST, "base_image": BASE_IMAGE}

    def write_manifest(self, manifest):
        self.path.write_text(json.dumps(manifest), encoding="utf-8")

    def validate(self, *extra):
        return subprocess.run(
            [sys.executable, "-B", ROOT / "ci/gitlab/release-manifest.py", self.path, REVISION, REPOSITORY, *extra],
            capture_output=True, text=True,
        )

    def test_legacy_rollback_and_current_release(self):
        self.write_manifest(self.legacy)
        self.assertIsNone(RegistryRelease(self.path).mcp)
        self.assertEqual(self.validate().returncode, 0)
        self.assertIn("requires the MCP image", self.validate("--require-mcp").stderr)
        self.write_manifest(self.current)
        result = self.validate("--require-mcp", "--helm-values", self.values)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.values.read_text())["images"]["mcp"], self.mcp_image)
        self.assertEqual(RegistryRelease(self.path).environment()["CABINET_IMAGE_MCP"], self.mcp_image)

    def test_requires_digest_pairs_pinned_base_and_project(self):
        for field in ("image", "descriptor", "base_image", "config_digest", "repository", "tag"):
            with self.subTest(field=field):
                manifest = deepcopy(self.current)
                if field == "image":
                    del manifest["images"]["mcp"]
                elif field == "descriptor":
                    del manifest["mcp"]
                elif field == "base_image":
                    manifest["mcp"][field] = "node:24.13.0-bookworm-slim"
                elif field == "config_digest":
                    manifest["mcp"][field] = "sha256:wrong"
                elif field == "repository":
                    manifest["images"]["mcp"] = self.mcp_image.replace("/pump/", "/other/")
                elif field == "tag":
                    manifest["images"]["mcp"] = f"{REPOSITORY}/mcp:latest"
                self.write_manifest(manifest)
                with self.assertRaises(ValueError):
                    RegistryRelease(self.path)

    def test_pull_verifies_revision_base_and_config_digest(self):
        self.write_manifest(self.current)
        release = RegistryRelease(self.path)
        inspected = f"{CONFIG_DIGEST} {REVISION} {BASE_IMAGE}"
        pull = subprocess.CompletedProcess([], 0)
        verify = subprocess.CompletedProcess([], 0, inspected)
        with patch("registry_release.subprocess.run", side_effect=[pull, verify]) as docker:
            release.pull_and_verify(["cabinet-mcp"])
            self.assertEqual(docker.call_args_list[0].args[0], ["docker", "pull", self.mcp_image])
            self.assertEqual(docker.call_args_list[1].args[0][-1], self.mcp_image)
        for bad in (inspected.replace(CONFIG_DIGEST, IMAGE_DIGEST),
                    inspected.replace(REVISION, "e" * 40),
                    inspected.replace(BASE_IMAGE, "node:latest")):
            with self.subTest(provenance=bad), patch(
                "registry_release.subprocess.run", return_value=subprocess.CompletedProcess([], 0, bad)
            ), self.assertRaises(RuntimeError):
                release.local_image_id("mcp")


if __name__ == "__main__":
    unittest.main()
