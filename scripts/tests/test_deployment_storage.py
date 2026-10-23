import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("deployment_common", Path(__file__).resolve().parents[2] / "deploy/common.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DeploymentStorageTest(unittest.TestCase):
    def deployment(self, directory, **extra):
        values = {"POSTGRES_PASSWORD": "p" * 64, "JWT_SECRET": "j" * 64, "SIM_API_KEY": "s" * 64,
                  "IDENTITY_LINK_KEY": "i" * 64, "VALKEY_PASSWORD": "v" * 64, "DEMO_ACCOUNTS": "false", "SIM_CONTROL_ENABLED": "false",
                  "CABINET_RELEASE": "test", "CABINET_DOMAIN": "localhost", "PUBLIC_CABINET_URL": "https://localhost"}
        for index, service in enumerate(["IDENTITY", "CREDIT", "ENGAGEMENT"]):
            values[service + "_DB_PASSWORD"] = ("runtime" + str(index)) * 8
            values[service + "_MIGRATION_PASSWORD"] = ("migration" + str(index)) * 8
        values.update(extra)
        path = Path(directory) / "deploy.env"
        path.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")
        return module.Deployment("cabinet-prod-test", path, True)

    def keys(self):
        return dict(S3_ACCESS_KEY="a" * 32, S3_SECRET_KEY="b" * 32, S3_ADMIN_ACCESS_KEY="c" * 32, S3_ADMIN_SECRET_KEY="d" * 32)

    def test_legacy_local_env_cannot_inherit_s3_mode_from_shell(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"STORAGE_BACKEND": "s3", "STORAGE_ENABLED": "true"}):
            deployment = self.deployment(directory)
            self.assertEqual(deployment.profiles, {"prod"})
            self.assertEqual(deployment.environment["STORAGE_BACKEND"], "local")
            self.assertEqual(deployment.environment["STORAGE_ENABLED"], "false")

    def test_reverse_cutover_can_keep_s3_reads_with_local_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            deployment = self.deployment(directory, STORAGE_BACKEND="local", STORAGE_ENABLED="true", **self.keys())
            self.assertEqual(deployment.profiles, {"prod", "storage"})
            config = {"services": {"clamav": {}, "seaweedfs": {"profiles": ["storage"]}, "model": {"profiles": ["ai"]}}}
            self.assertEqual(set(deployment.active_services(config)), {"clamav", "seaweedfs"})

    def test_s3_rejects_missing_default_and_reused_credentials(self):
        for keys in ({}, {**self.keys(), "S3_SECRET_KEY": "dev-storage-secret-change-me"},
                     {**self.keys(), "S3_ADMIN_SECRET_KEY": "b" * 32}):
            with self.subTest(keys_present=sorted(keys)), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):
                    self.deployment(directory, STORAGE_BACKEND="s3", **keys)

    def test_valkey_requires_an_independent_generated_secret(self):
        for value in ("", "dev-valkey-password-change-before-deployment", "p" * 64):
            with self.subTest(kind="invalid-valkey-secret"), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises(ValueError):
                    self.deployment(directory, VALKEY_PASSWORD=value)
