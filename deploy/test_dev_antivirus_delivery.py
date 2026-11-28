"""Keep the temporary Dev signature policy out of ordinary and Prod deployment."""
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import os
import unittest
from unittest.mock import patch

import common
import dev_antivirus as policy
from test_dev_payment_reserve_delivery import environment


class DevAntivirusPolicyTest(unittest.TestCase):
    def test_only_the_manifest_bound_canonical_dev_uses_seven_days(self):
        values = environment()
        for origin in ("https://dev.credit-cabinet.ru", "https://dev.credit-cabinet.ru/"):
            self.assertEqual(policy.signature_age("cabinet-dev", False, values | {"PUBLIC_CABINET_URL": origin}, object()), "P7D")
        for project, local, release, historical, change in (
            ("cabinet-prod", False, object(), False, {}),
            ("cabinet-dev-demo", False, object(), False, {}),
            ("cabinet-dev-restore-example", False, object(), False, {}),
            ("cabinet-dev", True, object(), False, {}),
            ("cabinet-dev", False, None, False, {}),
            ("cabinet-dev", False, object(), True, {}),
            ("cabinet-dev", False, object(), False, {"CABINET_DOMAIN": "elsewhere.invalid"}),
            ("cabinet-dev", False, object(), False, {"PUBLIC_CABINET_URL": "http://dev.credit-cabinet.ru"}),
            ("cabinet-dev", False, object(), False, {"PUBLIC_CABINET_URL": "https://dev.credit-cabinet.ru/other"}),
            ("cabinet-dev", False, object(), False, {"PUBLIC_CABINET_URL": "https://user@dev.credit-cabinet.ru"}),
        ):
            with self.subTest(project=project, local=local, historical=historical, change=change):
                self.assertEqual(policy.signature_age(project, local, values | change, release, historical), "PT72H")

    def test_controller_does_not_import_the_ci_exception_from_ambient_environment(self):
        release = SimpleNamespace(revision="1" * 40, alpha_dev=None, assessment=None, servicing=None, documents=None,
                                  environment=lambda: {})
        with TemporaryDirectory() as temporary, patch.object(common, "RegistryRelease", return_value=release), patch.object(
            common, "requested_mode", return_value="disabled",
        ), patch.dict(os.environ, {policy.VARIABLE: "P7D"}):
            for project, expected, configured in (
                ("cabinet-prod", "PT72H", None),
                ("cabinet-prod", "PT1H", "PT1H"),
                ("cabinet-dev", "P7D", None),
                ("cabinet-dev", "P7D", "PT72H"),
            ):
                values = environment()
                if configured is not None:
                    values[policy.VARIABLE] = configured
                original = dict(values)
                raw = "".join(f"{key}={value}\n" for key, value in values.items()).encode()
                deployment = common.Deployment(project, Path(temporary) / "cabinet.env",
                                               release_manifest=Path(temporary) / "release.json",
                                               env_contents=raw, open_hooks=False)
                self.assertEqual(deployment.environment[policy.VARIABLE], expected)
                self.assertEqual(deployment.antivirus_max_signature_age, expected)
                self.assertEqual(values, original)


if __name__ == "__main__":
    unittest.main()
