import base64
import contextlib
import hashlib
from importlib import import_module
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


activation = import_module("main-release-activation")
NOW = 2_000_000_000
NAMESPACE_UID = "6f1d4c1e-0b8e-4c55-9a51-000000000001"


def jwt(**claims):
    def pack(data):
        return base64.urlsafe_b64encode(json.dumps(data).encode()).decode().rstrip("=")

    header = pack({"alg": "RS256"})
    payload = pack({"sub": activation.SUBJECT, "iat": NOW - 60, "exp": NOW + 3540} | claims)
    return f"{header}.{payload}.fixtureSignature"


class ActivationTest(unittest.TestCase):
    def setUp(self):
        scratch = tempfile.TemporaryDirectory()
        self.addCleanup(scratch.cleanup)
        self.dir = Path(scratch.name)
        previous = Path.cwd()
        os.chdir(self.dir)
        self.addCleanup(os.chdir, previous)
        self.write = {
            "CI_PROJECT_ID": "1", "CI_PROJECT_PATH": activation.PROJECT_PATH,
            "CI_COMMIT_BRANCH": "main", "CI_COMMIT_REF_NAME": "main", "CI_COMMIT_REF_PROTECTED": "true",
            "CI_PIPELINE_SOURCE": "push", "PUMP_MAIN_RELEASE_ENABLED": "1", "CI_JOB_ID": "42",
            "CI_COMMIT_SHA": "a" * 40, "CI_PIPELINE_ID": "100", "CI_JOB_NAME": activation.ACTIVATOR,
            "CI_ENVIRONMENT_ACTION": "prepare", "CI_ENVIRONMENT_NAME": "production", "CI_RUNNER_ID": "4",
            "CABINET_PROD_CONTEXT": "prod-fixture", "CABINET_PROD_NAMESPACE": activation.NAMESPACE,
            "CABINET_PROD_NAMESPACE_UID": NAMESPACE_UID,
            "CABINET_MAIN_PROD_JIT_CONTEXT": str(self.dir / "context.json"),
            "CABINET_MAIN_PROD_KUBECONFIG": str(self.dir / "kubeconfig.json"),
            "CABINET_MAIN_PROD_JURY_CREDENTIALS": str(self.dir / "jury.json"),
        }
        self.prod = self.write | {"CI_JOB_ID": "43", "CI_JOB_NAME": "main-prod-deploy", "CI_ENVIRONMENT_ACTION": "start"}
        self.dev = {key: value for key, value in self.write.items() if not key.startswith("CABINET_")} | {
            "CI_JOB_ID": "44", "CI_JOB_NAME": "main-dev-deploy", "CI_ENVIRONMENT_ACTION": "start",
            "CI_ENVIRONMENT_NAME": "dev", "CI_RUNNER_ID": "2",
        }
        manifest = self.json_file(Path("release.json"), {"revision": "a" * 40})
        checksum = hashlib.sha256(manifest).hexdigest()
        self.record = {
            "format": 1, "project_id": "1", "pipeline_id": "100", "revision": "a" * 40,
            "publisher_job_id": "41", "release_sha256": checksum,
        }
        self.json_file(Path("release-evidence.json"), {
            "format": 1, "project": "1", "pipeline": "100", "revision": "a" * 40,
            "publisher_job": "41", "manifest_sha256": checksum,
            "ref": "refs/heads/main", "source": "push", "tree": "b" * 40,
        })
        self.kubeconfig = {
            "kind": "Config", "apiVersion": "v1", "preferences": {}, "current-context": "prod-fixture",
            "clusters": [{"name": "cluster", "cluster": {
                "server": "https://fixture.example.invalid:6443",
                "tls-server-name": "fixture.example.invalid",
                "certificate-authority-data": base64.b64encode(
                    b"-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----\n"
                ).decode(),
            }}],
            "users": [{"name": "release", "user": {"token": jwt()}}],
            "contexts": [{"name": "prod-fixture", "context": {
                "cluster": "cluster", "user": "release", "namespace": activation.NAMESPACE,
            }}],
        }
        self.jury = {
            role: {"email": f"{role.lower()}@example.invalid", "password": f"synthetic-{role}-password"}
            for role in ("CLIENT", "AGENT", "UNDERWRITER")
        }
        self.valid_inputs()

    @staticmethod
    def json_file(path, data):
        raw = json.dumps(data).encode()
        path.write_bytes(raw)
        path.chmod(0o600)
        return raw

    def valid_inputs(self, **envelope_overrides):
        self.kubeconfig_raw = self.json_file(Path("kubeconfig.json"), self.kubeconfig)
        jury = self.json_file(Path("jury.json"), self.jury)
        self.json_file(Path("context.json"), self.record | {
            "namespace_uid": NAMESPACE_UID,
            "kubeconfig_sha256": hashlib.sha256(self.kubeconfig_raw).hexdigest(),
            "jury_sha256": hashlib.sha256(jury).hexdigest(),
        } | envelope_overrides)

    def invoke(self, mode, env, uid=NAMESPACE_UID):
        output, errors = io.StringIO(), io.StringIO()
        with (
            patch.dict(os.environ, env, clear=True),
            patch.object(sys, "argv", ["main-release-activation.py", mode]),
            patch.object(activation.time, "time", return_value=NOW),
            patch.object(activation.subprocess, "run", return_value=subprocess.CompletedProcess(
                args=[], returncode=0, stdout=uid.encode() + b"\n", stderr=b"fixture secret",
            )) as kubectl,
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(errors),
        ):
            try:
                code = activation.main()
            except SystemExit as error:
                code = error.code
        self.assertNotIn("fixture secret", output.getvalue() + errors.getvalue())
        return code, kubectl

    def test_valid_activation_writes_only_public_provenance_and_prod_rechecks(self):
        code, kubectl = self.invoke("write", self.write)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(activation.CHECKPOINT.read_text()), self.record | {"checkpoint_job": "42"})
        call = kubectl.call_args
        self.assertEqual(call.kwargs["input"], self.kubeconfig_raw)
        self.assertEqual(call.kwargs["stderr"], subprocess.DEVNULL)
        self.assertIn("--kubeconfig=/dev/stdin", call.args[0])
        self.assertIn("--context=prod-fixture", call.args[0])
        self.assertIn("--namespace=cabinet-prod", call.args[0])
        self.assertLessEqual(call.kwargs["timeout"], 15)
        code, kubectl = self.invoke("verify", self.prod)
        self.assertEqual(code, 0)
        kubectl.assert_called_once()

    def test_dev_does_not_read_any_prod_file(self):
        self.assertEqual(self.invoke("write", self.write)[0], 0)
        for filename in ("context.json", "kubeconfig.json", "jury.json"):
            Path(filename).unlink()
        code, kubectl = self.invoke("verify", self.dev)
        self.assertEqual(code, 0)
        kubectl.assert_not_called()

    def test_wrong_producer_or_envelope_fails_before_kubectl(self):
        self.valid_inputs(publisher_job_id="40")
        code, kubectl = self.invoke("write", self.write)
        self.assertEqual(code, 1)
        kubectl.assert_not_called()
        self.assertFalse(activation.CHECKPOINT.exists())

    def test_working_publisher_cannot_be_replaced_in_checkpoint(self):
        self.assertEqual(self.invoke("write", self.write)[0], 0)
        for changed in ({"publisher_job_id": "40"}, {"pipeline_id": "101"}, {"revision": "c" * 40},
                        {"release_sha256": "e" * 64}, {"checkpoint_job": "43"}, {"checkpoint_job": "0"}):
            with self.subTest(changed=changed):
                self.json_file(activation.CHECKPOINT, self.record | {"checkpoint_job": "42"} | changed)
                code, kubectl = self.invoke("verify", self.prod)
                self.assertEqual(code, 1)
                kubectl.assert_not_called()

    def test_prod_rejects_stale_token_after_valid_manual_checkpoint(self):
        self.assertEqual(self.invoke("write", self.write)[0], 0)
        self.kubeconfig["users"][0]["user"]["token"] = jwt(iat=NOW - 1200, exp=NOW + 2400)
        self.valid_inputs()
        code, kubectl = self.invoke("verify", self.prod)
        self.assertEqual(code, 1)
        kubectl.assert_not_called()

    def test_prod_rejects_changed_credentials_after_activation(self):
        self.assertEqual(self.invoke("write", self.write)[0], 0)
        self.jury["CLIENT"]["password"] = "changed-synthetic-password"
        self.json_file(Path("jury.json"), self.jury)
        code, kubectl = self.invoke("verify", self.prod)
        self.assertEqual(code, 1)
        kubectl.assert_not_called()

    def test_invalid_kubernetes_identity_or_token_window_is_denied(self):
        for claims in ({"sub": "system:admin"}, {"iat": NOW + 1, "exp": NOW + 3601},
                       {"iat": NOW - 60, "exp": NOW + 5500}, {"nbf": NOW + 1}, {"iat": True},
                       {"iat": NOW - 300, "exp": NOW + 2699}):
            with self.subTest(claims=claims):
                self.kubeconfig["users"][0]["user"]["token"] = jwt(**claims)
                self.valid_inputs()
                code, kubectl = self.invoke("write", self.write)
                self.assertEqual(code, 1)
                kubectl.assert_not_called()

    def test_kubeconfig_rejects_plugin_and_insecure_tls(self):
        cluster = self.kubeconfig["clusters"][0]["cluster"]
        user = self.kubeconfig["users"][0]["user"]
        for mapping, key, value in ((user, "exec", {}), (user, "auth-provider", {}),
                                    (cluster, "insecure-skip-tls-verify", True), (cluster, "proxy-url", "https://fixture")):
            with self.subTest(key=key):
                mapping[key] = value
                self.valid_inputs()
                code, kubectl = self.invoke("write", self.write)
                self.assertEqual(code, 1)
                kubectl.assert_not_called()
                del mapping[key]

    def test_kubeconfig_rejects_alternate_tls_server_name(self):
        self.kubeconfig["clusters"][0]["cluster"]["tls-server-name"] = "other.example.invalid"
        self.valid_inputs()
        code, kubectl = self.invoke("write", self.write)
        self.assertEqual(code, 1)
        kubectl.assert_not_called()

    def test_scope_debug_and_file_discrepancies_are_denied(self):
        for scope in ({"CI_RUNNER_ID": "2"}, {"CI_ENVIRONMENT_ACTION": "start"}, {"CI_DEBUG_TRACE": "true"},
                      {"CI_PIPELINE_SOURCE": "web"}, {"CI_PROJECT_ID": "2"}, {"CI_COMMIT_REF_PROTECTED": "false"}):
            with self.subTest(scope=scope):
                code, kubectl = self.invoke("write", self.write | scope)
                self.assertEqual(code, 1)
                kubectl.assert_not_called()
        Path("jury.json").chmod(0o640)
        self.assertEqual(self.invoke("write", self.write)[0], 1)
        Path("jury.json").chmod(0o600)
        Path("jury.json").rename("other.json")
        Path("jury.json").symlink_to("other.json")
        self.assertEqual(self.invoke("write", self.write)[0], 1)

    def test_namespace_uid_check_fails_closed(self):
        code, kubectl = self.invoke("write", self.write, uid="another-namespace")
        self.assertEqual(code, 1)
        kubectl.assert_called_once()
        self.assertFalse(activation.CHECKPOINT.exists())


if __name__ == "__main__":
    unittest.main()
