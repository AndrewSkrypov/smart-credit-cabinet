import base64
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("with-deploy-secrets.py")
spec = importlib.util.spec_from_file_location("with_deploy_secrets", SCRIPT)
handoff = importlib.util.module_from_spec(spec)
spec.loader.exec_module(handoff)
SHA = "a" * 40
JOB = "42"


def token(**overrides):
    claims = {
        "iss": handoff.ISSUER,
        "aud": [handoff.VAULT_ORIGIN],
        "job_project_id": "1",
        "job_project_path": handoff.PROJECT_PATH,
        "ref": handoff.RELEASE_BRANCH,
        "ref_path": f"refs/heads/{handoff.RELEASE_BRANCH}",
        "ref_type": "branch",
        "ref_protected": "true",
        "pipeline_source": "push",
        "environment": "dev",
        "environment_action": "start",
        "runner_id": 2,
        "job_id": JOB,
        "sha": SHA,
    }
    encoded = base64.urlsafe_b64encode(json.dumps(claims | overrides).encode()).decode()
    return f"header.{encoded.rstrip('=')}.signature"


class HandoffTests(unittest.TestCase):
    def setUp(self):
        self.env = {"CI_COMMIT_SHA": SHA, "CI_JOB_ID": JOB, handoff.JWT_VAR: token()}
        self.values = dict(zip(handoff.PROFILES["dev"][3], ("fixture-key\n", "fixture-hosts\n")))

    def fake_vault(self, _opener, _endpoint, path, *, body=None, token=None):
        if path == "auth/pump-gitlab/login":
            return {"auth": {"client_token": "fixture-session", "policies": ["pump-ci-dev"]}}
        self.assertEqual(token, "fixture-session")
        if path == "pump/data/dev/ci-deploy":
            return {"data": {"data": self.values}}
        self.assertEqual(path, "auth/token/revoke-self")
        self.assertEqual(body, {})
        return {}

    def trust(self):
        return patch.object(handoff, "load_trust", return_value=(handoff.VAULT_ORIGIN, object()))

    def test_rejects_wrong_scope_and_provenance(self):
        for override in (
            {"environment": "production"}, {"environment_action": "verify"}, {"runner_id": 4},
            {"runner_id": "2"}, {"job_project_id": "99"}, {"job_project_path": "other/project"},
            {"ref_protected": "false"}, {"ref_path": "refs/heads/main"}, {"ref_type": "tag"},
            {"pipeline_source": "merge_request_event"}, {"aud": ["https://other.invalid"]},
            {"sha": "b" * 40}, {"job_id": "43"}, {"iss": "https://other.invalid"},
        ):
            with self.subTest(override=override), self.assertRaises(handoff.HandoffError):
                handoff.validate_claims(token(**override), handoff.VAULT_ORIGIN, "dev", 2, JOB, SHA, "start")

    def test_main_role_requires_the_exact_protected_push_and_pipeline(self):
        main_claims = {"ref": "main", "ref_path": "refs/heads/main", "pipeline_id": "178"}
        env = self.env | {"CI_PIPELINE_ID": "178", handoff.JWT_VAR: token(**main_claims)}
        with (patch.dict(os.environ, env, clear=True), self.trust(),
              patch.object(handoff, "vault_request", side_effect=self.fake_vault) as request):
            self.assertEqual(handoff.read_values("dev", main_release=True), {
                key: value.encode() for key, value in self.values.items()
            })
            self.assertEqual(request.call_args_list[0].kwargs["body"]["role"], "pump-ci-dev-main")
        for override in (
            {"pipeline_id": "179"}, {"pipeline_source": "web"}, {"pipeline_source": "api"},
            {"ref": handoff.RELEASE_BRANCH}, {"ref_protected": "false"}, {"sha": "b" * 40},
            {"environment": "production"}, {"environment_action": "verify"}, {"runner_id": 4},
        ):
            with self.subTest(override=override), self.assertRaises(handoff.HandoffError):
                handoff.validate_claims(token(**(main_claims | override)), handoff.VAULT_ORIGIN,
                                        "dev", 2, JOB, SHA, "start", main_pipeline="178")

    def test_main_vault_failure_never_uses_the_legacy_release_path(self):
        cli = ["with-deploy-secrets.py", "production-main-release", "--", "fixture-command"]
        with (patch.object(handoff.sys, "argv", cli),
              patch.object(handoff, "read_values", side_effect=handoff.HandoffError("Vault unavailable")) as read,
              patch.object(handoff, "release") as legacy, patch.object(handoff.subprocess, "run") as run,
              contextlib.redirect_stderr(io.StringIO())):
            self.assertEqual(handoff.main(), 1)
            read.assert_called_once_with("production", main_release=True)
            legacy.assert_not_called()
            run.assert_not_called()

    def test_canary_checks_bytes_and_revokes_then_never_invokes_a_child(self):
        with tempfile.TemporaryDirectory() as temp:
            files = {key: str(Path(temp) / key) for key in self.values}
            for key, path in files.items():
                Path(path).write_bytes(self.values[key].encode())
            env = self.env | files | {handoff.JWT_VAR: token(environment_action="verify")}
            with (patch.dict(os.environ, env, clear=True), self.trust(),
                  patch.object(handoff, "vault_request", side_effect=self.fake_vault) as request,
                  patch.object(handoff.subprocess, "run") as child, contextlib.redirect_stdout(io.StringIO()) as output):
                self.assertEqual(handoff.check_parity("dev"), 0)
                self.assertEqual(request.call_args_list[0].kwargs["body"]["role"], "pump-ci-dev-canary")
                self.assertEqual(request.call_args_list[-1].args[2], "auth/token/revoke-self")
                self.assertIn("2/2", output.getvalue())
                self.assertNotIn("fixture", output.getvalue())
                child.assert_not_called()

    def test_canary_fails_on_parity_mismatch_and_does_not_dump_values(self):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "legacy"
            file.write_bytes(b"wrong-bytes")
            env = self.env | {key: str(file) for key in self.values}
            env[handoff.JWT_VAR] = token(environment_action="verify")
            with (patch.dict(os.environ, env, clear=True), self.trust(),
                  patch.object(handoff, "vault_request", side_effect=self.fake_vault) as request,
                  self.assertRaisesRegex(handoff.HandoffError, "^Vault and legacy file variables differ$")):
                handoff.check_parity("dev")
            self.assertEqual(request.call_args_list[-1].args[2], "auth/token/revoke-self")

    def test_deploy_child_sees_only_replacement_paths_after_revoke_and_files_are_cleaned(self):
        paths = []
        with (patch.dict(os.environ, self.env | {"VAULT_TOKEN": "fixture-inherited-token"}, clear=True), self.trust(),
              patch.object(handoff, "vault_request", side_effect=self.fake_vault) as request):
            def child(command, *, env, check):
                self.assertEqual(request.call_args_list[-1].args[2], "auth/token/revoke-self")
                self.assertNotIn(handoff.JWT_VAR, env)
                self.assertNotIn("VAULT_TOKEN", env)
                for key, expected in self.values.items():
                    path = Path(env[key])
                    paths.append(path)
                    self.assertEqual(path.read_bytes(), expected.encode())
                    self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
                    self.assertEqual(stat.S_IMODE(path.parent.stat().st_mode), 0o700)
                return handoff.subprocess.CompletedProcess(command, 17)

            with patch.object(handoff.subprocess, "run", side_effect=child):
                self.assertEqual(handoff.deploy("dev", ["fixture-command"]), 17)
        self.assertEqual(len(paths), 2)
        self.assertTrue(all(not p.exists() for p in paths))

    def test_failed_vault_read_still_revokes_and_never_falls_back(self):
        def fail_read(opener, endpoint, path, **options):
            if path == "pump/data/dev/ci-deploy":
                raise handoff.HandoffError("Vault request returned HTTP 403")
            return self.fake_vault(opener, endpoint, path, **options)

        with (patch.dict(os.environ, self.env, clear=True), self.trust(),
              patch.object(handoff, "vault_request", side_effect=fail_read) as request,
              patch.object(handoff.subprocess, "run") as child):
            with self.assertRaisesRegex(handoff.HandoffError, "HTTP 403"):
                handoff.deploy("dev", ["fixture-command"])
            self.assertEqual(request.call_args_list[-1].args[2], "auth/token/revoke-self")
            child.assert_not_called()

    def test_debug_trace_is_rejected_before_vault(self):
        with (patch.dict(os.environ, self.env | {"CI_DEBUG_TRACE": "true"}, clear=True),
              patch.object(handoff, "load_trust") as load):
            with self.assertRaisesRegex(handoff.HandoffError, "debug tracing"):
                handoff.read_values("dev")
            load.assert_not_called()

    def test_unexpected_session_policy_is_revoked_without_reading(self):
        with (patch.dict(os.environ, self.env, clear=True), self.trust(),
              patch.object(handoff, "vault_request", side_effect=[
                  {"auth": {"client_token": "fixture-session", "policies": ["pump-ci-dev", "other"]}}, {},
              ]) as request, self.assertRaisesRegex(handoff.HandoffError, "session policies")):
            handoff.read_values("dev")
        self.assertEqual([call.args[2] for call in request.call_args_list],
                         ["auth/pump-gitlab/login", "auth/token/revoke-self"])

    def test_dev_release_uses_legacy_only_without_the_root_activation_file(self):
        with (patch.object(handoff, "verify_trust_path", side_effect=FileNotFoundError),
              patch.dict(os.environ, self.env | {"PUMP_VAULT_DEV_ENABLED": "1"}, clear=True),
              patch.object(handoff, "deploy") as deploy,
              patch.object(handoff.subprocess, "run", return_value=handoff.subprocess.CompletedProcess([], 0)) as run):
            self.assertEqual(handoff.release("dev", ["fixture-command"]), 0)
            self.assertNotIn(handoff.JWT_VAR, run.call_args.kwargs["env"])
            deploy.assert_not_called()

    def test_dev_release_rejects_activation_for_a_different_sha_and_keeps_root_authority(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory)
            marker = config / "dev-active-sha"
            marker.write_text(SHA + "\n")
            with (patch.object(handoff, "CONFIG_DIR", config),
                  patch.object(handoff, "verify_trust_path", return_value=None) as trust,
                  patch.dict(os.environ, self.env | {"PUMP_VAULT_DEV_ENABLED": "0"}, clear=True),
                  patch.object(handoff, "deploy", return_value=17) as deploy,
                  patch.object(handoff.subprocess, "run") as run):
                self.assertEqual(handoff.release("dev", ["fixture-command"]), 17)
                trust.assert_any_call(config, stat.S_ISDIR)
                trust.assert_any_call(marker, stat.S_ISREG)
                deploy.assert_called_once_with("dev", ["fixture-command"])
                marker.write_text("b" * 40)
                with self.assertRaisesRegex(handoff.HandoffError, "activation does not match"):
                    handoff.release("dev", ["fixture-command"])
                run.assert_not_called()

    def test_prod_canary_reads_only_its_record_and_revokes_without_running_a_child(self):
        requests = []

        def fake_request(_opener, _endpoint, path, *, body=None, token=None):
            requests.append(path)
            if path == "auth/pump-gitlab/login":
                self.assertEqual(body["role"], "pump-ci-prod-canary")
                return {"auth": {"client_token": "fixture-prod-session", "policies": ["pump-ci-prod"]}}
            self.assertEqual(token, "fixture-prod-session")
            if path == "pump/data/prod/ci-deploy":
                return {"data": {"data": {"CABINET_PROD_VALUES": "fixture-values\n"}}}
            self.assertEqual(path, "auth/token/revoke-self")
            return {}

        with tempfile.TemporaryDirectory() as directory:
            previous = Path(directory) / "legacy-values"
            previous.write_text("fixture-values\n")
            env = self.env | {
                handoff.JWT_VAR: token(environment="production", environment_action="verify", runner_id=4),
                "CABINET_PROD_VALUES": str(previous),
            }
            with (patch.dict(os.environ, env, clear=True), self.trust(),
                  patch.object(handoff, "vault_request", side_effect=fake_request),
                  patch.object(handoff.subprocess, "run") as child, contextlib.redirect_stdout(io.StringIO()) as output):
                self.assertEqual(handoff.check_parity("production"), 0)
                self.assertIn("1/1", output.getvalue())
                self.assertNotIn("fixture", output.getvalue())
                child.assert_not_called()
        self.assertEqual(requests, ["auth/pump-gitlab/login", "pump/data/prod/ci-deploy", "auth/token/revoke-self"])

    def test_prod_cannot_use_dev_marker_runner_or_start_token_for_canary(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory)
            (config / "dev-active-sha").write_text(SHA)
            with (patch.object(handoff, "CONFIG_DIR", config),
                  patch.object(handoff, "verify_trust_path", side_effect=lambda path, _: path.stat()),
                  patch.dict(os.environ, self.env, clear=True),
                  patch.object(handoff, "deploy") as deploy,
                  patch.object(handoff.subprocess, "run", return_value=handoff.subprocess.CompletedProcess([], 0))):
                self.assertEqual(handoff.release("production", ["fixture-command"]), 0)
                deploy.assert_not_called()
        for claims in (
            {"environment": "production", "runner_id": 2, "environment_action": "verify"},
            {"environment": "production", "runner_id": 4, "environment_action": "start"},
            {"environment": "dev", "runner_id": 4, "environment_action": "verify"},
        ):
            with self.subTest(claims=claims), self.assertRaises(handoff.HandoffError):
                handoff.validate_claims(token(**claims), handoff.VAULT_ORIGIN, "production", 4, JOB, SHA, "verify")

if __name__ == "__main__":
    unittest.main()
