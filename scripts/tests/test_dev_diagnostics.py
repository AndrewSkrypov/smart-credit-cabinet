"""Protected Dev diagnostics regression cases. Docker and host state are fixtures."""
import contextlib
import fcntl
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "deploy"))
import dev_diagnostics as diagnostics

spec = importlib.util.spec_from_file_location("diagnostic_dev_release", ROOT / "deploy/dev-release.py")
dev_release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dev_release)

SERVICE_ID = "b" * 64
NETWORK_ID = "c" * 64
OTHER_NETWORK_ID = "e" * 64
IMAGE_ID = "sha256:" + "d" * 64
SECRET = "fake_secret_must_not_be_printed"
REVISION = "a" * 40
REPOSITORY = "registry.example.invalid/cabinet/project"
REF = REPOSITORY + "/credit@sha256:" + "f" * 64


class DiagnosticsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.releases = Path(directory.name)
        self.root = self.releases / REVISION
        self.root.mkdir()
        self.state = self.releases / "state"
        self.state.mkdir(mode=0o700)
        target_dir = self.releases / diagnostics.TARGET_REVISION
        target_dir.mkdir()
        self.target = target_dir / "release.json"
        self.target.write_text(json.dumps({
            "revision": diagnostics.TARGET_REVISION,
            "images": {"identity": REPOSITORY + "/identity@sha256:" + "e" * 64, "credit": REF},
            "alpha_dev": {"image": REPOSITORY + "/analytics@sha256:" + "a" * 64,
                          "kafka_image": REPOSITORY + "/kafka@sha256:" + "b" * 64},
        }))
        self.target_hash = hashlib.sha256(self.target.read_bytes()).hexdigest()
        self.request = {"version": 1, "operation": "diagnose-v1", "revision": REVISION,
                        "target_revision": diagnostics.TARGET_REVISION, "target_manifest_sha256": self.target_hash}
        self.manifest = self.root / "release.json"
        self.manifest.write_text(json.dumps(self.request))
        self.lock = self.state / "deploy.lock"
        self.lock.write_bytes(b"existing lock\n")
        self.current = {"revision": diagnostics.TARGET_REVISION, "manifest_sha256": self.target_hash,
                        "alpha_dev": {"generation": "g" + "a" * 16, "read_enabled": False,
                                      "password": SECRET}, "token": SECRET}
        (self.state / "current.json").write_text(json.dumps(self.current))
        (self.state / "cabinet.env").write_text(f"PASSWORD={SECRET}")
        (self.state / "alpha-generations.json").write_text("[]")
        self.forbidden = ("RegistryRelease", "Deployment", "deploy", "run", "provision", "verify",
                          "read_credentials", "replace_env", "with_alpha_config", "write_state", "rollout_metadata")
        self.raw_container = {
            "id": SERVICE_ID, "project": "cabinet-dev", "service": "credit", "oneoff": "False",
            "state": "exited", "health": "unhealthy", "exit_code": 137, "oom_killed": True,
            "started_at": "2026-11-26T20:00:00Z", "finished_at": "2026-11-26T20:01:00.123Z",
            "error": SECRET, "registry_ref": REF, "image_id": IMAGE_ID,
            "networks": ["cabinet-dev_grafana_edge", SECRET, None],
        }
        self.raw_networks = [
            {"id": NETWORK_ID, "project": "cabinet-dev", "name": "cabinet-dev_grafana_edge",
             "logical": "grafana_edge", "internal": True,
             "subnets": ["172.30.41.0/28", SECRET, "2001:db8::/64", None]},
            {"id": OTHER_NETWORK_ID, "project": "cabinet-dev", "name": SECRET,
             "logical": SECRET, "internal": False, "subnets": ["172.30.43.0/24", None]},
        ]

    def local_docker(self, kind, operation, *args):
        if operation == "ls":
            self.assertIn("label=com.docker.compose.project=cabinet-dev", args)
            self.assertIn("--no-trunc", args)
            self.assertIn(kind, {"container", "network"})
            return (SERVICE_ID if kind == "container" else NETWORK_ID + "\n" + OTHER_NETWORK_ID).encode()
        self.assertEqual(operation, "inspect")
        self.assertEqual(args[0], "--format")
        self.assertNotIn(".Config.Env", args[1])
        self.assertNotIn(".State.Health.Log", args[1])
        self.assertNotIn(".HostConfig", args[1])
        if kind == "container":
            self.assertEqual(args[2:], (SERVICE_ID,))
            objects = [self.raw_container]
        elif kind == "network":
            self.assertEqual(args[2:], (NETWORK_ID, OTHER_NETWORK_ID))
            objects = self.raw_networks
        else:
            self.assertEqual(kind, "image")
            self.assertEqual(args[2:], (IMAGE_ID,))
            objects = [{"id": IMAGE_ID, "revision": diagnostics.TARGET_REVISION}]
        return "\n".join(map(json.dumps, objects)).encode()

    def execute(self, should_succeed=True, docker=None, args=()):
        original = {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in self.state.iterdir()}
        argv = ["dev-release.py", "--state-dir", str(self.state), "--release-manifest", str(self.manifest), *args]
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.ExitStack() as stack:
            stack.enter_context(contextlib.redirect_stdout(stdout))
            stack.enter_context(contextlib.redirect_stderr(stderr))
            stack.enter_context(patch.object(sys, "argv", argv))
            stack.enter_context(patch.object(dev_release, "ROOT", self.root))
            stack.enter_context(patch.object(diagnostics, "STATE_DIR", self.state))
            stack.enter_context(patch.object(diagnostics, "TARGET_MANIFEST_SHA256", self.target_hash))
            forbidden = [stack.enter_context(patch.object(dev_release, name,
                         side_effect=AssertionError(f"deployment function called: {name}"))) for name in self.forbidden]
            invocation = stack.enter_context(patch.object(diagnostics, "docker_read", side_effect=docker or self.local_docker))
            if should_succeed:
                self.assertIsNone(dev_release.main())
            else:
                with self.assertRaises(SystemExit) as exited:
                    dev_release.main()
                self.assertEqual(exited.exception.code, 1)
                self.assertEqual(stdout.getvalue(), "")
            for callable_ in forbidden:
                callable_.assert_not_called()
        self.assertEqual({p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in self.state.iterdir()}, original)
        self.assertNotIn(SECRET, stdout.getvalue() + stderr.getvalue())
        return stdout.getvalue(), stderr.getvalue(), invocation

    def test_read_only_with_pending_and_without_pending_even_when_current_matches(self):
        for pending in (True, False):
            with self.subTest(pending=pending):
                path = self.state / "pending.json"
                if pending:
                    path.write_text(json.dumps(self.current))
                else:
                    path.unlink()
                stdout, stderr, invocation = self.execute()
                self.assertEqual(stderr, "")
                self.assertEqual(invocation.call_count, 5)
                report = json.loads(stdout)
                self.assertEqual(report["current"]["revision"], diagnostics.TARGET_REVISION)
                self.assertEqual(report["pending"]["present"], pending)
                self.assertFalse(report["current"]["alpha_dev"]["read_enabled"])
                container, = report["containers"]
                self.assertEqual(container["registry_ref"], REF)
                self.assertEqual(container["oci_revision"], diagnostics.TARGET_REVISION)
                self.assertEqual(container["image_id"], IMAGE_ID)
                self.assertEqual(container["networks"], ["cabinet-dev_grafana_edge", "unclassified"])
                self.assertEqual(container["error_categories"], ["unclassified", "oom_killed", "unhealthy", "nonzero_exit"])
                known, unknown = report["networks"]
                self.assertEqual(known["labels"]["network"], "grafana_edge")
                self.assertEqual(known["subnets"], ["172.30.41.0/28", "unclassified", "unclassified"])
                self.assertEqual(unknown["name"], "unclassified")
                self.assertEqual(unknown["subnets"], [])

    def test_secret_injection_in_container_labels_reference_and_state_is_redacted(self):
        self.raw_container.update(service=SECRET, oneoff=SECRET, health=SECRET, started_at=SECRET,
                                  registry_ref=f"{REPOSITORY}/{SECRET}@sha256:" + "f" * 64,
                                  error=f"failed to bind host port: {SECRET}")
        stdout, _, _ = self.execute()
        container, = json.loads(stdout)["containers"]
        self.assertEqual(container["labels"]["service"], "unclassified")
        self.assertEqual(container["labels"]["oneoff"], "unclassified")
        self.assertEqual(container["registry_ref"], "unclassified")
        self.assertEqual(container["health"], "unclassified")
        self.assertIsNone(container["started_at"])
        self.assertIn("port_conflict", container["error_categories"])

    def test_malformed_envelopes_never_reach_docker_or_the_release_path(self):
        valid = json.dumps(self.request)
        invalid = [
            valid.replace('"version": 1', '"version": 1, "version": 1'),
            json.dumps({**self.request, "version": True}),
            json.dumps({**self.request, "exec": SECRET, "environment": SECRET}),
            json.dumps({**self.request, "operation": "shell"}),
            json.dumps({**self.request, "target_revision": "c" * 40}),
            json.dumps({**self.request, "revision": "c" * 40}),
            json.dumps({**self.request, "target_manifest_sha256": "f" * 64}),
            json.dumps({key: value for key, value in self.request.items() if key != "operation"}),
            valid + " " * diagnostics.MAX_REQUEST,
            "{" + SECRET,
        ]
        for raw in invalid:
            with self.subTest(index=invalid.index(raw)):
                self.manifest.write_text(raw)
                self.execute(should_succeed=False)[2].assert_not_called()

    def test_missing_lock_corrupt_state_and_target_mismatch_fail_closed(self):
        self.lock.unlink()
        self.execute(should_succeed=False)[2].assert_not_called()
        self.assertFalse(self.lock.exists())
        self.lock.touch()
        self.target.write_bytes(self.target.read_bytes() + b" ")
        self.execute(should_succeed=False)[2].assert_not_called()
        self.target.write_bytes(self.target.read_bytes()[:-1])
        for raw in (b'{}', SECRET.encode(), b" " * (diagnostics.MAX_FILE + 1)):
            with self.subTest(size=len(raw)):
                (self.state / "pending.json").write_bytes(raw)
                self.execute(should_succeed=False)[2].assert_not_called()
        (self.state / "pending.json").unlink()
        with self.lock.open("rb") as active:
            fcntl.flock(active, fcntl.LOCK_EX)
            self.execute(should_succeed=False)[2].assert_not_called()

    def test_missing_docker_and_excess_objects_fail_closed(self):
        for docker, category in (
            (lambda *args: (_ for _ in ()).throw(RuntimeError("Docker: docker_unavailable")), "docker_unavailable"),
            (lambda *args: b"\n".join(f"{index:064x}".encode() for index in range(65)), "unclassified_object_list"),
        ):
            with self.subTest(category=category):
                _, stderr, _ = self.execute(should_succeed=False, docker=docker)
                self.assertIn(category, stderr)

    def test_oversized_report_fails_closed(self):
        with patch.object(diagnostics, "MAX_REPORT", 10):
            _, stderr, _ = self.execute(should_succeed=False)
        self.assertIn("лимит отчёта", stderr)

    def test_normal_release_still_calls_its_existing_parser_and_deploy(self):
        self.manifest.write_text(json.dumps({"revision": REVISION, "images": {}}))
        args = ["dev-release.py", "--state-dir", str(self.state), "--release-manifest", str(self.manifest)]
        with patch.object(sys, "argv", args), patch.object(dev_release, "ROOT", self.root), \
                patch.object(dev_release, "RegistryRelease") as release, patch.object(dev_release, "deploy") as normal, \
                patch.object(dev_release, "diagnose") as diagnostic:
            release.return_value.revision = REVISION
            release.return_value.path = self.manifest
            dev_release.main()
        release.assert_called_once_with(self.manifest)
        normal.assert_called_once()
        diagnostic.assert_not_called()

    def test_compose_failure_emits_only_known_category(self):
        deployment = dev_release.Deployment.__new__(dev_release.Deployment)
        deployment.command = ["docker", "compose"]
        deployment.environment = {}
        deployment.release = True
        for raw, category in (("pool overlaps: " + SECRET, "network_overlap"), (SECRET, "unclassified")):
            result = subprocess.CompletedProcess([], 1, "", raw)
            with self.subTest(category=category), patch("common.subprocess.run", return_value=result):
                with self.assertRaisesRegex(RuntimeError, f"категория {category}$") as failure:
                    deployment.compose("up")
                self.assertNotIn(SECRET, str(failure.exception))


