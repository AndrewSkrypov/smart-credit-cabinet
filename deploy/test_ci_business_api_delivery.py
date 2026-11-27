"""Offline isolation/transport/provider tests; these do not claim API integration PASS."""
from contextlib import ExitStack
import hashlib
import http.client
import json
import os
from pathlib import Path
import socket
import ssl
import tempfile
import unittest
from unittest.mock import Mock, patch

import yaml

import business_smoke as business
import ci_business_api as ci
import common

REVISION = "1" * 40
PROJECT = "cabinet-prod-restore-test-" + "a" * 12
REPO = Path(__file__).resolve().parents[1]
ENVI = {"CI": "true", "CI_JOB_ID": "123", "CI_COMMIT_SHA": REVISION,
        "CABINET_CI_DOCKER_HOST": "unix:///tmp/cabinet-ci/docker.sock",
        "DOCKER_HOST": "unix:///tmp/cabinet-ci/docker.sock"}
ENABLED = {"credit": {"DEV_PAYMENT_RESERVE_SIMULATION_ENABLED": "true", "CABINET_DEPLOYMENT_PROJECT": "cabinet-dev", "DEMO_ACCOUNTS": "false"},
           "identity": {"DEMO_ACCOUNTS": "false"}, "external-sim": {"SIM_CONTROL_ENABLED": "false"}}
DISABLED = {**ENABLED, "credit": {**ENABLED["credit"], "DEV_PAYMENT_RESERVE_SIMULATION_ENABLED": "false", "CABINET_DEPLOYMENT_PROJECT": "cabinet-prod"}}


def environment():
    keys = ["POSTGRES_PASSWORD", "JWT_SECRET", "SIM_API_KEY", "IDENTITY_LINK_KEY", "VALKEY_PASSWORD"]
    keys += [name + suffix for name in ("IDENTITY", "CREDIT", "ENGAGEMENT")
             for suffix in ("_DB_PASSWORD", "_MIGRATION_PASSWORD")]
    values = {key: hashlib.sha256(key.encode()).hexdigest() for key in keys}
    values.update(CABINET_DOMAIN="localhost", PUBLIC_CABINET_URL="https://localhost:20443", HTTPS_PORT="20443",
                  CABINET_RELEASE="local-verification-aaaaaaaaaaaa", DEMO_ACCOUNTS="false", SIM_CONTROL_ENABLED="false")
    return values


