"""Small source/configuration checks; no Docker, JVM, network or Dev operations."""

from copy import deepcopy
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("projection_smoke", ROOT / "ci/alpha/projection-smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
SECRET = "synthetic-private-credential-not-for-output"


def wiring():
    return {"services": {
        "analytics": {"environment": {"SPRING_PROFILES_ACTIVE": "prod", "CABINET_VALKEY_ENABLED": "true",
                       "CABINET_VALKEY_HOST": "valkey", "CABINET_VALKEY_PASSWORD": SECRET},
                      "networks": dict.fromkeys(smoke.PRIVATE_NETWORKS),
                      "depends_on": {"valkey": {"condition": "service_healthy"}}},
        "valkey": {"environment": {"VALKEY_PASSWORD": SECRET}, "networks": {"cabinet": {}, "alpha-cache": {}}},
        "credit": {"environment": {"ANALYTICS_STATUS_READ_ENABLED": "false"}},
        "gateway": {"networks": {"cabinet": {}}}, "analytics-db": {}, "analytics-kafka": {},
    }, "networks": {key: {"internal": True} for key in smoke.PRIVATE_NETWORKS}}


class ConfigurationTest(unittest.TestCase):
    def test_source_keeps_cache_private_and_old_networks_intact(self):
        config = yaml.safe_load((ROOT / "deploy/compose.dev-alpha.yaml").read_text())
        services = config["services"]
        self.assertEqual(set(services["analytics"]["networks"]), smoke.PRIVATE_NETWORKS)
        self.assertEqual(set(services["valkey"]["networks"]), {"cabinet", "alpha-cache"})
        self.assertEqual({name for name, service in services.items() if "alpha-cache" in service.get("networks", [])},
                         {"analytics", "valkey"})
        self.assertTrue(all(config["networks"][key]["internal"] for key in smoke.PRIVATE_NETWORKS))
        self.assertEqual(services["analytics"]["depends_on"]["valkey"]["condition"], "service_healthy")
        self.assertNotIn("ports", services["analytics"])
        self.assertIn("VALKEY_PASSWORD:?", services["analytics"]["environment"]["CABINET_VALKEY_PASSWORD"])

    def test_rendered_config_rejects_gateway_access_ports_or_different_credentials(self):
        env = {"VALKEY_PASSWORD": SECRET}
        smoke.check_wiring(wiring(), env)
        for change in (
            lambda x: x["services"]["analytics"]["networks"].update(cabinet={}),
            lambda x: x["services"]["gateway"]["networks"].update({"alpha-cache": {}}),
            lambda x: x["services"]["valkey"].update(ports=["6379:6379"]),
            lambda x: x["services"]["analytics"]["environment"].update(CABINET_VALKEY_PASSWORD="other"),
            lambda x: x["networks"]["alpha-cache"].update(internal=False),
            lambda x: x["services"]["credit"]["environment"].update(ANALYTICS_STATUS_READ_ENABLED="true"),
        ):
            config = deepcopy(wiring())
            change(config)
            with self.assertRaises(smoke.ProjectionError):
                smoke.check_wiring(config, env)

    def test_environment_drops_operator_overrides_and_preserves_checked_endpoint(self):
        endpoint = "unix:///tmp/ci-only.sock"
        with patch.dict(os.environ, {"CABINET_ALPHA_DEV_JWT_SECRET": SECRET, "DOCKER_CONTEXT": SECRET,
                                    "COMPOSE_FILE": SECRET, "AWS_SECRET_ACCESS_KEY": SECRET}, clear=True):
            env = smoke.synthetic_env("cabinet-alpha-projection-42", endpoint, "a" * 40)
        self.assertNotIn(SECRET, env.values())
        self.assertNotIn("DOCKER_CONTEXT", env)
        self.assertNotIn("COMPOSE_FILE", env)
        self.assertNotIn("AWS_SECRET_ACCESS_KEY", env)
        self.assertEqual(env["DOCKER_HOST"], endpoint)
        self.assertEqual(env["CABINET_CI_DOCKER_HOST"], endpoint)
        self.assertNotEqual(env["CABINET_ALPHA_DEV_PG_ADMIN_PASSWORD"], env["CABINET_ALPHA_DEV_PG_APP_PASSWORD"])

    def test_rules_include_only_requested_new_branch_and_always_cleanup(self):
        pipeline = yaml.safe_load((ROOT / "ci/alpha/gitlab.yml").read_text())
        predicate = pipeline[".alpha-pilot-verify"]["rules"][1]["if"]
        self.assertIn('SOURCE_BRANCH_NAME == "feature/alpha-private-valkey"', predicate)
        self.assertNotIn("codex/alpha-dev-valkey", predicate)
        self.assertIn('TARGET_BRANCH_NAME == "feat/final-platform-release"', predicate)
        job = pipeline["alpha-pilot-analytics-jar"]
        self.assertEqual(job["after_script"], ["python3 -B ci/alpha/projection-smoke.py --cleanup-only"])
        self.assertEqual(job["artifacts"]["when"], "always")
        self.assertIn("ci-artifacts/alpha-projection.json", job["artifacts"]["paths"])

    def test_acl_is_narrowed_without_weakening_runtime_guard(self):
        sql = (ROOT / "ci/alpha/init-analytics.sql").read_text()
        self.assertIn("analytics, postgres, template0, template1 FROM PUBLIC", sql)
        text = (ROOT / "ci/alpha/smoke.sh").read_text()
        self.assertIn("datname <> current_database()", text)
        self.assertIn("has_database_privilege(current_user, oid, 'CONNECT')", text)
        guard = (ROOT / "libs/platform-common/src/main/kotlin/ru/creditcabinet/platform/database/RuntimeDatabaseGuard.kt").read_text()
        self.assertIn("d.datname <> current_database()", guard)


class GuardAndCleanupTest(unittest.TestCase):
    def test_numeric_job_scope_and_no_default_daemon(self):
        self.assertEqual(smoke.job_project("42"), "cabinet-alpha-projection-42")
        for bad in ("", "../dev", "42;prune", "1" * 25):
            with self.assertRaises(smoke.ProjectionError):
                smoke.job_project(bad)
        for env in ({}, {"DOCKER_HOST": "unix:///var/run/docker.sock"},
                    {"CABINET_CI_DOCKER_HOST": "tcp://example.invalid:2375"},
                    {"CABINET_CI_DOCKER_HOST": "unix:///ci.sock", "DOCKER_CONTEXT": "other"},
                    {"CABINET_CI_DOCKER_HOST": "unix:///ci.sock", "DOCKER_HOST": "unix:///dev.sock"}):
            with self.assertRaises(smoke.ProjectionError):
                smoke.endpoint_from_env(env)
        with patch.object(Path, "stat", return_value=SimpleNamespace(st_mode=stat.S_IFSOCK)), \
                patch.object(Path, "resolve", return_value=Path("/ci.sock")):
            self.assertEqual(smoke.endpoint_from_env({"CABINET_CI_DOCKER_HOST": "unix:///ci.sock"}), "unix:///ci.sock")

    def test_engine_label_required_before_operations(self):
        client = smoke.Docker("unix:///ci.sock", "/tmp/config", {})
        with patch.object(client, "run", return_value='["other=true"]'):
            with self.assertRaises(smoke.ProjectionError):
                client.guard()
        with patch.object(client, "run", return_value='["cabinet-ci=true"]'):
            client.guard()

    def test_cleanup_only_deletes_exact_labeled_job_objects(self):
        client = MagicMock()
        project = smoke.job_project("42")
        references = {"container": "1" * 64, "network": "2" * 64,
                      "volume": project + "-analytics-data", "image": "sha256:" + "3" * 64}
        def run(operation, kind, action, *args, **options):
            return references[kind] if operation == "cleanup_discovery" else ""
        client.run.side_effect = run
        client.inspect.side_effect = lambda kind, *_: {
            smoke.PROJECT_LABEL if kind != "image" else smoke.IMAGE_PROJECT_LABEL: project, smoke.JOB_LABEL: "42"}
        smoke.cleanup_owned(client, project, "42")
        client.guard.assert_called_once()
        calls = client.run.call_args_list
        self.assertEqual(sum(call.args[0] == "owned_cleanup" for call in calls), 4)
        for call in calls:
            self.assertNotIn("prune", call.args)
            if call.args[0] == "cleanup_discovery":
                self.assertIn(f"label={smoke.IMAGE_PROJECT_LABEL if call.args[1] == 'image' else smoke.PROJECT_LABEL}={project}", call.args)

    def test_cleanup_refuses_objects_returned_with_foreign_labels(self):
        client = MagicMock()
        client.run.return_value = "1" * 64
        client.inspect.return_value = {smoke.PROJECT_LABEL: "cabinet-dev"}
        with self.assertRaises(smoke.ProjectionError):
            smoke.cleanup_owned(client, smoke.job_project("42"), "42")
        self.assertFalse(any(call.args[0] == "owned_cleanup" for call in client.run.call_args_list))

    def test_main_sigterm_cleans_owned_objects_and_retains_failed_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / "receipt.json"
            args = SimpleNamespace(cleanup_only=False, receipt=receipt)
            def checked(operation, *_args, **_kwargs):
                return {"source_revision": "a" * 40, "source_tree": "b" * 40,
                        "clean_source": "", "compose_render": json.dumps(wiring())}[operation]
            def terminate(*_args):
                smoke.interrupted(signal.SIGTERM, None)
            with (patch.object(smoke.argparse.ArgumentParser, "parse_args", return_value=args),
                  patch.dict(os.environ, {"CI_JOB_ID": "42", "CI_COMMIT_SHA": "a" * 40}),
                  patch.object(smoke, "endpoint_from_env", return_value="unix:///ci.sock"),
                  patch.object(smoke, "checked", side_effect=checked),
                  patch.object(smoke, "Docker") as client,
                  patch.object(smoke, "check_wiring"),
                  patch.object(smoke, "run_projection", side_effect=terminate),
                  patch.object(smoke, "cleanup_owned") as cleanup,
                  patch.object(signal, "signal"), patch("sys.stdout", new_callable=io.StringIO)):
                self.assertEqual(smoke.main(), 143)
                cleanup.assert_called_once_with(client.return_value, "cabinet-alpha-projection-42", "42")
            result = json.loads(receipt.read_text())
            self.assertFalse(result["passed"])
            self.assertTrue(result["cleanup_passed"])
            self.assertEqual(result["error_category"], "interrupted")

    def test_command_failure_never_prints_secret_stderr(self):
        result = SimpleNamespace(returncode=1, stdout=SECRET, stderr=SECRET)
        with patch.object(subprocess, "run", return_value=result):
            with self.assertRaisesRegex(smoke.ProjectionError, "^probe: failed$") as error:
                smoke.checked("probe", ["docker"], {})
        self.assertNotIn(SECRET, str(error.exception))


class EvidenceTest(unittest.TestCase):
    def test_jar_receipt_binds_exact_source_and_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifacts = root / "ci-artifacts/alpha-analytics"
            artifacts.mkdir(parents=True)
            jar = root / "services/analytics/build/libs/app.jar"
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"synthetic-jar")
            (artifacts / "app.jar").write_bytes(jar.read_bytes())
            (artifacts / "source-revision.txt").write_text("a" * 40 + "\n")
            digest = smoke.sha256(jar.read_bytes()).hexdigest()
            (artifacts / "app.jar.sha256").write_text(digest + "  app.jar\n")
            with patch.object(smoke, "ROOT", root):
                self.assertEqual(smoke.jar_evidence("a" * 40), digest)
                with self.assertRaises(smoke.ProjectionError):
                    smoke.jar_evidence("b" * 40)

    def test_namespace_probe_uses_runtime_values_without_secret_arguments(self):
        client = MagicMock()
        client.command = ["docker", "--host", "unix:///ci.sock"]
        client.env = {"PATH": "/usr/bin"}
        analytics, valkey = "1" * 64, "2" * 64
        actual = {"SPRING_PROFILES_ACTIVE": "prod", "CABINET_VALKEY_ENABLED": "true", "CABINET_VALKEY_HOST": "valkey",
                  "CABINET_VALKEY_PASSWORD": SECRET}
        client.inspect.side_effect = [
            [f"{k}={v}" for k, v in actual.items()], ["VALKEY_PASSWORD=" + SECRET], "sha256:" + "3" * 64,
        ]
        with patch.object(smoke, "checked", return_value="") as run:
            result = smoke.valkey_namespace_probe(client, analytics, valkey, smoke.job_project("42"), {"VALKEY_PASSWORD": SECRET})
        args = run.call_args.args
        self.assertIn("container:" + analytics, args[1])
        self.assertNotIn(SECRET, " ".join(args[1]))
        self.assertEqual(args[2]["VALKEYCLI_AUTH"], SECRET)
        self.assertNotIn(SECRET, json.dumps(result))
        self.assertFalse(result["jvm_client_assertion"])


if __name__ == "__main__":
    unittest.main()
