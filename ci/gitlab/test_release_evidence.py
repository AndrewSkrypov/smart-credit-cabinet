import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("release-evidence.py").resolve()


class ReleaseEvidenceTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.repo = self.root / "checkout"
        self.git(self.root, "init", "--bare", "remote.git")
        self.git(self.root, "init", "-b", "main", "checkout")
        self.git(self.repo, "remote", "add", "origin", str(self.root / "remote.git"))
        self.git(self.repo, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "--allow-empty", "-m", "release")
        self.git(self.repo, "push", "origin", "main")
        self.revision = self.git(self.repo, "rev-parse", "HEAD")
        self.env = os.environ | {
            "CI_COMMIT_BRANCH": "main", "CI_COMMIT_REF_PROTECTED": "true", "CI_PIPELINE_SOURCE": "push",
            "PUMP_MAIN_RELEASE_ENABLED": "1", "CI_COMMIT_SHA": self.revision,
            "CI_PROJECT_ID": "1", "CI_PIPELINE_ID": "100", "CI_JOB_ID": "10",
        }
        self.manifest = self.repo / "release.json"
        self.manifest.write_text(json.dumps({"revision": self.revision, "images": {"test": "first"}}))

    @staticmethod
    def git(cwd, *args):
        return subprocess.check_output(["git", *args], cwd=cwd, stderr=subprocess.PIPE, text=True).strip()

    def run_evidence(self, operation, **override):
        return subprocess.run([sys.executable, "-B", SCRIPT, operation], cwd=self.repo,
                              env=self.env | override, capture_output=True, text=True)

    def test_one_publisher_evidence_is_valid_for_both_deployments(self):
        result = self.run_evidence("write")
        self.assertEqual(result.returncode, 0, result.stderr)
        for job in ("11", "12"):
            result = self.run_evidence("verify", CI_JOB_ID=job)
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotEqual(self.run_evidence("verify").returncode, 0)
        self.assertNotEqual(self.run_evidence("verify", CI_JOB_ID="11", CI_PIPELINE_ID="101").returncode, 0)
        self.manifest.write_text(json.dumps({"revision": self.revision, "images": {"test": "changed"}}))
        self.assertNotEqual(self.run_evidence("verify", CI_JOB_ID="11").returncode, 0)

    def test_fails_if_main_advances_after_publication(self):
        self.assertEqual(self.run_evidence("write").returncode, 0)
        self.git(self.repo, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                 "commit", "--allow-empty", "-m", "next release")
        self.git(self.repo, "push", "origin", "main")
        self.git(self.repo, "checkout", "--detach", self.revision)
        result = self.run_evidence("verify", CI_JOB_ID="11")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no longer the tip", result.stderr)

    def test_rejects_nonrelease_sources_and_checkout_mismatch(self):
        for override in (
            {"CI_PIPELINE_SOURCE": "web"}, {"CI_COMMIT_BRANCH": "dev"},
            {"CI_COMMIT_REF_PROTECTED": "false"}, {"PUMP_MAIN_RELEASE_ENABLED": "0"},
            {"CI_COMMIT_SHA": "e" * 40},
        ):
            with self.subTest(override=override):
                self.assertNotEqual(self.run_evidence("check", **override).returncode, 0)


if __name__ == "__main__":
    unittest.main()