class DockerBoundariesTest(unittest.TestCase):
    def run_substitute(self, code, timeout=8):
        popen = subprocess.Popen

        def launch(command, **kwargs):
            self.assertEqual(command[:3], ["docker", "--host", "unix:///var/run/docker.sock"])
            self.assertEqual(set(kwargs["env"]), {"PATH", "LC_ALL"})
            return popen([sys.executable, "-c", code], **kwargs)

        with patch.object(diagnostics, "READ_TIMEOUT", timeout), patch.object(subprocess, "Popen", side_effect=launch):
            return diagnostics.docker_read("container", "ls")

    def test_error_categories_do_not_emit_stderr(self):
        with self.assertRaisesRegex(RuntimeError, "^Docker: disk_full$"):
            self.run_substitute(f"import sys; print('no space left on device {SECRET}', file=sys.stderr); sys.exit(1)")
        with self.assertRaisesRegex(RuntimeError, "^Docker: docker_unavailable$"):
            self.run_substitute(f"import sys; print('failed to connect to the Docker API {SECRET}', file=sys.stderr); sys.exit(1)")
        with self.assertRaisesRegex(RuntimeError, "^Docker: unclassified$"):
            self.run_substitute(f"import sys; print('{SECRET}', file=sys.stderr); sys.exit(1)")

    def test_real_subprocess_streams_are_bounded_and_deadlined(self):
        with self.assertRaisesRegex(RuntimeError, "^Docker: output_limit$"):
            self.run_substitute("print('s' * 140000)")
        with self.assertRaisesRegex(RuntimeError, "^Docker: timeout$"):
            self.run_substitute("import time; time.sleep(5)", timeout=0.2)
        with patch.object(subprocess, "Popen", side_effect=FileNotFoundError()):
            with self.assertRaisesRegex(RuntimeError, "^Docker: docker_unavailable$"):
                diagnostics.docker_read("container", "ls")


