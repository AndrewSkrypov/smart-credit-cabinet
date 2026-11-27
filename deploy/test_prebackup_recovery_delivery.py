"""Only a proven, unstarted failed backup can hand off to another protected release."""
from copy import deepcopy
import importlib.util
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "deploy"))
import runtime_attempts as attempts
from runtime_delivery_support import read_record, write_private, write_record

spec = importlib.util.spec_from_file_location("prebackup_dev_release", ROOT / "deploy/dev-release.py")
dev_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev_release)


class InterruptedHandoff(Exception):
    pass


class PrebackupRecoveryDeliveryTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.original = b"CABINET_ALPHA_DEV_ENABLED=true\r\nORIGINAL=preserved\r\n"
        self.old_env = self.original + b"CATALOG_DB_PASSWORD=old-unactivated\r\n"
        self.new_env = self.original + b"CATALOG_DB_PASSWORD=new-unactivated\r\n"
        self.before = {"revision": "1" * 40, "manifest_sha256": "a" * 64,
                       "alpha_dev": {"generation": "g" + "1" * 16, "read_enabled": False}}
        self.old_target = {**self.before, "revision": "2" * 40, "manifest_sha256": "b" * 64,
                           "catalog": {"mode": "local"}, "documents": {"mode": "local"}}
        self.new_target = {**self.old_target, "revision": "3" * 40, "manifest_sha256": "c" * 64}
        self.scenario("initial")

    def scenario(self, name):
        self.state = self.root / name
        self.state.mkdir(mode=0o700)
        write_private(self.state / "cabinet.env", self.original.decode("utf-8"))
        write_record(self.state / "current.json", self.before)
        self.entry = attempts.enroll(self.state, self.before, self.old_target, self.original, self.old_env)
        self.backup = attempts.enrollment_backup(self.state, self.entry)
        (self.state / "backups").mkdir(mode=0o700)
        self.backup.parent.mkdir(mode=0o700)
        self.backup.mkdir(mode=0o700)
        self.manifest = {"source_project": "cabinet-dev", "release": self.before["revision"],
                         "release_manifest_sha256": self.before["manifest_sha256"],
                         "backup_complete": False, "source_resumed": True}
        write_record(self.backup / "manifest.json", self.manifest)
        self.audit_dir = self.state / "runtime-attempts"
        self.audit = self.audit_dir / (self.entry["attempt"] + "-prebackup-abort.json")

    def snapshot(self):
        return {str(p.relative_to(self.state)): ("link", str(p.readlink())) if p.is_symlink()
                else ("file", p.read_bytes()) for p in self.state.rglob("*") if p.is_symlink() or p.is_file()}

    def recover(self, target=None, environment=None):
        return attempts.recover_prebackup(self.state, self.entry, self.before,
                                         self.new_target if target is None else target,
                                         self.original, self.new_env if environment is None else environment)

    def interrupt_pointer(self, after=False):
        original = attempts.write_record

        def write(path, value, immutable=False):
            switch = path == self.state / "runtime-enrollment.json" and not immutable
            if switch and not after:
                raise InterruptedHandoff("before atomic pointer replacement")
            result = original(path, value, immutable=immutable)
            if switch:
                raise InterruptedHandoff("after atomic pointer replacement")
            return result

        return patch.object(attempts, "write_record", side_effect=write)

    def sealed_handoff(self):
        with self.interrupt_pointer(), self.assertRaises(InterruptedHandoff):
            self.recover()
        self.assertEqual(attempts.enrollment(self.state), self.entry)
        return read_record(self.audit)

    def test_atomic_handoff_preserves_old_files_and_allocates_a_new_backup(self):
        before = self.snapshot()
        replacement = self.recover()
        self.assertNotEqual(replacement["attempt"], self.entry["attempt"])
        self.assertEqual(attempts.enrollment(self.state), replacement)
        self.assertEqual(replacement["target"], self.new_target)
        self.assertEqual(attempts.enrolled_env(self.state, replacement, self.original), self.new_env)
        self.assertNotEqual(attempts.enrollment_backup(self.state, replacement), self.backup)
        after = self.snapshot()
        for path, value in before.items():
            if path != "runtime-enrollment.json":
                self.assertEqual(after[path], value, path)
        self.assertEqual((self.state / "cabinet.env").read_bytes(), self.original)
        self.assertTrue(self.audit.is_file())
        self.assertFalse((self.state / "runtime-attempt.json").exists())

    def test_forbidden_state_cannot_abandon_or_write_any_new_enrollment(self):
        changes = {
            "complete": lambda: self.manifest.update(backup_complete=True),
            "integer-complete": lambda: self.manifest.update(backup_complete=0),
            "missing-complete": lambda: self.manifest.pop("backup_complete"),
            "not-resumed": lambda: self.manifest.update(source_resumed=False),
            "integer-resumed": lambda: self.manifest.update(source_resumed=1),
            "wrong-project": lambda: self.manifest.update(source_project="cabinet-other"),
            "wrong-revision": lambda: self.manifest.update(release="4" * 40),
            "wrong-manifest": lambda: self.manifest.update(release_manifest_sha256="d" * 64),
            "pending-empty": lambda: write_record(self.state / "pending.json", {}),
            "active-pointer-empty": lambda: write_record(self.state / "runtime-attempt.json", {}),
            "immutable-attempt-without-pointer": lambda: write_record(self.audit_dir / (self.entry["attempt"] + ".json"), {}),
            "other-started-receipt": lambda: write_record(self.audit_dir / (self.entry["attempt"] + "-alpha-stores.json"), {}),
            "changed-current": lambda: write_record(self.state / "current.json", {**self.before, "revision": "5" * 40}),
            "changed-env": lambda: write_private(self.state / "cabinet.env", (self.original + b"CHANGED=true\n").decode("utf-8")),
            "changed-saved-env": lambda: write_private(self.audit_dir / (self.entry["attempt"] + "-enrollment.env"), "changed"),
        }
        for name, change in changes.items():
            with self.subTest(name=name):
                self.scenario(name)
                change()
                write_record(self.backup / "manifest.json", self.manifest)
                before = self.snapshot()
                with self.assertRaises((RuntimeError, ValueError, OSError)):
                    self.recover()
                self.assertEqual(self.snapshot(), before)

    def test_unknown_or_missing_backup_and_manifest_fail_closed(self):
        for missing in ("manifest", "backup"):
            with self.subTest(missing=missing):
                self.scenario("missing-" + missing)
                if missing == "manifest":
                    (self.backup / "manifest.json").unlink()
                else:
                    self.backup.rename(self.backup.with_name("preserved-unknown-copy"))
                before = self.snapshot()
                with self.assertRaises((RuntimeError, ValueError, OSError)):
                    self.recover()
                self.assertEqual(self.snapshot(), before)

    def test_symlinks_in_control_paths_or_partial_backup_are_never_followed(self):
        for name in ("manifest", "backup-directory", "audit-directory", "pending-dangling", "backup-member"):
            with self.subTest(name=name):
                self.scenario("link-" + name)
                if name == "manifest":
                    path = self.backup / "manifest.json"
                    moved = self.backup / "preserved-manifest.json"
                    path.rename(moved)
                    path.symlink_to(moved)
                elif name == "backup-directory":
                    moved = self.backup.with_name("preserved-backup")
                    self.backup.rename(moved)
                    self.backup.symlink_to(moved, target_is_directory=True)
                elif name == "audit-directory":
                    moved = self.state / "preserved-attempts"
                    self.audit_dir.rename(moved)
                    self.audit_dir.symlink_to(moved, target_is_directory=True)
                elif name == "pending-dangling":
                    (self.state / "pending.json").symlink_to(self.state / "missing")
                else:
                    (self.backup / "unknown-copy").symlink_to(self.state / "missing")
                before = self.snapshot()
                with self.assertRaises((RuntimeError, ValueError, OSError)):
                    self.recover()
                self.assertEqual(self.snapshot(), before)

    def test_same_manifest_retry_does_not_regenerate_or_repurpose_secrets(self):
        before = self.snapshot()
        with self.assertRaisesRegex(RuntimeError, "другой проверенный выпуск"):
            self.recover(target=self.old_target)
        self.assertEqual(self.snapshot(), before)

    def test_optional_saved_env_cannot_hide_a_symlink_when_secrets_are_unchanged(self):
        for name in ("old", "new"):
            with self.subTest(enrollment=name):
                self.scenario("optional-env-" + name)
                if name == "old":
                    path = self.audit_dir / (self.entry["attempt"] + "-enrollment.env")
                    self.entry["target_env_sha256"] = self.entry["original_env_sha256"]
                    write_record(self.audit_dir / (self.entry["attempt"] + "-enrollment.json"), self.entry)
                    path.unlink()
                else:
                    with self.interrupt_pointer(), self.assertRaises(InterruptedHandoff):
                        self.recover(environment=self.original)
                    audit = read_record(self.audit)
                    path = self.audit_dir / (audit["replacement_attempt"] + "-enrollment.env")
                path.symlink_to(self.state / "cabinet.env")
                before = self.snapshot()
                with self.assertRaises((RuntimeError, ValueError, OSError)):
                    self.recover(environment=self.original if name == "new" else self.new_env)
                self.assertEqual(self.snapshot(), before)

    def test_failure_before_audit_keeps_old_pointer_and_inactive_files_for_audit(self):
        original = attempts.write_record

        def fail(path, value, immutable=False):
            if path == self.audit:
                raise InterruptedHandoff("audit not sealed")
            return original(path, value, immutable=immutable)

        with patch.object(attempts, "write_record", side_effect=fail), self.assertRaises(InterruptedHandoff):
            self.recover()
        self.assertEqual(attempts.enrollment(self.state), self.entry)
        self.assertFalse(self.audit.exists())
        orphans = set(self.audit_dir.iterdir())
        self.assertGreater(len(orphans), 2)
        replacement = self.recover()
        self.assertNotEqual(replacement["attempt"], self.entry["attempt"])
        self.assertTrue(orphans <= set(self.audit_dir.iterdir()))

    def test_replay_before_swap_reuses_the_exact_env_and_fsyncs_existing_audit(self):
        audit = self.sealed_handoff()
        old_audit = self.audit.read_bytes()
        environment = attempts.prebackup_replacement_env(self.state, self.entry, self.before,
                                                          self.new_target["revision"], self.new_target["manifest_sha256"], self.original)
        self.assertEqual(environment, self.new_env)
        events = []
        real_write, real_sync = attempts.write_record, attempts.fsync_dir

        def write(path, value, immutable=False):
            if path == self.state / "runtime-enrollment.json":
                events.append("swap")
            return real_write(path, value, immutable=immutable)

        def sync(path):
            if path == self.audit_dir:
                events.append("audit-fsync")
            return real_sync(path)

        with (patch.object(attempts, "uuid4", side_effect=AssertionError("replay must not allocate or regenerate")),
              patch.object(attempts, "write_record", side_effect=write), patch.object(attempts, "fsync_dir", side_effect=sync)):
            replacement = self.recover(environment=environment)
        self.assertEqual(replacement["attempt"], audit["replacement_attempt"])
        self.assertLess(events.index("audit-fsync"), events.index("swap"))
        self.assertEqual(self.audit.read_bytes(), old_audit)

    def test_crash_after_atomic_swap_leaves_a_normal_resumable_enrollment(self):
        with self.interrupt_pointer(after=True), self.assertRaises(InterruptedHandoff):
            self.recover()
        replacement = attempts.enrollment(self.state)
        self.assertEqual(replacement["target"], self.new_target)
        self.assertNotEqual(replacement["attempt"], self.entry["attempt"])
        self.assertEqual(attempts.enrolled_env(self.state, replacement, self.original), self.new_env)
        self.assertEqual(read_record(self.backup / "manifest.json"), self.manifest)

    def test_existing_audit_directory_must_sync_before_pointer_swap(self):
        self.sealed_handoff()
        before = self.snapshot()
        with (patch.object(attempts, "fsync_dir", side_effect=OSError("audit sync failed")),
              self.assertRaisesRegex(OSError, "audit sync failed")):
            self.recover()
        self.assertEqual(self.snapshot(), before)
        self.assertEqual(attempts.enrollment(self.state), self.entry)

    def test_inputs_are_rechecked_after_audit_sync_before_pointer_swap(self):
        for name in ("current", "copy", "env", "audit", "new-env", "new-started-receipt"):
            with self.subTest(name=name):
                self.scenario("before-swap-" + name)
                audit = self.sealed_handoff()
                real_sync = attempts.fsync_dir
                modified = []

                def change_then_sync(path):
                    if path == self.audit_dir and not modified:
                        if name == "current":
                            write_record(self.state / "current.json", {**self.before, "revision": "4" * 40})
                        elif name == "copy":
                            write_record(self.backup / "manifest.json", {**self.manifest, "extra": "changed"})
                        elif name == "env":
                            write_private(self.state / "cabinet.env", "changed")
                        elif name == "audit":
                            write_record(self.audit, {**audit, "extra": "changed"})
                        elif name == "new-env":
                            write_private(self.audit_dir / (audit["replacement_attempt"] + "-enrollment.env"), "changed")
                        else:
                            write_record(self.audit_dir / (audit["replacement_attempt"] + "-alpha-stores.json"), {})
                        modified.append(self.snapshot())
                    real_sync(path)

                with patch.object(attempts, "fsync_dir", side_effect=change_then_sync), self.assertRaises(RuntimeError):
                    self.recover()
                self.assertEqual(self.snapshot(), modified[0])
                self.assertEqual(attempts.enrollment(self.state), self.entry)

    def test_sealed_handoff_rejects_other_target_or_env_without_pointer_change(self):
        self.sealed_handoff()
        before = self.snapshot()
        other = {**self.new_target, "revision": "4" * 40}
        with self.assertRaises(RuntimeError):
            attempts.prebackup_replacement_env(self.state, self.entry, self.before,
                                               other["revision"], other["manifest_sha256"], self.original)
        with self.assertRaises(RuntimeError):
            self.recover(target=other)
        with self.assertRaises(RuntimeError):
            self.recover(environment=self.new_env + b"CHANGED=true\n")
        self.assertEqual(self.snapshot(), before)

    def test_sealed_handoff_rechecks_current_copy_env_and_audit(self):
        for name in ("current", "copy", "env", "audit", "new-entry", "new-env", "new-attempt-without-pointer"):
            with self.subTest(name=name):
                self.scenario("sealed-" + name)
                audit = self.sealed_handoff()
                identifier = audit["replacement_attempt"]
                if name == "current":
                    write_record(self.state / "current.json", {**self.before, "revision": "4" * 40})
                elif name == "copy":
                    write_record(self.backup / "manifest.json", {**self.manifest, "extra": "changed"})
                elif name == "env":
                    write_private(self.state / "cabinet.env", (self.original + b"CHANGED=true\n").decode("utf-8"))
                elif name == "audit":
                    write_record(self.audit, {**audit, "replacement_env_sha256": "e" * 64})
                elif name == "new-entry":
                    write_record(self.audit_dir / (identifier + "-enrollment.json"), {})
                elif name == "new-env":
                    write_private(self.audit_dir / (identifier + "-enrollment.env"), "corrupt")
                else:
                    write_record(self.audit_dir / (identifier + ".json"), {})
                before = self.snapshot()
                with self.assertRaises((RuntimeError, ValueError, OSError)):
                    self.recover()
                self.assertEqual(self.snapshot(), before)

    def test_controller_validates_document_mode_and_alpha_before_handoff(self):
        for invalid in ("documents", "alpha"):
            with self.subTest(invalid=invalid):
                self.scenario("controller-" + invalid)
                release = SimpleNamespace(revision=self.new_target["revision"], checksum=self.new_target["manifest_sha256"],
                                          path=self.state / "release.json", catalog=None, alpha_dev={}, has_all_runtimes=True)
                alpha_state = deepcopy(self.before["alpha_dev"])
                if invalid == "alpha":
                    alpha_state["generation"] = "g" + "2" * 16
                alpha = SimpleNamespace(state=lambda: alpha_state, generation=alpha_state["generation"], read_enabled=False)
                mode = "remote" if invalid == "documents" else "local"
                deployment = SimpleNamespace(values={"CABINET_ALPHA_DEV_ENABLED": "true"}, alpha_dev=alpha,
                                             catalog_enabled=False, catalog_state=self.state / "catalog",
                                             runtime_modes={"documents": mode})
                before = self.snapshot()
                with (patch.object(dev_release, "Deployment", return_value=deployment),
                      patch.object(attempts, "recover_prebackup", side_effect=AssertionError("invalid target must not abandon"))):
                    with self.assertRaises(RuntimeError):
                        dev_release.deploy(SimpleNamespace(verify_only=False), self.state, release)
                self.assertEqual(self.snapshot(), before)

    def test_controller_replay_uses_persisted_new_env_before_catalog_rng(self):
        target = deepcopy(self.new_target)
        target.pop("documents")
        target["catalog"] = {"mode": "local"}
        release = SimpleNamespace(revision=target["revision"], checksum=target["manifest_sha256"],
                                  path=self.state / "release.json", catalog={"dev_mode": "local"}, alpha_dev={}, has_all_runtimes=True)
        alpha_state = self.before["alpha_dev"]
        alpha = SimpleNamespace(state=lambda: alpha_state, generation=alpha_state["generation"], read_enabled=False)
        seen = []

        def deployment(*args, **kwargs):
            self.assertFalse(kwargs["open_hooks"])
            seen.append(kwargs["env_contents"])
            return SimpleNamespace(values={"CABINET_ALPHA_DEV_ENABLED": "true"}, alpha_dev=alpha,
                                   catalog_enabled=True, catalog_mode="local", catalog_state=self.state / "catalog", runtime_modes={})

        with (patch.object(dev_release, "Deployment", side_effect=deployment),
              patch.object(dev_release, "append_secrets", return_value=self.new_env), self.interrupt_pointer(),
              self.assertRaises(InterruptedHandoff)):
            dev_release.deploy(SimpleNamespace(verify_only=False), self.state, release)
        with (patch.object(dev_release, "Deployment", side_effect=deployment),
              patch.object(dev_release, "append_secrets", side_effect=AssertionError("must reuse sealed ENV")),
              patch.object(dev_release, "capture_previous", side_effect=InterruptedHandoff("new backup starts normally")),
              self.assertRaisesRegex(InterruptedHandoff, "new backup")):
            dev_release.deploy(SimpleNamespace(verify_only=False), self.state, release)
        self.assertEqual(seen, [self.new_env, self.new_env])
        replacement = attempts.enrollment(self.state)
        self.assertEqual(replacement["target"], target)
        self.assertEqual(attempts.enrolled_env(self.state, replacement, self.original), self.new_env)
        self.assertEqual(read_record(self.backup / "manifest.json"), self.manifest)


if __name__ == "__main__":
    unittest.main()
