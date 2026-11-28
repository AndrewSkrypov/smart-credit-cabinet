"""Offline host-smoke transport, scope and recovery contracts. Never accesses Dev."""
from contextlib import nullcontext, redirect_stdout
from copy import deepcopy
import ast
import fcntl
import hashlib
import http.client
import importlib.util
from io import BytesIO, StringIO
import json
import os
from pathlib import Path
import re
import signal
import ssl
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from urllib.parse import urlsplit
from uuid import uuid4

import business_smoke as smoke
from registry_release import IMAGE_COMPONENTS, RUNTIME_COMPONENTS, RegistryRelease


ROOT = Path(__file__).resolve().parents[1]


def controller_source():
    source = (ROOT / "deploy/dev-release.py").read_text()
    assert "from business_smoke import run as run_business_smoke" in source
    ast.parse(source)
    return source


def encoded(value):
    return json.dumps(value).encode()


def remote_manifest(revision):
    repository = "registry.example.invalid/team/cabinet"
    image = lambda name: f"{repository}/{name}@sha256:" + "1" * 64
    oci = {name: "sha256:" + "2" * 64 for name in (*RUNTIME_COMPONENTS, "kafka")}
    manifest = {"revision": revision, "images": {name: image(name) for name in (*IMAGE_COMPONENTS, *RUNTIME_COMPONENTS)},
                "runtime_oci": oci,
                "alpha_dev": {"image": image("analytics"), "jar_sha256": "3" * 64, "kafka_image": image("kafka")}}
    for component, key in RUNTIME_COMPONENTS.items():
        manifest[key] = {"dev_mode": "remote", "jar_sha256": "3" * 64}
        if key == "catalog":
            manifest[key]["config_digest"] = oci[component]
        if key == "assessment":
            manifest[key]["kafka_image"] = image("kafka")
    return manifest


class Response:
    def __init__(self, status=200, body=b"{}", headers=None):
        self.status, self.body, self.headers = status, body, headers or {}
        self.reads = 0

    def getheader(self, key, default=None):
        return self.headers.get(key, default)

    def read1(self, size):
        self.reads += 1
        result, self.body = self.body[:size], self.body[size:]
        return result


class Transport(unittest.TestCase):
    def setUp(self):
        self.response = Response()
        self.connection = Mock()
        self.connection.getresponse.return_value = self.response
        self.factory = patch.object(smoke.http.client, "HTTPSConnection", return_value=self.connection).start()
        self.addCleanup(patch.stopall)
        self.api = smoke.API()

    def test_verified_tls_fixed_origin_and_no_proxy_environment_transport(self):
        self.assertEqual(self.api.request("GET", "/api/v1/products"), (200, b"{}"))
        args, kwargs = self.factory.call_args
        self.assertEqual(args, (smoke.HOST, 443))
        self.assertTrue(kwargs["context"].check_hostname)
        self.assertEqual(kwargs["context"].verify_mode, ssl.CERT_REQUIRED)
        self.assertEqual(self.connection.request.call_args.args, ("GET", "/api/v1/products"))
        self.assertEqual(self.connection.request.call_args.kwargs["headers"]["Accept-Encoding"], "identity")
        self.connection.close.assert_called_once()

    def test_redirect_rejected_without_reading_or_following(self):
        for status in (301, 302, 303, 307, 308):
            self.response.status = status
            with self.subTest(status=status), self.assertRaisesRegex(smoke.SmokeError, "redirect_rejected"):
                self.api.request("GET", "/api/v1/products")
        self.assertEqual(self.response.reads, 0)

    def test_content_encoding_and_declared_oversize_fail_before_read(self):
        for headers in ({"Content-Encoding": "gzip"}, {"Content-Length": "-1"},
                        {"Content-Length": str(smoke.MAX_RESPONSE + 1)}):
            self.response.headers = headers
            with self.subTest(headers=headers), self.assertRaises(smoke.SmokeError):
                self.api.request("GET", "/api/v1/products")
        self.assertEqual(self.response.reads, 0)

    def test_streaming_cap_does_not_trust_missing_length(self):
        self.response.body = b"x" * 11
        with patch.object(smoke, "MAX_RESPONSE", 10), self.assertRaisesRegex(smoke.SmokeError, "response_budget_exhausted"):
            self.api.request("GET", "/api/v1/products")

    def test_total_bytes_and_request_count_and_wall_budget_are_enforced(self):
        self.response.body = b"xx"
        self.api.bytes = smoke.MAX_TOTAL_BYTES - 1
        with self.assertRaises(smoke.SmokeError):
            self.api.request("GET", "/api/v1/products")
        self.factory.reset_mock()
        self.api.requests = smoke.MAX_REQUESTS
        with self.assertRaises(smoke.SmokeError):
            self.api.request("GET", "/api/v1/products")
        self.api.requests = 0
        self.api.started -= smoke.RUN_SECONDS + 1
        with self.assertRaises(smoke.SmokeError):
            self.api.request("GET", "/api/v1/products")
        self.factory.assert_not_called()

    def test_foreign_origins_loopback_and_destructive_paths_never_reach_connection(self):
        for path in ("https://dev.credit-cabinet.ru/api/v1/products", "https://user:secret@outside.invalid/",
                     "//outside.invalid/api/v1/products", "/sim/control", "/api/v1/../sim/control",
                     "/api/v1/agent/applications#fragment", "/api/v1/%2e%2e/control", "/api/v1/auth/sign-in\r\nx: x"):
            with self.subTest(path=path), self.assertRaises(smoke.SmokeError):
                self.api.request("GET", path)
        self.factory.assert_not_called()

    def test_request_body_bound_and_transport_error_sanitization(self):
        with self.assertRaisesRegex(smoke.SmokeError, "request_body_oversize"):
            self.api.request("POST", "/api/v1/auth/sign-in", data={"value": "x" * smoke.MAX_REQUEST})
        self.factory.assert_not_called()
        self.connection.request.side_effect = OSError("synthetic private credential must never be copied")
        with self.assertRaisesRegex(smoke.SmokeError, "^https_request_unconfirmed$"):
            self.api.request("POST", "/api/v1/auth/sign-in", data={"password": "synthetic private value"})

    def test_alarm_interrupts_the_entire_call_including_dripping_headers(self):
        self.connection.getresponse.side_effect = lambda: time.sleep(0.4)
        self.api.started = time.monotonic() - smoke.RUN_SECONDS + 0.1
        with self.assertRaisesRegex(smoke.SmokeError, "request_deadline"):
            self.api.request("GET", "/api/v1/products")
        self.assertEqual(signal.getitimer(signal.ITIMER_REAL), (0.0, 0.0))