class Isolated(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch("subprocess.run", side_effect=AssertionError("offline: no processes")))
        self.stack.enter_context(patch("socket.create_connection", side_effect=AssertionError("offline: no sockets")))
        self.stack.enter_context(patch("socket.getaddrinfo", side_effect=AssertionError("offline: no DNS")))
        self.stack.enter_context(patch.dict(os.environ, ENVI, clear=True))
        self.temp = self.stack.enter_context(tempfile.TemporaryDirectory())
        self.root = Path(self.temp)
        self.runtime = self.root / "deploy/runtime/test-aaaaaaaaaaaa"
        self.runtime.mkdir(parents=True)
        self.output = self.root / "reports"
        self.output.mkdir()
        self.stack.enter_context(patch.object(common, "ROOT", self.root))
        self.stack.enter_context(patch.object(ci, "ROOT", self.root))
        values = environment()
        raw = "".join(f"{key}={value}\n" for key, value in values.items()).encode()
        path = self.runtime / "target.env"
        path.write_bytes(raw)
        # Real Deployment, not a mock reimplementation of its public surface.
        self.deployment = common.Deployment(PROJECT, path, local=True, env_contents=raw)

    def expect_denied(self, callback):
        with self.assertRaises(business.SmokeError):
            callback()

    def test_real_local_deployment_starts_with_no_dev_permission(self):
        self.assertFalse(self.deployment.dev_payment_reserve_simulation)
        self.assertIsNone(self.deployment.release)
        self.assertEqual(self.deployment.runtime_modes, {})
        self.assertNotIn(str(ci.OVERLAY), self.deployment.command)

    def test_source_job_daemon_and_project_are_all_required(self):
        ci.ci_context(ENVI, PROJECT, REVISION)
        for key, value in (("CI", "false"), ("CI_JOB_ID", "0"), ("CI_COMMIT_SHA", "2" * 40),
                           ("DOCKER_HOST", "unix:///var/run/docker.sock"), ("DOCKER_CONTEXT", "default"),
                           ("CABINET_CI_DOCKER_HOST", "tcp://dev.example.invalid:2375")):
            with self.subTest(key=key):
                self.expect_denied(lambda: ci.ci_context({**ENVI, key: value}, PROJECT, REVISION))
        for project in ("cabinet-dev", "cabinet-prod", "cabinet-prod-restore-test-", "cabinet-prod-restore-test-" + "a" * 12 + "/x"):
            with self.subTest(project=project):
                self.expect_denied(lambda: ci.ci_context(ENVI, project, REVISION))

    def test_default_socket_is_denied_even_if_both_environment_values_match(self):
        for value in ("unix:///var/run/docker.sock", "unix:///run/docker.sock", "unix:///tmp/../docker.sock"):
            self.expect_denied(lambda: ci.ci_context({**ENVI, "DOCKER_HOST": value, "CABINET_CI_DOCKER_HOST": value}, PROJECT, REVISION))

    def metadata(self, *args):
        if args[:1] == ("info",):
            return '["cabinet-ci=true"]'
        if args[:2] == ("image", "inspect"):
            return REVISION
        service = args[-1]
        if ".Config.Labels" in args[-2]:
            return json.dumps({"com.docker.compose.project": PROJECT, "com.docker.compose.service": service})
        return "sha256:" + "2" * 64

    def test_fixture_binds_actual_container_labels_and_image_revisions(self):
        with patch.object(self.deployment, "container", side_effect=lambda name: name), patch.object(ci, "command", side_effect=self.metadata):
            port, images = ci.verified_fixture(self.deployment, REVISION, self.runtime)
        self.assertEqual(port, 20443)
        self.assertEqual(set(images), set(ci.SERVICES))

    def test_foreign_labels_or_stale_image_or_wrong_runtime_fail_before_changes(self):
        for mutation in ("project", "source", "daemon"):
            def metadata(*args):
                answer = self.metadata(*args)
                if mutation == "project" and args[:1] == ("inspect",) and ".Config.Labels" in args[-2]:
                    return json.dumps({"com.docker.compose.project": "cabinet-dev", "com.docker.compose.service": args[-1]})
                if mutation == "source" and args[:2] == ("image", "inspect"):
                    return "2" * 40
                if mutation == "daemon" and args[:1] == ("info",):
                    return "[]"
                return answer
            with self.subTest(mutation=mutation), patch.object(self.deployment, "container", side_effect=lambda name: name), patch.object(ci, "command", side_effect=metadata):
                self.expect_denied(lambda: ci.verified_fixture(self.deployment, REVISION, self.runtime))
        self.expect_denied(lambda: ci.verified_fixture(self.deployment, REVISION, self.root))

    def test_only_two_identity_roles_are_seeded_without_business_sql(self):
        hasher = Mock(return_value=b"$2b$12$" + b"a" * 53)
        with patch("bcrypt.hashpw", hasher), patch.object(self.deployment, "sql") as sql:
            credentials = ci.provision_roles(self.deployment)
        self.assertEqual(set(credentials), {"AGENT", "UNDERWRITER"})
        self.assertEqual(hasher.call_count, 2)
        self.assertNotEqual(credentials["AGENT"]["password"], credentials["UNDERWRITER"]["password"])
        database, statement = sql.call_args.args
        self.assertEqual(database, "identity")
        self.assertEqual(statement.count("'ACTIVE', null, false"), 2)
        self.assertTrue(statement.startswith("begin; insert into users "))
        self.assertNotIn("on conflict", statement.lower())
        for name in ("companies", "agent_clients", "loans", "reserves", "fences", "CLIENT"):
            self.assertNotIn(name, statement)

    def lifecycle(self, *, error=None, restore_error=None, enabled=ENABLED):
        calls = []
        original = list(self.deployment.command)
        def compose(*args, **kwargs):
            calls.append((list(self.deployment.command), args))
            if len(calls) == 2 and restore_error:
                raise restore_error
        def execute(_api, ledger, _credentials):
            if error:
                raise error
            ledger.data.update(checks=sorted(ci.CHECKS), facts={"reserve": []}, cleanup={})
            ledger.save()
        self.stack.enter_context(patch.object(ci, "verified_fixture", return_value=(20443, {name: "sha256:" + "2" * 64 for name in ci.SERVICES})))
        self.stack.enter_context(patch.object(ci, "daemon_guard"))
        self.stack.enter_context(patch.object(ci, "provision_roles", return_value={}))
        self.stack.enter_context(patch.object(ci, "API", return_value=Mock(requests=100, bytes=1000)))
        self.stack.enter_context(patch.object(ci, "execute", side_effect=execute))
        self.stack.enter_context(patch.object(ci, "flags", side_effect=[enabled, DISABLED]))
        self.stack.enter_context(patch.object(self.deployment, "compose", side_effect=compose))
        return original, calls

    def test_success_restores_original_flags_and_explicitly_is_not_dev_or_remote_proof(self):
        original, calls = self.lifecycle()
        result = ci.run(self.deployment, REVISION, self.runtime, self.output)
        self.assertTrue(result["passed"])
        self.assertTrue(result["feature_restored_false"])
        self.assertFalse(result["dev_accepted"])
        self.assertFalse(result["four_remote_accepted"])
        self.assertEqual(result["runtime_modes"], {key: "embedded" for key in business.RUNTIMES})
        self.assertEqual(calls[0][0][-2:], ["-f", str(ci.OVERLAY)])
        self.assertEqual(calls[1][0], original)
        self.assertEqual(self.deployment.command, original)
        ledgers = list((self.runtime / "ci-business/business-smoke").glob("*.json"))
        self.assertEqual(len(ledgers), 1)
        self.assertIsNone(json.loads(ledgers[0].read_text())["manifest_sha256"])
        self.assertEqual(ledgers[0].stat().st_mode & 0o777, 0o600)
        self.assertNotIn("password", (self.output / "business-api.json").read_text())

    def test_business_failure_still_restores_and_cannot_publish_pass(self):
        original, calls = self.lifecycle(error=business.SmokeError("synthetic_failure"))
        with self.assertRaisesRegex(business.SmokeError, "synthetic_failure"):
            ci.run(self.deployment, REVISION, self.runtime, self.output)
        result = json.loads((self.output / "business-api.json").read_text())
        self.assertFalse(result["passed"])
        self.assertTrue(result["feature_restored_false"])
        self.assertEqual(calls[-1][0], original)

    def test_restore_failure_invalidates_success_and_preserves_diagnostic_receipt(self):
        original, _ = self.lifecycle(restore_error=RuntimeError("synthetic_restore_failure"))
        with self.assertRaisesRegex(RuntimeError, "synthetic_restore_failure"):
            ci.run(self.deployment, REVISION, self.runtime, self.output)
        result = json.loads((self.output / "business-api.json").read_text())
        self.assertTrue(result["business_completed"])
        self.assertFalse(result["passed"])
        self.assertFalse(result["feature_restored_false"])
        self.assertEqual(self.deployment.command, original)

    def test_actual_demo_flag_mismatch_prevents_business(self):
        bad = {**ENABLED, "identity": {"DEMO_ACCOUNTS": "true"}}
        self.lifecycle(enabled=bad)
        self.expect_denied(lambda: ci.run(self.deployment, REVISION, self.runtime, self.output))
        ci.execute.assert_not_called()
        self.assertFalse(json.loads((self.output / "business-api.json").read_text())["passed"])

    def test_flow_error_and_cleanup_error_are_failures_not_mock_integration_proof(self):
        ledger = Mock(data={"checks": [], "cleanup": {}})
        smoke = Mock()
        smoke.signin.side_effect = business.SmokeError("synthetic_login_failure")
        smoke.cleanup.return_value = False
        with patch.object(business, "Smoke", return_value=smoke):
            with self.assertRaisesRegex(business.SmokeError, "synthetic_login_failure"):
                ci.execute(Mock(), ledger, {"AGENT": {}, "UNDERWRITER": {}})
        smoke.cleanup.assert_called_once()
        self.assertEqual(ledger.data["status"], "INCOMPLETE")


