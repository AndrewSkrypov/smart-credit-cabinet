import importlib.util
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("manual_delivery_scope", ROOT / "scripts/check-manual-review-delivery.py")
DELIVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DELIVERY)


class ManualReviewScopeTest(unittest.TestCase):
    def parse(self, project="cabinet-e2e-123", base="http://127.0.0.1:18080", changes=None):
        env = {"GITLAB_CI": "true", "CI_JOB_ID": "123", "CI_PROJECT_ID": "1",
               "COMPOSE_PROJECT_NAME": project, "CI_COMMIT_SHA": "a" * 40, "CI_PROJECT_DIR": str(ROOT)}
        env.update(changes or {})
        args = ["manual-review", "--revision", "a" * 40, "--project", project,
                "--base", base, "--output", "/unused/synthetic.json"]
        with patch.dict(os.environ, env, clear=True), patch.object(sys, "argv", args):
            return DELIVERY.options()

    def test_current_gitlab_job_fixture_is_allowed(self):
        self.assertEqual(self.parse().project, "cabinet-e2e-123")

    def test_foreign_job_revision_checkout_and_live_origin_are_rejected(self):
        from contextlib import redirect_stderr
        from io import StringIO
        cases = [dict(project="cabinet-dev"), dict(project="cabinet-e2e-999"),
                 dict(changes={"CI_COMMIT_SHA": "b" * 40}),
                 dict(changes={"CI_PROJECT_DIR": "/unused/foreign"}),
                 dict(base="https://dev.credit-cabinet.ru"),
                 dict(base="http://127.0.0.1:18080/foreign")]
        for case in cases:
            with self.subTest(case=case), redirect_stderr(StringIO()), self.assertRaises(SystemExit):
                self.parse(**case)


if __name__ == "__main__":
    unittest.main()