class PrivateState(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="business-smoke-unit-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.context = smoke.Context("a" * 40, "b" * 64, self.root, {}, {})

    def write(self, name, value, mode=0o600):
        path = self.root / name
        path.write_bytes(encoded(value))
        path.chmod(mode)
        return path

    def test_private_reader_rejects_bad_mode_symlink_nonregular_and_size(self):
        path = self.write("private.json", {"ok": True}, 0o644)
        with self.assertRaises(smoke.SmokeError):
            smoke.private_json(path)
        path.chmod(0o600)
        link = self.root / "link.json"
        link.symlink_to(path)
        with self.assertRaises(OSError):
            smoke.private_json(link)
        fifo = self.root / "not-a-secret-record"
        os.mkfifo(fifo, 0o600)
        with self.assertRaises(smoke.SmokeError):
            smoke.private_json(fifo)
        path.write_bytes(b"x" * (smoke.MAX_RECORD + 1))
        with self.assertRaises(smoke.SmokeError):
            smoke.private_json(path)

    def test_jury_is_read_only_and_never_created(self):
        with self.assertRaises(FileNotFoundError):
            smoke.jury_credentials(self.root)
        self.assertFalse((self.root / "jury.json").exists())
        credentials = {r: {"email": r.lower() + "@jury.test", "password": r + "x" * 24}
                       for r in ("CLIENT", "AGENT", "UNDERWRITER")}
        path = self.write("jury.json", credentials)
        before = path.read_bytes()
        self.assertEqual(smoke.jury_credentials(self.root), credentials)
        self.assertEqual(path.read_bytes(), before)

    def test_ledger_atomic_private_and_changed_ledger_or_existing_run_refuses_retry(self):
        ledger = smoke.Ledger(self.context)
        self.assertEqual(ledger.path.stat().st_mode & 0o777, 0o600)
        with self.assertRaisesRegex(smoke.SmokeError, "existing_run_requires_operator_reconciliation"):
            smoke.Ledger(self.context)
        ledger.path.write_text('{}')
        with self.assertRaisesRegex(smoke.SmokeError, "ledger_changed"):
            ledger.save()

    def test_creation_intent_is_durable_before_response_and_before_uuid_clear(self):
        ledger = smoke.Ledger(self.context)
        intent = ledger.beginning("applications", "A")
        self.assertEqual(smoke.private_json(ledger.path)["pending"], [intent])
        snapshots = []
        original = ledger.save
        def save():
            original()
            snapshots.append(deepcopy(smoke.private_json(ledger.path)))
        ledger.save = save
        app = str(uuid4())
        ledger.created(intent, app)
        self.assertIn(app, snapshots[0]["applications"])
        self.assertEqual(snapshots[0]["pending"], [intent])
        self.assertEqual(snapshots[-1]["pending"], [])

    def test_synthetic_inn_checksum_and_credentials_recover_without_persisting_secret(self):
        run_id = str(uuid4())
        inn = smoke.synthetic_inn(run_id + ":A")
        self.assertEqual(len(inn), 10)
        self.assertTrue(inn.startswith("00"))
        self.assertEqual(int(inn[-1]), sum(int(n) * w for n, w in zip(inn[:9], (2, 4, 10, 3, 5, 9, 4, 6, 8))) % 11 % 10)
        credentials = {"AGENT": {"password": "synthetic-secret-" * 3}}
        a = smoke.client_credentials(run_id, "A", credentials)
        self.assertEqual(a, smoke.client_credentials(run_id, "A", credentials))
        self.assertNotEqual(a, smoke.client_credentials(run_id, "B", credentials))
        ledger = smoke.Ledger(self.context)
        self.assertNotIn(a["password"], ledger.path.read_text())

    def test_owned_registry_rejects_jury_company_and_bad_uuid(self):
        ledger = smoke.Ledger(self.context)
        for ident in (smoke.JURY_COMPANY, "../unsafe", "not-uuid"):
            with self.subTest(ident=ident), self.assertRaises(smoke.SmokeError):
                ledger.own("applications", ident, "A")

    def test_mutation_failure_retains_secret_free_intent_without_automatic_retry(self):
        ledger = smoke.Ledger(self.context)
        api = Mock()
        api.request.side_effect = smoke.SmokeError("https_request_unconfirmed")
        runner = smoke.Smoke(api, ledger, {})
        with self.assertRaises(smoke.SmokeError):
            runner.call("A", "POST", "/api/v1/auth/sign-up", data={"password": "never-persist-this"})
        self.assertEqual(api.request.call_count, 1)
        self.assertEqual(len(smoke.private_json(ledger.path)["pending"]), 1)
        self.assertNotIn("never-persist-this", ledger.path.read_text())

    def test_keyboard_interrupt_is_converted_to_fail_closed_error_and_retained_ledger(self):
        runner = Mock()
        runner.signin.side_effect = KeyboardInterrupt
        runner.cleanup.return_value = True
        previous = signal.getsignal(signal.SIGTERM)
        with patch.object(smoke, "verified_context", return_value=self.context), \
                patch.object(smoke, "jury_credentials", return_value={"AGENT": {}, "UNDERWRITER": {}}), \
                patch.object(smoke, "API"), patch.object(smoke, "Smoke", return_value=runner), \
                self.assertRaisesRegex(smoke.SmokeError, "business_smoke_interrupted"):
            smoke.run(None, self.root, {}, {}, {}, lock_fd=0)
        saved = next((self.root / "business-smoke").glob('*.json'))
        self.assertEqual(smoke.private_json(saved)["status"], "INCOMPLETE")
        self.assertEqual(signal.getsignal(signal.SIGTERM), previous)
        runner.cleanup.assert_called_once()

    def test_unowned_route_is_rejected_before_any_api_call(self):
        api = Mock()
        runner = smoke.Smoke(api, smoke.Ledger(self.context), {})
        with self.assertRaisesRegex(smoke.SmokeError, "unowned_uuid_route"):
            runner.call("AGENT", "POST", "/api/v1/agent/applications/" + str(uuid4()) + "/cancel")
        api.request.assert_not_called()

    def test_cleanup_retains_funded_and_never_enumerates_or_deletes_foreign_objects(self):
        ledger = smoke.Ledger(self.context)
        company, user, app, mandate = (str(uuid4()) for _ in range(4))
        ledger.own("clients", company, {"alias": "A", "user_id": user})
        ledger.own("applications", app, "A")
        ledger.own("mandates", mandate, "A")
        api = Mock()
        api.request.side_effect = [(200, encoded({"id": app, "company": {"id": company}, "status": "DISBURSED"})), (204, b"")]
        runner = smoke.Smoke(api, ledger, {})
        runner.sessions["A"] = {"token": "synthetic-token", "user": {"companyId": company}}
        self.assertTrue(runner.cleanup())
        calls = [c.args[:2] for c in api.request.call_args_list]
        self.assertEqual(calls, [("GET", "/api/v1/applications/" + app),
                                 ("DELETE", "/api/v1/companies/me/agent-mandates/" + mandate)])
        self.assertEqual(ledger.data["cleanup"]["funded_retained_application_ids"], [app])
        self.assertEqual(ledger.data["browser_proof"], "NOT_RUN")

    def test_cleanup_wrong_owner_or_unknown_intent_stays_incomplete(self):
        ledger = smoke.Ledger(self.context)
        app, company = str(uuid4()), str(uuid4())
        ledger.own("applications", app, "A")
        ledger.beginning("applications", "A")
        api = Mock()
        api.request.return_value = (200, encoded({"id": app, "company": {"id": str(uuid4())}, "status": "DRAFT"}))
        runner = smoke.Smoke(api, ledger, {})
        runner.sessions["A"] = {"token": "synthetic-token", "user": {"companyId": company}}
        self.assertFalse(runner.cleanup())
        self.assertEqual(api.request.call_count, 1)
        self.assertEqual(ledger.data["cleanup"]["failed_owned_ids"], [app])
        self.assertEqual(ledger.data["cleanup"]["unknown_mutation_intents_retained"], 1)

    def test_cleanup_cancels_only_known_agent_private_draft(self):
        ledger = smoke.Ledger(self.context)
        app, company = str(uuid4()), str(uuid4())
        ledger.own("applications", app, "A")
        api = Mock()
        api.request.side_effect = [(404, b""), (200, encoded({"id": app, "company": {"id": company}, "status": "DRAFT"})), (200, b"{}")]
        runner = smoke.Smoke(api, ledger, {})
        runner.sessions["A"] = {"token": "synthetic-token", "user": {"companyId": company}}
        runner.sessions["AGENT"] = {"token": "synthetic-agent-token"}
        self.assertTrue(runner.cleanup())
        self.assertEqual(api.request.call_args_list[-1].args[1], "/api/v1/agent/applications/" + app + "/cancel")

    def test_saved_cleanup_reauthenticates_exact_own_client_and_never_grants_acceptance(self):
        ledger = smoke.Ledger(self.context)
        company, user, app, mandate = (str(uuid4()) for _ in range(4))
        ledger.own("clients", company, {"alias": "A", "user_id": user})
        ledger.own("applications", app, "A")
        ledger.own("mandates", mandate, "A")
        credentials = {"AGENT": {"password": "synthetic-cleanup-secret"}}
        client = {"token": "synthetic-client-token", "user": {"role": "CLIENT", "id": user, "companyId": company}}
        api = Mock()
        api.request.side_effect = [(200, encoded({"token": "synthetic-agent-token", "user": {"role": "AGENT", "id": str(uuid4())}})),
            (200, encoded(client)), (200, encoded({"id": app, "company": {"id": company}, "status": "DRAFT"})),
            (200, b"{}"), (200, encoded({"id": app, "company": {"id": company}, "status": "WITHDRAWN"})), (204, b"")]
        with patch.object(smoke, "verified_context", return_value=self.context), patch.object(smoke, "jury_credentials", return_value=credentials), \
                patch.object(smoke, "API", return_value=api), patch.object(smoke.time, "sleep"):
            result = smoke.cleanup_saved(None, self.root, {}, {}, {}, ledger.path, lock_fd=0)
        self.assertTrue(result["cleanup_complete"])
        self.assertEqual(result["business_acceptance"], "NOT_GRANTED")
        self.assertEqual(smoke.private_json(ledger.path)["status"], "INCOMPLETE")
        text = ledger.path.read_text()
        self.assertNotIn("synthetic-cleanup-secret", text)
        self.assertNotIn("synthetic-client-token", text)

    def test_saved_cleanup_wrong_authenticated_company_stops_before_any_cleanup_write(self):
        ledger = smoke.Ledger(self.context)
        company, user = str(uuid4()), str(uuid4())
        ledger.own("clients", company, {"alias": "A", "user_id": user})
        before = ledger.path.read_bytes()
        api = Mock()
        api.request.side_effect = [(200, encoded({"token": "synthetic-agent-token", "user": {"role": "AGENT", "id": str(uuid4())}})),
            (200, encoded({"token": "synthetic-token", "user": {"role": "CLIENT", "id": user, "companyId": str(uuid4())}}))]
        with patch.object(smoke, "verified_context", return_value=self.context), \
                patch.object(smoke, "jury_credentials", return_value={"AGENT": {"password": "synthetic-secret"}}), \
                patch.object(smoke, "API", return_value=api), self.assertRaisesRegex(smoke.SmokeError, "cleanup_session_owner_mismatch"):
            smoke.cleanup_saved(None, self.root, {}, {}, {}, ledger.path, lock_fd=0)
        self.assertEqual(api.request.call_count, 2)
        self.assertEqual(ledger.path.read_bytes(), before)


class VerifiedContext(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="business-context-unit-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.state = self.root / "state"
        self.state.mkdir(mode=0o700)
        self.sha = "a" * 40
        self.release_dir = self.root / self.sha
        self.release_dir.mkdir()
        path = self.release_dir / "release.json"
        path.write_bytes(encoded(remote_manifest(self.sha)))
        self.release = RegistryRelease(path)
        class Deployment:
            pass
        self.deployment = Deployment()
        self.deployment.project, self.deployment.local = "cabinet-dev", False
        self.deployment.dev_payment_reserve_simulation = True
        self.deployment.release, self.deployment.path = self.release, self.state / "cabinet.env"
        self.deployment.values = {"PUBLIC_CABINET_URL": smoke.ORIGIN, "DEMO_ACCOUNTS": "false", "SIM_CONTROL_ENABLED": "false"}
        self.current = {"revision": "c" * 40, "manifest_sha256": "d" * 64,
                        "alpha_dev": {"generation": "g0123456789abcdef", "read_enabled": False}}
        self.desired = {"revision": self.sha, "manifest_sha256": self.release.checksum,
                        **{name: {"mode": "remote"} for name in smoke.RUNTIMES}, "alpha_dev": self.current["alpha_dev"]}
        for name, data in (("current.json", self.current), ("pending.json", self.desired)):
            p = self.state / name
            p.write_bytes(encoded(data))
            p.chmod(0o600)
        self.fd = os.open(self.state / "deploy.lock", os.O_CREAT | os.O_RDWR, 0o600)
        self.addCleanup(os.close, self.fd)
        fcntl.flock(self.fd, fcntl.LOCK_EX)
        self.report = {"manifest_sha256": self.release.checksum, "release": {"passed": True, "expected_revision": self.sha,
                        **{name: {"mode": "remote", "revision": self.sha, "manifest_sha256": self.release.checksum} for name in smoke.RUNTIMES}}}
        self.modules = patch.dict(sys.modules, {"common": SimpleNamespace(Deployment=Deployment, ROOT=self.release_dir),
                            "runtime_attempts": SimpleNamespace(load=lambda *_: {"attempt": "f" * 32, "previous": self.current, "target": self.desired,
                            "backup": {"sha256": "e" * 64}, "alpha": {"frozen": {"checkpoint": {"generation": self.current["alpha_dev"]["generation"]}}}})})
        self.modules.start()
        self.addCleanup(self.modules.stop)

    def invoke(self):
        return smoke.verified_context(self.deployment, self.state, self.current, self.desired, self.report, self.fd)

    def test_selector_and_host_context_share_canonical_origin_with_optional_slash(self):
        from dev_payment_reserve import enabled
        for origin in (smoke.ORIGIN, smoke.ORIGIN + "/"):
            with self.subTest(origin=origin), patch.object(smoke, "API") as api:
                self.deployment.values["PUBLIC_CABINET_URL"] = origin
                values = {**self.deployment.values, "CABINET_DOMAIN": smoke.HOST}
                self.assertTrue(enabled("cabinet-dev", False, values, self.release))
                self.assertEqual(self.invoke().revision, self.sha)
                api.assert_not_called()

    def test_selector_and_context_reject_extra_origin_components_before_api(self):
        from dev_payment_reserve import enabled
        origins = (smoke.ORIGIN + "//", smoke.ORIGIN + "/path", smoke.ORIGIN + "?x=1",
                   smoke.ORIGIN + "#x", smoke.ORIGIN + ":443", "https://user@" + smoke.HOST,
                   "http://" + smoke.HOST, "https://" + smoke.HOST + ".outside.invalid", None, [])
        for origin in origins:
            with self.subTest(origin=origin), patch.object(smoke, "API") as api:
                self.deployment.values["PUBLIC_CABINET_URL"] = origin
                values = {**self.deployment.values, "CABINET_DOMAIN": smoke.HOST}
                self.assertFalse(enabled("cabinet-dev", False, values, self.release))
                with self.assertRaisesRegex(smoke.SmokeError, "dev_origin_or_feature_policy_changed"):
                    self.invoke()
                api.assert_not_called()

    def test_actual_state_attempt_release_and_computed_feature_gate(self):
        self.assertIsInstance(self.release, RegistryRelease)
        self.assertNotIn("catalog", self.release.runtime)
        self.assertIs(self.release.catalog, self.release.runtime["catalog-grpc"])
        self.assertEqual(self.invoke().revision, self.sha)
        self.deployment.values["DEV_PAYMENT_RESERVE_SIMULATION_ENABLED"] = "true"
        self.deployment.dev_payment_reserve_simulation = False
        with self.assertRaisesRegex(smoke.SmokeError, "verified_dev_feature_context_required"):
            self.invoke()

    def test_prod_foreign_origin_local_or_disabled_feature_never_starts_api(self):
        for name, value in (("project", "cabinet-prod"), ("local", True), ("dev_payment_reserve_simulation", False)):
            before = getattr(self.deployment, name)
            setattr(self.deployment, name, value)
            with self.subTest(name=name), patch.object(smoke, "API") as api, self.assertRaises(smoke.SmokeError):
                smoke.run(self.deployment, self.state, self.current, self.desired, self.report, lock_fd=self.fd)
            api.assert_not_called()
            setattr(self.deployment, name, before)

    def test_malformed_metadata_is_sanitized_before_api_and_matches_controller_failure_boundary(self):
        cases = (
            ("runtime_attempts", {"load": lambda *_: {"attempt": "f" * 32, "synthetic-private-key": "never expose"}}),
            ("report", {"release": None}),
        )
        for kind, values in cases:
            with self.subTest(kind=kind), patch.object(smoke, "API") as api:
                if kind == "runtime_attempts":
                    manager = patch.dict(sys.modules, {"runtime_attempts": SimpleNamespace(**values)})
                    arguments = self.report
                else:
                    manager = nullcontext()
                    arguments = {**self.report, **values}
                with manager, self.assertRaisesRegex(smoke.SmokeError, "^verified_dev_context_unconfirmed$") as error:
                    smoke.run(self.deployment, self.state, self.current, self.desired, arguments, lock_fd=self.fd)
                self.assertIsInstance(error.exception, RuntimeError)
                self.assertTrue(error.exception.__suppress_context__)
                self.assertNotIn("synthetic-private", str(error.exception))
                api.assert_not_called()

    def test_missing_release_descriptor_is_a_fixed_failure_not_a_raw_key_or_attribute_error(self):
        del self.release.catalog
        with patch.object(smoke, "API") as api, self.assertRaisesRegex(smoke.SmokeError, "^verified_dev_context_unconfirmed$"):
            smoke.run(self.deployment, self.state, self.current, self.desired, self.report, lock_fd=self.fd)
        api.assert_not_called()

    def test_pending_current_digest_runtime_and_lock_mismatches_fail(self):
        (self.state / "pending.json").write_bytes(encoded({**self.desired, "manifest_sha256": "e" * 64}))
        with self.assertRaisesRegex(smoke.SmokeError, "actual_current_pending_or_manifest_changed"):
            self.invoke()
        (self.state / "pending.json").write_bytes(encoded(self.desired))
        self.report["release"]["servicing"]["mode"] = "local"
        with self.assertRaisesRegex(smoke.SmokeError, "runtime_verification_required"):
            self.invoke()
        self.report["release"]["servicing"]["mode"] = "remote"
        wrong = os.open(self.state / "wrong.lock", os.O_CREAT | os.O_RDWR, 0o600)
        try:
            with self.assertRaisesRegex(smoke.SmokeError, "held_deployment_lock_required"):
                smoke.verified_context(self.deployment, self.state, self.current, self.desired, self.report, wrong)
        finally:
            os.close(wrong)


class HookCompatibility(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="business-hook-unit-")
        self.addCleanup(self.temp.cleanup)
        self.state = Path(self.temp.name)
        self.revision = "a" * 40
        self.manifest = remote_manifest(self.revision)
        path = self.state / "release.json"
        path.write_bytes(encoded(self.manifest))
        self.release = RegistryRelease(path)
        self.snapshot = "b" * 64
        self.generation = "g8b1a02f4d62af8c0"
        self.topic = "alpha.dev.credit.application-status-changed.v1." + self.generation
        self.dlq = self.topic + ".dlq"
        bounds = {"0": 0, "1": 0, "2": 0}
        checkpoint = {"generation": self.generation, "marker": self.topic + " " + self.snapshot,
                      "topics": {topic: {"id": "A" * 22, "begin": dict(bounds), "end": dict(bounds)}
                                 for topic in (self.topic, self.dlq)},
                      "committed": dict(bounds),
                      "outbox": {"cutoff": "2026-11-27T00:00:00Z", "rows": 1, "sha256": "c" * 64, "open": 0}}
        checkpoint["topics"][self.topic]["end"]["0"] = 1
        checkpoint["committed"]["0"] = 1
        self.before = {"volumes": {"alpha-analytics-data": {"name": "synthetic-analytics", "created_at": "1"}},
                       "checkpoint": checkpoint}
        self.after = deepcopy(checkpoint)
        self.after["topics"][self.topic]["end"]["0"] = 2
        self.after["committed"]["0"] = 2
        self.application = str(uuid4())
        self.source_rows = json.dumps([self.application, 2, "MANUAL_REVIEW", 2, "MANUAL_REVIEW"])
        self.target_rows = json.dumps([self.application, 2, "MANUAL_REVIEW"])
        self.deployment = SimpleNamespace(
            project="cabinet-dev", dev_payment_reserve_simulation=True,
            runtime_modes={name: "remote" for name in smoke.RUNTIMES},
            values={"PUBLIC_CABINET_URL": smoke.ORIGIN},
            alpha_dev=SimpleNamespace(topic=self.topic, dlq=self.dlq),
            sql=Mock(return_value=self.source_rows), compose=Mock(),
        )
        self.attempt = {"attempt": "d" * 32, "alpha": {"frozen": self.before}}
        self.old_ready = {"manifest_sha256": self.release.checksum, "caught_up": True, "historical": "kept"}
        self.pending = self.state / "pending.json"
        self.pending.write_bytes(b"synthetic pending retained")
        self.current = self.state / "current.json"
        self.current.write_bytes(b"synthetic current retained")
        self.events, self.helper_results = [], []

    def execute(self, *, fail_alpha=False, alpha_error=None):
        import runtime_backup as backup
        tree = ast.parse(controller_source())
        blocks = [node for node in ast.walk(tree) if isinstance(node, ast.Try)
                  and any(isinstance(call, ast.Call) and isinstance(call.func, ast.Name)
                          and call.func.id == "run_business_smoke" for call in ast.walk(node))]
        self.assertEqual(len(blocks), 1)
        original_helper = backup.verify_alpha_catchup
        def business(*args, **kwargs):
            self.events.append("business_and_cleanup_complete")
            return {"api_business_proof": "PASS"}
        def fresh(deployment, before):
            self.events.append("fresh_alpha")
            self.assertEqual(self.events[0], "business_and_cleanup_complete")
            if fail_alpha:
                raise RuntimeError("synthetic fresh Alpha unavailable")
            if alpha_error is not None:
                raise alpha_error
            value = original_helper(deployment, before)
            self.helper_results.append(value)
            return value
        namespace = {
            "args": SimpleNamespace(verify_only=False, deployment_fd=123),
            "state_dir": self.state, "release": self.release, "deployment": self.deployment,
            "status": {}, "desired": {}, "pending_file": self.pending,
            "ROOT": ROOT, "env_args": [], "release_args": [], "json": json, "subprocess": subprocess, "COMPANY": smoke.JURY_COMPANY,
            "canonical_origin": smoke.canonical_origin,
            "read_credentials": Mock(return_value={}), "provision": Mock(), "verify": Mock(return_value=[]),
            "run": Mock(return_value=json.dumps({"passed": True})), "run_business_smoke": business,
            "runtime_attempts": SimpleNamespace(load=Mock(return_value=self.attempt)),
            "read_record": Mock(return_value=self.old_ready),
        }
        def analytics(*args):
            return "t" if "application_status_versions" in args[-1] else self.target_rows
        with patch.object(backup, "verify_alpha_catchup", side_effect=fresh) as proof, \
                patch.object(backup, "alpha_volumes", return_value=self.before["volumes"]), \
                patch.object(backup, "verify_credit_mode", return_value="sql"), \
                patch.object(backup, "analytics_owner", side_effect=analytics), \
                patch.object(backup, "alpha_checkpoint", return_value=self.after), \
                patch.object(backup, "kafka", return_value=self.topic + ":0:2\n" + self.topic + ":1:0\n" + self.topic + ":2:0"):
            exec(compile(ast.Module(body=blocks, type_ignores=[]), "controller-business-hook", "exec"), namespace)
        proof.assert_called_once_with(self.deployment, self.before)
        return namespace["report"]

    def test_actual_controller_contains_hook_and_keeps_valid_syntax(self):
        controller = ROOT / "deploy/dev-release.py"
        before = controller.read_bytes()
        ast.parse(controller_source())
        self.assertEqual(controller.read_bytes(), before)

    def no_pending_controller(self, origin=smoke.ORIGIN):
        spec = importlib.util.spec_from_file_location("business_noop_actual_controller", ROOT / "deploy/dev-release.py")
        controller = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(controller)
        self.pending.unlink(missing_ok=True)
        alpha_state = {"generation": self.generation, "read_enabled": False}
        desired = {"revision": self.release.revision, "manifest_sha256": self.release.checksum,
                   **{name: {"mode": "remote"} for name in smoke.RUNTIMES}, "alpha_dev": alpha_state}
        self.current.write_bytes(encoded(desired))
        env = self.state / "cabinet.env"
        env.write_text("CABINET_ALPHA_DEV_ENABLED=true\nCATALOG_DB_PASSWORD=synthetic\n")
        env.chmod(0o600)
        self.deployment.values["CABINET_ALPHA_DEV_ENABLED"] = "true"
        self.deployment.values["PUBLIC_CABINET_URL"] = origin
        self.deployment.catalog_enabled = True
        self.deployment.catalog_mode = "remote"
        self.deployment.catalog_state = self.state / "catalog"
        self.deployment.alpha_dev = SimpleNamespace(state=lambda: alpha_state, generation=self.generation, read_enabled=False)
        return controller

    def test_actual_same_current_retry_without_pending_does_not_run_business_or_create_fixtures(self):
        controller = self.no_pending_controller()
        before = {path.name: path.read_bytes() for path in self.state.iterdir() if path.is_file()}
        with (patch.object(controller, "Deployment", return_value=self.deployment),
              patch.object(controller, "read_credentials", return_value={}),
              patch.object(controller, "provision"), patch.object(controller, "verify", return_value=[]),
              patch.object(controller, "run", return_value="{}") as run,
              patch.object(controller, "capture_previous", side_effect=AssertionError("no-op recaptured backup")),
              patch.object(controller, "run_business_smoke", side_effect=AssertionError("no-op created business fixtures")) as business_call,
              patch.object(controller.runtime_attempts, "begin", side_effect=AssertionError("no-op began another attempt")),
              patch.object(smoke, "API", side_effect=AssertionError("no-op created business API")),
              patch.object(smoke, "Ledger", side_effect=AssertionError("no-op created business ledger")),
              redirect_stdout(StringIO())):
            controller.deploy(SimpleNamespace(verify_only=False, deployment_fd=123), self.state, self.release)
        business_call.assert_not_called()
        self.assertEqual([call.args[1] for call in run.call_args_list], ["verify-release.py"])
        self.assertFalse(self.pending.exists())
        self.assertFalse((self.state / "business-smoke").exists())
        self.assertEqual({path.name: path.read_bytes() for path in self.state.iterdir() if path.is_file()}, before)
        self.deployment.compose.assert_not_called()

    def role_probe(self, configured_origin, expected_origin, *, feature_enabled=True):
        import jury
        controller = self.no_pending_controller(configured_origin)
        self.deployment.dev_payment_reserve_simulation = feature_enabled
        self.assertIs(controller.verify, jury.verify)  # Exercise the real role checker.
        credentials = {role: {"email": details[0], "password": "synthetic-role-password-" + role}
                       for role, details in jury.USERS.items()}
        paths = []
        def open_request(request, timeout):
            parsed = urlsplit(request.full_url)
            self.assertEqual(parsed.scheme + "://" + parsed.netloc, expected_origin)
            self.assertFalse(parsed.path.startswith("//"))
            route = parsed.path + ("?" + parsed.query if parsed.query else "")
            paths.append(route)
            token = request.get_header("Authorization")
            role = next((role for role in jury.USERS if token == "Bearer synthetic-" + role + "-token"), None)
            if route == "/":
                self.assertEqual(request.get_method(), "GET")
                status, body = 200, b"synthetic cabinet page"
            elif route == "/api/v1/auth/sign-in":
                self.assertEqual(request.get_method(), "POST")
                payload = json.loads(request.data)
                role = next((r for r, values in credentials.items() if payload == values), None)
                self.assertIn(role, jury.USERS)
                user = {"id": jury.USERS[role][1], "fullName": "Synthetic " + role, "email": credentials[role]["email"], "role": role, "status": "ACTIVE",
                        "companyId": jury.COMPANY if role == "CLIENT" else None, "canSign": role == "CLIENT"}
                status, body = 200, encoded({"token": "synthetic-" + role + "-token", "user": user})
            elif route == "/api/v1/companies/me":
                self.assertEqual(role, "CLIENT")
                status, body = 200, encoded({"id": jury.COMPANY, "inn": "0000000000", "name": "Synthetic role company"})
            elif route == "/api/v1/agent/clients":
                if role == "AGENT":
                    status, body = 200, encoded([{"companyId": jury.COMPANY, "inn": "0000000000", "name": "Synthetic role company",
                                                "legalForm": "LEGAL_ENTITY", "assignedAt": "2026-11-28T00:00:00Z"}])
                else:
                    status, body = (403 if role in {"CLIENT", "UNDERWRITER"} else 401), b"{}"
            elif route == "/api/v1/applications/page?size=1&group=review":
                self.assertIn(role, {"AGENT", "UNDERWRITER"})
                status, body = (200 if role == "UNDERWRITER" else 403), encoded({"items": [], "page": 0, "size": 1,
                    "totalElements": 0, "totalPages": 0, "hasNext": False, "countsByGroup": {},
                    "actionRequiredCount": 0, "actionRequiredPreview": []})
            elif route == "/sim/control":
                status, body = 404, b"{}"
            else:
                raise AssertionError("unexpected synthetic role route")
            response = BytesIO(body)
            response.status = status
            return response
        before = {path.name: path.read_bytes() for path in self.state.iterdir() if path.is_file()}
        with (patch.object(controller, "Deployment", return_value=self.deployment),
              patch.object(controller, "read_credentials", return_value=credentials),
              patch.object(controller, "provision"),
              patch.object(controller, "run", return_value="{}"),
              patch.object(controller, "run_business_smoke", side_effect=AssertionError("role probe created business fixtures")) as business,
              patch.object(jury, "build_opener", return_value=SimpleNamespace(open=open_request)) as opener,
              patch("socket.create_connection", side_effect=AssertionError("role probe opened network")),
              patch("socket.getaddrinfo", side_effect=AssertionError("role probe resolved DNS")),
              redirect_stdout(StringIO()) as output):
            controller.deploy(SimpleNamespace(verify_only=False, deployment_fd=123), self.state, self.release)
        report = json.loads(output.getvalue())
        self.assertEqual(len(report["role_checks"]), 11)
        self.assertEqual([row["status"] for row in report["role_checks"]], [200] * 6 + [403] * 3 + [401, 404])
        self.assertEqual(len(paths), 12)
        self.assertEqual(paths[0], "/")
        self.assertEqual(paths.count("/api/v1/auth/sign-in"), 3)
        self.assertTrue(all(not path.startswith("//") for path in paths))
        opener.assert_called_once(); business.assert_not_called()
        self.assertEqual({path.name: path.read_bytes() for path in self.state.iterdir() if path.is_file()}, before)
        self.deployment.compose.assert_not_called()

    def test_actual_controller_real_jury_role_probe_canonicalizes_both_dev_forms(self):
        for configured in (smoke.ORIGIN, smoke.ORIGIN + "/"):
            with self.subTest(configured=configured):
                self.role_probe(configured, smoke.ORIGIN)

    def test_actual_controller_real_role_probe_preserves_other_disabled_origin(self):
        self.role_probe("https://legacy.example.invalid", "https://legacy.example.invalid", feature_enabled=False)

    def test_actual_helper_fresh_shape_gets_exact_current_checksum_after_business(self):
        report = self.execute()
        self.assertEqual(self.events, ["business_and_cleanup_complete", "fresh_alpha"])
        fresh, = self.helper_results
        self.assertNotIn("manifest_sha256", fresh)
        self.assertEqual(report["alpha_continuity"], {**fresh, "manifest_sha256": self.release.checksum})
        self.assertEqual(fresh["checkpoint"]["committed"]["0"], 2)
        self.assertIs(report["alpha_continuity"]["checkpoint"], self.after)
        self.assertTrue(report["release"]["business_smoke"]["alpha_catchup_verified"])
        self.assertEqual(self.old_ready, {"manifest_sha256": self.release.checksum, "caught_up": True, "historical": "kept"})
        self.assertEqual(self.current.read_bytes(), b"synthetic current retained")
        self.assertEqual(self.pending.read_bytes(), b"synthetic pending retained")
        self.deployment.compose.assert_not_called()

    def test_fresh_alpha_failure_closes_candidate_and_keeps_current_pending(self):
        with self.assertRaisesRegex(RuntimeError, "synthetic fresh Alpha unavailable"):
            self.execute(fail_alpha=True)
        self.deployment.compose.assert_called_once_with("stop", "caddy")
        self.assertEqual(self.current.read_bytes(), b"synthetic current retained")
        self.assertEqual(self.pending.read_bytes(), b"synthetic pending retained")

    def test_fresh_alpha_subprocess_failures_close_candidate_and_keep_current_pending(self):
        for error in (subprocess.CalledProcessError(1, ["synthetic-private-command"], output="never expose"),
                      subprocess.TimeoutExpired(["synthetic-private-command"], 3, output="never expose")):
            with self.subTest(error=type(error).__name__), self.assertRaises(subprocess.SubprocessError):
                self.execute(alpha_error=error)
            self.deployment.compose.assert_called_once_with("stop", "caddy")
            self.assertEqual(self.current.read_bytes(), b"synthetic current retained")
            self.assertEqual(self.pending.read_bytes(), b"synthetic pending retained")
            self.deployment.compose.reset_mock()
            self.events.clear()


class BusinessAssertions(unittest.TestCase):
    def test_scenario_checks_actual_financial_outputs_not_just_positive_numbers(self):
        from decimal import Decimal
        rate = Decimal(20) / 1200
        options = []
        for term in (12, 24):
            monthly = Decimal(2_000_000) * rate / (1 - (1 + rate) ** -term)
            options.append({"termMonths": term, "monthlyPayment": float(monthly),
                            "totalInterest": float(monthly * term - 2_000_000), "balanceOnPaymentDate": float(150000 - monthly)})
        snapshot = {"loanAmount": 2_000_000, "annualRatePercent": 20, "options": options}
        smoke.checked_options(snapshot)
        snapshot["options"][0]["balanceOnPaymentDate"] += 140000
        with self.assertRaisesRegex(smoke.SmokeError, "scenario_calculation_mismatch"):
            smoke.checked_options(snapshot)

    def reserve_fixture(self, corrupt=False):
        temp = tempfile.TemporaryDirectory(prefix="business-reserve-unit-")
        self.addCleanup(temp.cleanup)
        ledger = smoke.Ledger(smoke.Context("a" * 40, "b" * 64, Path(temp.name), {}, {}))
        api = Mock()
        runner = smoke.Smoke(api, ledger, {})
        runner.sessions = {x: {"token": x + "_synthetic_token"} for x in ("A", "B", "AGENT")}
        loans, ids, answers = [], [], []
        for limit in (160000, 400000):
            app, loan_id = str(uuid4()), str(uuid4())
            ledger.own("applications", app, "A")
            ledger.own("loans", loan_id, app)
            ids.append(app)
            loan = {"id": loan_id, "applicationId": app, "approvedLimit": limit, "disbursedAmount": limit // 2,
                    "availableLimit": limit // 2, "tranches": [{"id": str(uuid4()), "amount": limit // 2}]}
            loans.append(loan)
            waiting = {"version": 1, "receiptStatus": "PENDING", "canDraw": False}
            late = {"version": 2, "requiredAmount": 130000, "remainingLimit": limit // 2,
                    "shortfall": max(130000 - limit // 2, 0), "canDraw": limit >= 260000}
            if corrupt and limit == 160000:
                late["shortfall"] = 0
            answers.extend([(404, None), (403, None), (404, None), (404, None), (404, None), (403, None),
                            (200, waiting), (409, None), (200, late), (200, late), (409, None)])
            if limit == 160000:
                answers.extend([(409, None), (200, {"version": 3, "requiredAmount": 0, "shortfall": 0,
                                "projectedBalance": 40000, "canDraw": False}), (409, None), (200, loan)])
            else:
                drawn = {"version": 3, "drawnAmount": 130000}
                after = {**loan, "availableLimit": 70000, "disbursedAmount": 330000,
                         "tranches": loan["tranches"] + [{"id": str(uuid4()), "amount": 130000}]}
                answers.extend([(409, None), (200, drawn), (200, drawn), (200, after)])
        api.request.side_effect = [(status, encoded(body)) for status, body in answers]
        runner.create = Mock(side_effect=ids)
        runner.hand, runner.confirm = Mock(), Mock()
        runner.fund = Mock(side_effect=loans)
        return runner, api, ledger

    def test_normal_line160k400k_exact_quotes_and_same_event_confirmation_keys(self):
        runner, api, ledger = self.reserve_fixture()
        runner.reserve()
        facts = ledger.data["facts"]["reserve"]
        self.assertEqual(facts[0]["delayed"]["shortfall"], 50000)
        self.assertEqual(facts[0]["received"]["requiredAmount"], 0)
        self.assertEqual(facts[1]["confirmed"]["drawn_amount"], 130000)
        self.assertEqual(facts[1]["confirmed"]["tranches_after"] - facts[1]["confirmed"]["tranches_before"], 1)
        calls = api.request.call_args_list
        receipts = [c for c in calls if c.args[1].endswith('/receipt-event') and c.args[2] == 'A_synthetic_token']
        self.assertEqual(receipts[0].args[3], receipts[1].args[3])
        self.assertEqual(calls[-3].args[3], calls[-2].args[3])
        self.assertEqual(ledger.data["pending"], [])

    def test_wrong_absolute_shortfall_cannot_be_reported_as_pass(self):
        runner, _, ledger = self.reserve_fixture(corrupt=True)
        with self.assertRaisesRegex(smoke.SmokeError, "absolute_reserve_130000_or_shortfall50000_failed"):
            runner.reserve()
        self.assertEqual(ledger.data["checks"], [])

    def test_extra_tranche_on_replay_cannot_be_reported_as_pass(self):
        runner, api, ledger = self.reserve_fixture()
        replies = list(api.request.side_effect)
        loan = json.loads(replies[-1][1])
        loan["tranches"].append({"id": str(uuid4()), "amount": 130000})
        replies[-1] = (200, encoded(loan))
        api.request.side_effect = replies
        with self.assertRaisesRegex(smoke.SmokeError, "reserve_draw_not_exactly_one_effect"):
            runner.reserve()
        self.assertEqual(ledger.data["checks"], [])


if __name__ == "__main__":
    unittest.main()