class Transport(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch("subprocess.run", side_effect=AssertionError("no processes")))
        self.stack.enter_context(patch("socket.create_connection", side_effect=AssertionError("no sockets")))
        self.stack.enter_context(patch("socket.getaddrinfo", side_effect=AssertionError("no DNS")))
        self.temp = self.stack.enter_context(tempfile.TemporaryDirectory())
        self.ca = Path(self.temp) / "ca.crt"
        self.ca.write_text("synthetic CA; SSL context mocked, no trust install")
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        self.stack.enter_context(patch.object(ci.ssl, "create_default_context", return_value=self.context))
        self.api = ci.API(20443, self.ca)

    def response(self, status=200, headers=None, chunks=None):
        response = Mock(status=status)
        response.getheader.side_effect = lambda key, default=None: (headers or {}).get(key, default)
        response.read1.side_effect = chunks if chunks is not None else [b"{}", b""]
        connection = Mock()
        connection.getresponse.return_value = response
        self.stack.enter_context(patch.object(ci, "LoopbackHTTPS", return_value=connection))
        return connection, response

    def test_numeric_loopback_is_pinned_and_tls_hostname_is_localhost(self):
        connection = object.__new__(ci.LoopbackHTTPS)
        connection._tunnel_host = None
        connection.port, connection.timeout = 20443, 7
        connection._context = Mock()
        raw = Mock()
        with patch("socket.create_connection", return_value=raw) as connect:
            connection.connect()
        connect.assert_called_once_with(("127.0.0.1", 20443), 7)
        connection._context.wrap_socket.assert_called_once_with(raw, server_hostname="localhost")

    def test_bad_certificate_closes_numeric_socket(self):
        connection = object.__new__(ci.LoopbackHTTPS)
        connection._tunnel_host, connection.port, connection.timeout = None, 20443, 7
        connection._context = Mock()
        connection._context.wrap_socket.side_effect = ssl.SSLCertVerificationError("synthetic")
        raw = Mock()
        with patch("socket.create_connection", return_value=raw):
            with self.assertRaises(ssl.SSLCertVerificationError):
                connection.connect()
        raw.close.assert_called_once()

    def test_verified_tls_identity_and_request_headers(self):
        connection, _ = self.response()
        self.assertEqual(self.api.request("GET", "/api/v1/products"), (200, b"{}"))
        self.assertTrue(self.api.tls.check_hostname)
        self.assertEqual(self.api.tls.verify_mode, ssl.CERT_REQUIRED)
        ci.LoopbackHTTPS.assert_called_once_with("localhost", 20443, timeout=15, context=self.context)
        self.assertEqual(connection.request.call_args.kwargs["headers"]["Accept-Encoding"], "identity")
        connection.close.assert_called_once()

    def test_redirect_is_not_followed_or_drained(self):
        connection, response = self.response(status=302, headers={"Location": business.ORIGIN})
        with self.assertRaisesRegex(business.SmokeError, "redirect_rejected"):
            self.api.request("GET", "/api/v1/products")
        response.read1.assert_not_called()
        connection.close.assert_called_once()

    def test_gzip_and_declared_oversize_are_rejected_before_read(self):
        for headers, code in (({"Content-Encoding": "gzip"}, "response_encoding"),
                              ({"Content-Length": str(business.MAX_RESPONSE + 1)}, "response_oversize"),
                              ({"Content-Length": "-1"}, "response_length")):
            with self.subTest(headers=headers):
                _, response = self.response(headers=headers)
                with self.assertRaisesRegex(business.SmokeError, code):
                    self.api.request("GET", "/api/v1/products")
                response.read1.assert_not_called()

    def test_streamed_oversize_and_total_budget_are_bounded(self):
        self.response(chunks=[b"x" * (business.MAX_RESPONSE + 1)])
        with self.assertRaisesRegex(business.SmokeError, "response_budget_exhausted"):
            self.api.request("GET", "/api/v1/products")
        self.api.bytes = business.MAX_TOTAL_BYTES
        self.response(chunks=[b"x"])
        with self.assertRaisesRegex(business.SmokeError, "response_budget_exhausted"):
            self.api.request("GET", "/api/v1/products")

    def test_no_url_proxy_route_or_header_injection(self):
        for path in (business.ORIGIN + "/api/v1/products", "//dev.credit-cabinet.ru/api/v1/products", "/sim/control", "/api/v1/../users"):
            with self.assertRaises(business.SmokeError):
                self.api.request("GET", path)
        with self.assertRaises(business.SmokeError):
            self.api.request("GET", "/api/v1/products", token="synthetic\nheader")
        with self.assertRaises(business.SmokeError):
            ci.API(business.ORIGIN, self.ca)


