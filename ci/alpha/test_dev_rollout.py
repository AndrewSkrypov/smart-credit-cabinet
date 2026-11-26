"""Regression checks for the Dev release boundary without Docker or the registry."""
from contextlib import contextmanager
import errno
import importlib.util
import io
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, call, patch
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
        self.env_file = self.state / "cabinet.env"
        self.apply = SimpleNamespace(verify_only=False, host_memory_gib=31)
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

    def write_env(self, *extra, newline=b"\n"):
        keys = ["POSTGRES_PASSWORD", "JWT_SECRET", "SIM_API_KEY", "IDENTITY_LINK_KEY", "VALKEY_PASSWORD"]
        keys.extend(f"{database}_{role}_PASSWORD" for database in ("IDENTITY", "CREDIT", "ENGAGEMENT")
                    for role in ("DB", "MIGRATION"))
        values = {key: f"{i:064x}" for i, key in enumerate(keys, start=10)}
        values.update(CABINET_DOMAIN="dev.example.invalid", PUBLIC_CABINET_URL="https://dev.example.invalid",
                      DEMO_ACCOUNTS="false", SIM_CONTROL_ENABLED="false")
        contents = newline.join([*(f"{key}={value}".encode("ascii") for key, value in values.items()), *extra])
        self.env_file.touch(mode=0o600)
        self.env_file.write_bytes(contents)
        return contents

    @contextmanager
    def runtime(self, run_step=None):
        with (
            patch.object(dev_release, "read_credentials", return_value={}),
            patch.object(dev_release, "provision"),
            patch.object(dev_release, "verify", return_value={}),
            patch.object(dev_release, "run", return_value="{}", side_effect=run_step) as run,
            patch("sys.stdout", new_callable=io.StringIO) as output,
        ):
            yield run, output

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

    def test_first_rollout_preserves_base_env_and_reuses_alpha_after_failed_verification(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        base_revision = "0" * 40
        old_release = SimpleNamespace(path=self.state / "old-release.json", checksum="a" * 64)
        current_file = self.state / "current.json"
        pending_file = self.state / "pending.json"
        dev_release.write_state(current_file, {"revision": base_revision, "manifest_sha256": old_release.checksum})
        original = self.write_env("  # Настройки Dev".encode(), b"# CABINET_ALPHA_DEV_ENABLED=false",
                                  b"PUMP_KEEP=opaque  ", newline=b"\r\n")
        before = self.env_file.stat()
        verified_once = False

        def run_step(root, script, *arguments, **kwargs):
            nonlocal verified_once
            self.assertIn(str(self.env_file), arguments)
            if script == "backup.py":
                self.assertEqual(self.env_file.read_bytes(), original)
            elif script == "start.py":
                deployment = dev_release.Deployment("cabinet-dev", self.env_file, release_manifest=release.path)
                self.assertFalse(deployment.alpha_dev.read_enabled)
                self.assertEqual(json.loads(pending_file.read_text())["alpha_dev"], deployment.alpha_dev.state())
            elif not verified_once:
                verified_once = True
                raise RuntimeError("failed shadow probe")
            return "{}"

        with (
            self.runtime(run_step) as (run, output),
            patch.object(dev_release, "release_at", return_value=(self.state, old_release)),
            patch.object(dev_release.secrets, "token_hex", wraps=dev_release.secrets.token_hex) as random_hex,
        ):
            with self.assertRaisesRegex(RuntimeError, "failed shadow probe"):
                dev_release.deploy(self.apply, self.state, release)
            initialized = self.env_file.read_bytes()
            after = self.env_file.stat()
            self.assertEqual(json.loads(current_file.read_text())["revision"], base_revision)
            dev_release.deploy(self.apply, self.state, release)
            dev_release.deploy(SimpleNamespace(verify_only=True), self.state, release)
            self.assertEqual(random_hex.call_args_list, [call(8)] + [call(32)] * len(SECRET_KEYS))

        self.assertTrue(initialized.startswith(original + b"\r\nCABINET_ALPHA_DEV_ENABLED=true\r\n"))
        self.assertEqual(self.env_file.read_bytes(), initialized)
        self.assertEqual(self.env_file.stat().st_ino, after.st_ino)
        self.assertEqual((after.st_uid, after.st_gid), (before.st_uid, before.st_gid))
        self.assertEqual(stat.S_IMODE(after.st_mode), 0o600)
        values = dev_release.Deployment("cabinet-dev", self.env_file, release_manifest=release.path).values
        alpha = json.loads(current_file.read_text())["alpha_dev"]
        self.assertRegex(alpha["generation"], r"^g[0-9a-f]{16}$")
        self.assertFalse(alpha["read_enabled"])
        self.assertEqual(json.loads((self.state / "alpha-generations.json").read_text()), [alpha["generation"]])
        self.assertEqual(len({values[key] for key in SECRET_KEYS}), len(SECRET_KEYS))
        for key in SECRET_KEYS:
            self.assertRegex(values[key], r"^[0-9a-f]{64}$")
            self.assertNotIn(values[key], output.getvalue())
        self.assertEqual([c.args[1] for c in run.call_args_list],
                         ["backup.py", "start.py", "verify-release.py", "start.py", "verify-release.py", "verify-release.py"])
        self.assertFalse(pending_file.exists())
        self.assertFalse(list(self.state.glob(".cabinet.env.*")))

    def test_baseline_verify_and_existing_alpha_env_leave_env_untouched(self):
        current_file = self.state / "current.json"
        for label, metadata, config, verify_only in (
            ("baseline manifest", {}, (), False),
            ("verify only", {"alpha_dev": self.alpha_metadata}, (), True),
            ("disabled Alpha", {"alpha_dev": self.alpha_metadata}, (b"CABINET_ALPHA_DEV_ENABLED=false",), False),
            ("partial Alpha", {"alpha_dev": self.alpha_metadata}, (b"CABINET_ALPHA_DEV_GENERATION=",), False),
            ("unknown Alpha key", {"alpha_dev": self.alpha_metadata}, (b"CABINET_ALPHA_DEV_OTHER=custom",), False),
            ("configured Alpha", {"alpha_dev": self.alpha_metadata},
             tuple(f"{key}={value}".encode("ascii") for key, value in self.config().items()), False),
        ):
            with self.subTest(label=label), self.runtime(), patch.object(dev_release.secrets, "token_hex") as random_hex:
                release = self.manifest(**metadata)
                original = self.write_env(*config)
                before = self.env_file.stat()
                current_file.unlink(missing_ok=True)
                if verify_only:
                    dev_release.write_state(current_file, {"revision": release.revision, "manifest_sha256": release.checksum})
                args = SimpleNamespace(verify_only=verify_only, host_memory_gib=31)
                dev_release.deploy(args, self.state, release)
                random_hex.assert_not_called()
                self.assertEqual(self.env_file.stat().st_ino, before.st_ino)
                self.assertEqual(self.env_file.read_bytes(), original)

    def test_rejected_releases_leave_env_untouched(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        current_file = self.state / "current.json"
        pending_file = self.state / "pending.json"
        state = {"revision": release.revision, "manifest_sha256": release.checksum}
        for label, current, pending, config, expected, message in (
            ("changed manifest", {**state, "manifest_sha256": "0" * 64}, None, (), ValueError, "Manifest"),
            ("pending baseline", None, state, (), RuntimeError, "незавершённый выпуск"),
            ("missing current Alpha", {**state, "alpha_dev": self.alpha.state()}, None, (), ValueError, "записанного поколения"),
            ("missing pending Alpha", None, {**state, "alpha_dev": self.alpha.state()}, (), ValueError, "записанного поколения"),
            ("partial enabled Alpha", None, None, (b"CABINET_ALPHA_DEV_ENABLED=true",), ValueError, "Поколение Dev"),
        ):
            with self.subTest(label=label), self.runtime() as (run, _):
                original = self.write_env(*config)
                for path, value in ((current_file, current), (pending_file, pending)):
                    if value is None:
                        path.unlink(missing_ok=True)
                    else:
                        dev_release.write_state(path, value)
                with self.assertRaisesRegex(expected, message):
                    dev_release.deploy(self.apply, self.state, release)
                run.assert_not_called()
                self.assertEqual(self.env_file.read_bytes(), original)
                self.assertFalse((self.state / "alpha-generations.json").exists())

    def test_wrong_pinned_kafka_digest_is_rejected_before_initialization(self):
        release = self.manifest(alpha_dev={**self.alpha_metadata, "kafka_image": "apache/kafka:4.3.2@sha256:" + "f" * 64})
        original = self.write_env()
        with self.runtime() as (run, _), self.assertRaisesRegex(ValueError, "Digest Kafka"):
            dev_release.deploy(self.apply, self.state, release)
        run.assert_not_called()
        self.assertEqual(self.env_file.read_bytes(), original)
        self.assertFalse((self.state / "pending.json").exists())

    def test_failed_backup_or_operator_edit_prevents_initialization(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        old_release = SimpleNamespace(path=self.state / "old-release.json", checksum="a" * 64)
        dev_release.write_state(self.state / "current.json", {"revision": "0" * 40, "manifest_sha256": old_release.checksum})
        original = self.write_env(b"PUMP_KEEP=old")
        edited = original + b"\nCABINET_ALPHA_DEV_ENABLED=false\n"

        def edit_during_backup(root, script, *arguments):
            self.assertEqual(script, "backup.py")
            self.env_file.write_bytes(edited)

        with patch.object(dev_release, "release_at", return_value=(self.state, old_release)):
            with self.runtime(RuntimeError("failed backup")), self.assertRaisesRegex(RuntimeError, "failed backup"):
                dev_release.deploy(self.apply, self.state, release)
            self.assertEqual(self.env_file.read_bytes(), original)
            with self.runtime(edit_during_backup), self.assertRaisesRegex(RuntimeError, "cabinet.env изменился"):
                dev_release.deploy(self.apply, self.state, release)
            self.assertEqual(self.env_file.read_bytes(), edited)

        self.assertFalse((self.state / "pending.json").exists())
        self.assertFalse(list(self.state.glob(".cabinet.env.*")))

    def test_initialization_leaves_symlink_and_busy_mount_intact(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        original = self.write_env()
        with (
            self.runtime() as (run, _),
            patch.object(Path, "replace", side_effect=OSError(errno.EBUSY, "bind-mounted cabinet.env")),
            self.assertRaises(OSError),
        ):
            dev_release.deploy(self.apply, self.state, release)
        run.assert_not_called()
        self.assertEqual(self.env_file.read_bytes(), original)
        self.assertFalse(list(self.state.glob(".cabinet.env.*")))
        target = self.state / "target.env"
        self.env_file.rename(target)
        self.env_file.symlink_to(target)
        with self.runtime() as (run, _), self.assertRaisesRegex(ValueError, "обычный файл cabinet.env"):
            dev_release.deploy(self.apply, self.state, release)
        run.assert_not_called()
        self.assertTrue(self.env_file.is_symlink())
        self.assertEqual(target.read_bytes(), original)
        self.assertFalse((self.state / "pending.json").exists())

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