class DiagnosticJobTest(unittest.TestCase):
    def test_job_verifies_the_dev_environment_without_release_inputs(self):
        pipeline = yaml.safe_load((ROOT / "ci/gitlab/release.yml").read_text())
        job = pipeline["dev-diagnostics"]
        self.assertEqual(job["environment"], {"name": "dev", "action": "verify"})
        self.assertEqual(job["resource_group"], pipeline["dev-deploy"]["resource_group"])
        self.assertEqual(job["tags"], pipeline["dev-deploy"]["tags"])
        self.assertEqual(job["needs"], [])
        self.assertNotIn("dependencies", job)
        self.assertNotIn("image", job)
        rule, = job["rules"]
        for fragment in ('CABINET_DEV_DIAGNOSTICS == "1"', 'CI_COMMIT_REF_PROTECTED == "true"',
                         'CI_PIPELINE_SOURCE != "merge_request_event"', 'CI_PIPELINE_SOURCE != "schedule"'):
            self.assertIn(fragment, rule["if"])
        self.assertEqual(rule["when"], "manual")
        self.assertEqual(job["artifacts"]["paths"], ["dev-diagnostics.json"])

    def test_ssh_transfer_uses_one_source_archive_and_only_the_diagnostic_envelope(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("ci/gitlab/diagnose-dev.sh", "deploy/dev_diagnostics.py"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes((ROOT / name).read_bytes())
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=Diagnostic test",
                            "-c", "user.email=fixture@example.invalid", "-c", "commit.gpgSign=false",
                            "commit", "-qm", "Fixture"], check=True)
            revision = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
            mock = root / "ssh"
            mock.write_text("""#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys
import tarfile

operation, revision = sys.argv[-1].split()
assert revision == os.environ["CI_COMMIT_SHA"]
assert sys.argv[-2] == "cabinet-deploy@pump-final.test"
assert "StrictHostKeyChecking=yes" in sys.argv and "IdentitiesOnly=yes" in sys.argv
with Path("ssh-calls.txt").open("a") as calls:
    print(operation, file=calls)
if operation == "stage":
    with tarfile.open(fileobj=sys.stdin.buffer, mode="r|*") as archive:
        files = {entry.name for entry in archive}
    assert {"ci/gitlab/diagnose-dev.sh", "deploy/dev_diagnostics.py"} <= files
else:
    assert operation == "deploy"
    request = json.load(sys.stdin)
    assert set(request) == {"version", "operation", "revision", "target_revision", "target_manifest_sha256"}
    assert request["operation"] == "diagnose-v1" and request["revision"] == revision
    assert request["target_revision"] == "3e434c0adee63917d2fe088e8939207de5b84452"
    assert request["target_manifest_sha256"] == "07df658356bbc5e3501b0b00321f0177b6f72f6727e36ba624dfd44b0557791a"
    report = {**request, **dict(current={}, pending={}, containers=[], networks=[], error_counts={})}
    if os.environ.get("WRONG_REVISION") == "1":
        report["revision"] = "0" * 40
    print(json.dumps(report))
""")
            mock.chmod(0o700)
            env = {**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"],
                   "CI_COMMIT_SHA": revision, "CI_COMMIT_REF_PROTECTED": "true", "CABINET_DEV_DIAGNOSTICS": "1",
                   "CABINET_DEV_SSH_HOST": "pump-final.test", "CABINET_DEV_SSH_KEY": "/fixture/key",
                   "CABINET_DEV_KNOWN_HOSTS": "/fixture/known-hosts"}
            command = ["bash", "ci/gitlab/diagnose-dev.sh"]
            output = subprocess.run(command, cwd=root, env=env, capture_output=True, text=True)
            self.assertEqual(output.returncode, 0, output.stderr)
            self.assertEqual((root / "ssh-calls.txt").read_text().splitlines(), ["stage", "deploy"])
            artifact = root / "dev-diagnostics.json"
            self.assertEqual(json.loads(artifact.read_text())["revision"], revision)
            artifact.unlink()
            wrong = subprocess.run(command, cwd=root, env={**env, "WRONG_REVISION": "1"},
                                   capture_output=True, text=True)
            self.assertNotEqual(wrong.returncode, 0)
            self.assertFalse(artifact.exists())
            (root / "ssh-calls.txt").unlink()
            unprotected = subprocess.run(command, cwd=root, env={**env, "CI_COMMIT_REF_PROTECTED": "false"},
                                         capture_output=True, text=True)
            self.assertNotEqual(unprotected.returncode, 0)
            self.assertFalse((root / "ssh-calls.txt").exists())


if __name__ == "__main__":
    unittest.main()