class Bindings(unittest.TestCase):
    def test_ci_reuses_existing_local_gate_and_artifacts_exclude_private_runtime(self):
        class Loader(yaml.SafeLoader):
            pass
        Loader.add_constructor("!reference", lambda loader, node: loader.construct_sequence(node))
        jobs = yaml.load((REPO / "ci/gitlab/quality.yml").read_text(), Loader=Loader)
        self.assertNotIn("business-api", jobs)
        job = jobs["deployment"]
        self.assertEqual(job["stage"], "extended")
        self.assertEqual(job["extends"], [".compose-quality", ".extended"])
        self.assertNotIn("allow_failure", job)
        self.assertNotIn("when", job)
        self.assertEqual(job["resource_group"], "cabinet-ci-local-deployment")
        self.assertEqual(job["artifacts"]["paths"], ["deploy/reports/test-*/*.json"])
        self.assertEqual(job["parallel"]["matrix"], [{"STORAGE": ["local", "s3"]}])
        self.assertEqual(job["timeout"], "70 minutes")
        self.assertIn('if [[ "$STORAGE" == local ]]', "\n".join(job["script"]))
        self.assertIn("--business-api-smoke", "\n".join(job["script"]))
        alpha = yaml.safe_load((REPO / "ci/alpha/gitlab.yml").read_text())
        self.assertIn('"feature/alpha-dev-business-acceptance"', alpha[".alpha-pilot-verify"]["rules"][1]["if"])

    def test_only_explicit_test_overlay_enables_permission_without_demo(self):
        overlay = yaml.safe_load((REPO / "ci/gitlab/quality/compose.business-api.yaml").read_text())
        self.assertEqual(set(overlay["services"]), {"credit"})
        self.assertEqual(overlay["services"]["credit"]["environment"], ENABLED["credit"])
        self.assertNotIn("compose.business-api", (REPO / "deploy/common.py").read_text())

    def test_provider_hook_follows_all_existing_checks_and_precedes_pass(self):
        source = (REPO / "deploy/test-local.py").read_text()
        hook = source.index('stage = "isolated-business-api"')
        self.assertLess(source.index('stage = "memory-evidence"'), hook)
        self.assertLess(source.index('stage = "nonempty-target-guard"'), hook)
        self.assertLess(hook, source.index('report["passed"] = True', hook))
        self.assertIn('if not args.keep:', source[hook:])
        self.assertIn('deployment.compose("down", "-v", "--remove-orphans")', source[hook:])


if __name__ == "__main__":
    unittest.main()
