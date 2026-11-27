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
from backup_support import DATABASES, digest
from registry_release import IMAGE_COMPONENTS, RUNTIME_COMPONENTS, RegistryRelease

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
                               "jar_sha256": "d" * 64,
                               "kafka_image": f"{self.repository}/kafka@sha256:" + "e" * 64}

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
        self.assertNotIn("CABINET_ALPHA_DEV_KAFKA_IMAGE", release.environment())

    def test_manifest_rejects_wrong_pilot_repositories_components_and_hashes(self):
        for invalid in (
            {"image": "registry.example.invalid/other/analytics@sha256:" + "c" * 64},
            {"image": f"{self.repository}/credit@sha256:" + "c" * 64},
            {"jar_sha256": "d" * 63},
            {"kafka_image": "registry.example.invalid/other/kafka@sha256:" + "e" * 64},
            {"kafka_image": f"{self.repository}/analytics@sha256:" + "e" * 64},
            {"kafka_image": f"{self.repository}/kafka:custom@sha256:" + "e" * 64},
            {"kafka_image": f"{self.repository}/kafka:latest"},
            {"kafka_image": f"{self.repository}/kafka@sha256:" + "e" * 63},
        ):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
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

    def test_kafka_pull_checks_registry_digest_revision_and_upstream(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        image_id = "sha256:" + "f" * 64
        pull_result = subprocess.CompletedProcess([], 0)
        for revision, upstream, valid in (
            (self.revision, release.kafka_upstream, True),
            ("0" * 40, release.kafka_upstream, False),
            (self.revision, "apache/kafka:4.3.2@sha256:" + "0" * 64, False),
            (self.revision, "", False),
        ):
            inspected = subprocess.CompletedProcess([], 0, f"{image_id} {revision} {upstream}", "")
            with (
                self.subTest(revision=revision, upstream=upstream),
                patch("registry_release.subprocess.run", side_effect=[pull_result, inspected]) as docker,
            ):
                if valid:
                    release.pull_and_verify(["analytics-kafka"])
                else:
                    with self.assertRaisesRegex(RuntimeError, "OCI provenance образа kafka"):
                        release.pull_and_verify(["analytics-kafka"])
                pull, inspect = docker.call_args_list
                self.assertEqual(pull, call(["docker", "pull", self.alpha_metadata["kafka_image"]], capture_output=True))
                self.assertEqual(inspect.args[0][-1], self.alpha_metadata["kafka_image"])
                self.assertIn("org.opencontainers.image.revision", inspect.args[0][-2])
                self.assertIn("org.opencontainers.image.base.name", inspect.args[0][-2])

    def test_only_dev_with_an_explicit_manifest_and_config_enables_alpha(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        self.assertIsNone(AlphaDev.from_config("cabinet-dev", False, {}, release))
        for project, local in (("cabinet-prod", False), ("cabinet-dev-demo", False), ("cabinet-dev", True)):
            with self.subTest(project=project, local=local), self.assertRaises(ValueError):
                AlphaDev.from_config(project, local, self.config(), release)
        self.assertEqual(AlphaDev.from_config("cabinet-dev", False, self.config(), release), self.alpha)
        self.assertEqual(self.alpha.environment(release)["CABINET_ALPHA_DEV_KAFKA_IMAGE"],
                         self.alpha_metadata["kafka_image"])

    def alpha_deployment(self, config):
        return SimpleNamespace(alpha_dev=self.alpha, values=config, catalog_enabled=False,
                               catalog_state=self.state / "catalog", runtime_modes={}, servicing=None, documents=None)

    def test_rollout_requires_shadow_and_rejects_reusing_retired_generation(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        deployment = self.alpha_deployment(self.config())
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
            ("disabled Alpha", {"alpha_dev": self.alpha_metadata},
             (b"CABINET_ALPHA_DEV_ENABLED=false", b"CABINET_ALPHA_DEV_GENERATION="), False),
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
            ("generation without enable flag", None, None, (b"CABINET_ALPHA_DEV_GENERATION=g1234567890abcdef",),
             ValueError, "укажите CABINET_ALPHA_DEV_ENABLED"),
            ("other Alpha key without enable flag", None, None, (b"CABINET_ALPHA_DEV_OTHER=custom",),
             ValueError, "укажите CABINET_ALPHA_DEV_ENABLED"),
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

    def test_legacy_kafka_manifest_is_only_read_for_historical_backup(self):
        upstream = self.manifest().kafka_upstream
        with self.assertRaisesRegex(ValueError, "Kafka из проекта Registry"):
            self.manifest(alpha_dev={**self.alpha_metadata, "kafka_image": upstream})
        old_root = self.state / self.revision
        old_root.mkdir()
        old_manifest = self.state / "release.json"
        old_manifest.rename(old_root / "release.json")
        historical_root, release = dev_release.release_at(self.state, self.revision)
        self.assertEqual(historical_root, old_root)
        self.assertEqual(release.alpha_dev["kafka_image"], upstream)
        with self.assertRaisesRegex(ValueError, "Kafka из проекта Registry"):
            AlphaDev.from_config("cabinet-dev", False, self.config(), release)
        with (
            patch("registry_release.subprocess.run") as docker,
            self.assertRaisesRegex(ValueError, "Kafka нужен образ из проекта Registry"),
        ):
            release.pull_and_verify(["analytics-kafka", "credit"])
        docker.assert_not_called()
        original = self.write_env()
        with self.runtime() as (run, _), self.assertRaisesRegex(ValueError, "Kafka из проекта Registry"):
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

        def edit_during_backup(root, script, *arguments, lock_fd=None):
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
        deployment = SimpleNamespace(alpha_dev=self.alpha, release=self.manifest(alpha_dev=self.alpha_metadata), compose=MagicMock())
        containers = {"credit": "credit", "analytics-kafka": "kafka"}
        origin = "https://dev.example.invalid"
        url = origin + verify_release.ALPHA_ROUTE
        image_id = "sha256:" + "f" * 64
        inspected = f"true {deployment.release.kafka_image} {image_id}".encode("ascii")
        for status in (404, 200, 302, 401, None):
            opener = MagicMock()
            if status is None:
                opener.open.side_effect = URLError("unavailable")
            elif status != 200:
                opener.open.side_effect = HTTPError(url, status, "probe", {}, None)
            with (
                self.subTest(status=status),
                patch.object(verify_release, "docker", side_effect=[inspected, b"401", b"503"]),
                patch.object(deployment.release, "local_image_id", return_value=image_id),
                patch.object(verify_release, "analytics_owner", return_value=f"{self.alpha.topic} " + "f" * 64),
            ):
                if status == 404:
                    report = verify_release.verify_alpha(deployment, opener, origin, containers)
                    self.assertEqual(report["public_http"], 404)
                    self.assertEqual(report["http_with_token"], 503)
                    self.assertEqual(report["credit_status_source"], "sql")
                    self.assertEqual(deployment.compose.call_args.args[-2:], ("false", self.alpha.topic))
                    self.assertEqual(report["kafka"], {"container": "kafka", "image_id": image_id,
                                                       "registry_ref": deployment.release.kafka_image,
                                                       "revision": self.revision,
                                                       "upstream_ref": deployment.release.kafka_upstream})
                else:
                    with self.assertRaises(RuntimeError):
                        verify_release.verify_alpha(deployment, opener, origin, containers)
                opener.open.assert_called_once_with(url, timeout=15)

    def test_alpha_runtime_requires_the_exact_kafka_reference_and_config(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        deployment = SimpleNamespace(alpha_dev=self.alpha, release=release)
        image_id = "sha256:" + "f" * 64
        for ref, expected_id, message in (
            (release.kafka_upstream, image_id, "digest из manifest"),
            (release.kafka_image, "sha256:" + "0" * 64, "отличается от проверенного образа"),
        ):
            with (
                self.subTest(ref=ref, expected_id=expected_id),
                patch.object(verify_release, "docker", return_value=f"true {ref} {image_id}".encode("ascii")),
                patch.object(release, "local_image_id", return_value=expected_id),
                self.assertRaisesRegex(RuntimeError, message),
            ):
                verify_release.verify_alpha(deployment, MagicMock(), "https://dev.example.invalid",
                                            {"analytics-kafka": "kafka"})

    def test_enrollment_reuses_backup_and_secrets_across_registration_boundaries(self):
        release = self.manifest(
            images={**self.images, **{name: f"{self.repository}/{name}@sha256:" + "c" * 64 for name in RUNTIME_COMPONENTS}},
            runtime_oci={name: "sha256:" + "c" * 64 for name in (*RUNTIME_COMPONENTS, "kafka")},
            catalog={"dev_mode": "local", "jar_sha256": "c" * 64, "config_digest": "sha256:" + "c" * 64},
            assessment={"dev_mode": "disabled", "jar_sha256": "c" * 64, "kafka_image": self.alpha_metadata["kafka_image"]},
            servicing={"dev_mode": "remote", "jar_sha256": "c" * 64},
            documents={"dev_mode": "disabled", "jar_sha256": "c" * 64},
        )
        previous = {"revision": "b" * 40, "manifest_sha256": "b" * 64}
        original_env = self.write_env(newline=b"\r\n")
        deploy_state = dev_release.write_state
        begin = dev_release.runtime_attempts.begin
        authorize = dev_release.runtime_attempts.authorize_servicing

        for boundary in ("begin", "pending", "hooks", "authorize", "current"):
            for when in ("before", "after"):
                with self.subTest(boundary=boundary, when=when):
                    state = self.state / f"{boundary}-{when}"
                    state.mkdir(mode=0o700)
                    env_file = state / "cabinet.env"
                    env_file.touch(mode=0o600)
                    env_file.write_bytes(original_env)
                    current = state / "current.json"
                    pending = state / "pending.json"
                    deploy_state(current, previous)
                    failed = False
                    captures = []
                    hooked_credentials = []

                    def interrupt(label, operation, *args, **kwargs):
                        nonlocal failed
                        trigger = label == boundary and not failed
                        if trigger:
                            failed = True
                        if trigger and when == "before":
                            raise InterruptedError("injected enrollment interruption")
                        result = operation(*args, **kwargs)
                        if trigger and when == "after":
                            raise InterruptedError("injected enrollment interruption")
                        return result

                    def save(path, value):
                        return interrupt("current" if path == current else "pending", deploy_state, path, value)

                    def capture(_, path, __, source, ___, backup):
                        self.assertEqual(path.read_bytes(), original_env)
                        self.assertEqual(source, previous)
                        self.assertFalse(pending.exists())
                        self.assertTrue((state / "runtime-enrollment.json").is_file())
                        self.assertFalse((state / "hook-secret").exists())
                        captures.append(backup)
                        backup.mkdir(mode=0o700)
                        files = {}
                        for name in ("credit.dump", "identity.dump", "engagement.dump", "documents.tar.gz"):
                            file = backup / name
                            file.write_text("data from the current release")
                            files[name] = {"bytes": file.stat().st_size, "sha256": digest(file)}
                        dev_release.runtime_attempts.write_record(backup / "manifest.json", {"format_version": 2,
                            "backup_complete": True, "source_resumed": True, "source_project": "cabinet-dev",
                            "release": source["revision"], "release_manifest_sha256": source["manifest_sha256"],
                            "databases": {name: {} for name in DATABASES}, "files": files, "documents": {},
                            "storage": {"backends": {}, "s3_volume_included": False}})

                    def open_hooks(environment):
                        attempt = dev_release.runtime_attempts.load(state, release)
                        self.assertEqual(json.loads(pending.read_bytes()), attempt["target"])
                        self.assertEqual(env_file.read_bytes(), environment)
                        hooked_credentials.append(environment)
                        secret = state / "hook-secret"
                        if not secret.exists():
                            secret.write_text("retained private enrollment")
                        return SimpleNamespace(descriptor=SimpleNamespace(mode="remote"))

                    def deployment(_, path, *, release_manifest, env_contents, open_hooks=True):
                        self.assertEqual(path, env_file)
                        self.assertEqual(release_manifest, release.path)
                        servicing = interrupt("hooks", open_hooks_for_release, env_contents) if open_hooks else None
                        return SimpleNamespace(values={"PUBLIC_CABINET_URL": "https://dev.example.invalid"},
                            path=path, release=release, alpha_dev=None, catalog_enabled=True, catalog_mode="local",
                            catalog_state=state / "catalog", runtime_modes={"catalog": "local", "servicing": "remote"},
                            servicing=servicing, documents=None, compose=MagicMock())

                    open_hooks_for_release = open_hooks
                    with (self.runtime(), patch.object(dev_release, "Deployment", side_effect=deployment),
                          patch.object(dev_release, "ensure_tls"), patch.object(dev_release, "capture_previous", side_effect=capture),
                          patch.object(dev_release, "write_state", side_effect=save),
                          patch.object(dev_release.runtime_attempts, "begin",
                                       side_effect=lambda *args, **kwargs: interrupt("begin", begin, *args, **kwargs)),
                          patch.object(dev_release.runtime_attempts, "authorize_servicing",
                                       side_effect=lambda *args: interrupt("authorize", authorize, *args))):
                        with self.assertRaisesRegex(InterruptedError, "injected enrollment interruption"):
                            dev_release.deploy(self.apply, state, release)
                        self.assertTrue((state / "runtime-enrollment.json").exists())
                        dev_release.deploy(self.apply, state, release)
                    self.assertEqual(len(captures), 1)
                    self.assertIn(b"CATALOG_DB_PASSWORD=", env_file.read_bytes())
                    self.assertTrue(all(environment == env_file.read_bytes() for environment in hooked_credentials))
                    self.assertEqual((state / "hook-secret").read_text(), "retained private enrollment")
                    self.assertEqual(json.loads(current.read_bytes())["revision"], release.revision)
                    self.assertFalse(pending.exists())
                    self.assertFalse((state / "runtime-enrollment.json").exists())
                    self.assertFalse((state / "runtime-attempt.json").exists())

    def test_historical_backup_does_not_open_hooks_outside_its_manifest(self):
        release = self.manifest()
        self.write_env()
        with (patch("common.AssessmentDev.open") as assessment,
              patch("common.ServicingDev.open") as servicing,
              patch("common.DocumentsDev.open") as documents):
            deployment = dev_release.Deployment("cabinet-dev", self.env_file, release_manifest=release.path,
                                                historical_backup=True)
            self.assertEqual(deployment.runtime_modes, {})
            for hook in (assessment, servicing, documents):
                hook.assert_not_called()

    def test_failed_shadow_verification_blocks_promotion_until_retry(self):
        release = self.manifest(alpha_dev=self.alpha_metadata)
        self.write_env(*(f"{key}={value}".encode("ascii") for key, value in self.config().items()))
        args = SimpleNamespace(verify_only=False, host_memory_gib=8)
        config = self.config(PUBLIC_CABINET_URL="https://dev.example.invalid")
        deployment = self.alpha_deployment(config)
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
        self.write_env(*(f"{key}={value}".encode("ascii") for key, value in self.config().items()))
        args = SimpleNamespace(verify_only=False, host_memory_gib=8)
        config = self.config(PUBLIC_CABINET_URL="https://dev.example.invalid")
        deployment = self.alpha_deployment(config)
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
