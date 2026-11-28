"""Offline completed-business replay and actual-controller failure/resume contracts."""
from contextlib import ExitStack, nullcontext, redirect_stdout, redirect_stderr
from copy import deepcopy
from dataclasses import replace
import hashlib
import importlib.util
from io import StringIO
import json
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from uuid import uuid4

import business_smoke as smoke
from registry_release import RegistryRelease
from test_business_smoke_delivery import remote_manifest

ROOT = Path(__file__).resolve().parents[1]


def completed_fixture(ledger, *, status="PASS"):
    """Synthetic private bookkeeping, not execution of HTTP or money operations."""
    companies, users, mandates = ([str(uuid4()) for _ in range(2)] for _ in range(3))
    apps = [str(uuid4()) for _ in range(5)]
    loans = [str(uuid4()) for _ in range(3)]
    docs = [str(uuid4()) for _ in range(2)]
    options = [{"termMonths": term, "monthlyPayment": 2_000_000 / term,
                "totalInterest": 0, "balanceOnPaymentDate": 150000 - 2_000_000 / term} for term in (12, 24)]
    reserves = []
    for index, limit in enumerate((160000, 400000)):
        value = {"application": apps[index + 2], "loan": loans[index + 1], "approved_limit": limit,
                 "initial_disbursed": limit // 2, "initial_available": limit // 2,
                 "delayed": {"version": 2, "requiredAmount": 130000, "remainingLimit": limit // 2,
                             "shortfall": max(130000 - limit // 2, 0), "canDraw": limit == 400000}}
        if index == 0:
            value["received"] = {"requiredAmount": 0, "shortfall": 0, "projectedBalance": 40000, "canDraw": False}
        else:
            value["confirmed"] = {"version_requested": 2, "drawn_amount": 130000, "after_available": 70000,
                                  "after_disbursed": 330000, "tranches_before": 1, "tranches_after": 2}
        reserves.append(value)
    ledger.data.update(status=status, invoice_mode="MANUAL", checks=sorted(smoke.CHECKS), pending=[],
        clients={company: {"alias": alias, "user_id": user} for company, user, alias in zip(companies, users, ("A", "B"))},
        mandates=dict(zip(mandates, ("A", "B"))), applications=dict(zip(apps, ("A", "B", "A", "A", "A"))),
        loans=dict(zip(loans, (apps[0], apps[2], apps[3]))), documents=dict(zip(docs, apps[:2])),
        facts={"financing": {"application": apps[0], "first_revision": 1, "accepted_revision": 2,
                            "selected_term_months": 24, "loan_amount": 2_000_000, "annual_rate_percent": 0,
                            "options": options, "confirm_submit_events": 1},
               "private_statement": {"application": apps[1], "document": docs[1], "bank_exact_bytes_sha256": "c" * 64,
                                     "agent_ready": True, "foreign_client_denied": True, "agent_bytes_denied": True},
               "reserve": reserves},
        cleanup={"failed_owned_ids": [], "funded_retained_application_ids": [apps[0], apps[2], apps[3]],
                 "audit_documents_accounts_retained": True, "unknown_mutation_intents_retained": 0})
    ledger.save()
    return ledger


def actual_controller():
    spec = importlib.util.spec_from_file_location("business_replay_actual_controller", ROOT / "deploy/dev-release.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ResumeUnit(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch("subprocess.run", side_effect=AssertionError("offline no processes")))
        self.stack.enter_context(patch("socket.create_connection", side_effect=AssertionError("offline no sockets")))
        self.stack.enter_context(patch("socket.getaddrinfo", side_effect=AssertionError("offline no DNS")))
        self.root = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.context = smoke.Context("a" * 40, "b" * 64, self.root, {"revision": "c" * 40}, {"revision": "a" * 40}, "d" * 32)

    def ledger(self):
        return completed_fixture(smoke.Ledger(self.context))

    def run_replay(self, context=None):
        with (patch.object(smoke, "verified_context", return_value=context or self.context) as verified,
              patch.object(smoke, "jury_credentials", side_effect=AssertionError("replay read jury")) as jury,
              patch.object(smoke, "API", side_effect=AssertionError("replay opened API")) as api,
              patch.object(smoke, "Smoke", side_effect=AssertionError("replay made mutations")) as flow,
              patch.object(smoke.Ledger, "save", side_effect=AssertionError("replay wrote ledger")) as save):
            result = smoke.run(None, self.root, {}, {}, {}, lock_fd=1)
        verified.assert_called_once()
        jury.assert_not_called(); api.assert_not_called(); flow.assert_not_called(); save.assert_not_called()
        return result

    def test_complete_exact_attempt_reuses_same_private_bytes_without_auth_api_or_write(self):
        ledger = self.ledger()
        before = ledger.path.read_bytes()
        proof = self.run_replay()
        self.assertTrue(proof["reused_completed"])
        self.assertEqual(proof["attempt_id"], self.context.attempt_id)
        self.assertEqual(proof["ledger_sha256"], hashlib.sha256(before).hexdigest())
        self.assertNotIn("alpha_catchup_verified", proof)
        self.assertTrue(proof["requires_fresh_alpha_catchup_after_smoke"])
        self.assertEqual(ledger.path.read_bytes(), before)

    def test_context_is_verified_before_any_replay_lookup(self):
        self.ledger()
        with (patch.object(smoke, "verified_context", side_effect=smoke.SmokeError("runtime_verification_required")),
              patch.object(smoke, "resume_completed") as resume,
              self.assertRaisesRegex(smoke.SmokeError, "runtime_verification_required")):
            smoke.run(None, self.root, {}, {}, {}, lock_fd=1)
        resume.assert_not_called()

    def test_different_attempt_manifest_current_desired_and_ci_context_are_denied(self):
        self.ledger()
        contexts = (replace(self.context, attempt_id="e" * 32), replace(self.context, digest="e" * 64),
                    replace(self.context, current={"revision": "e" * 40}), replace(self.context, desired={"revision": "e" * 40}),
                    replace(self.context, attempt_id=None))
        for context in contexts:
            with self.subTest(context=context), self.assertRaises(smoke.SmokeError):
                self.run_replay(context)

    def test_incomplete_legacy_ambiguous_and_unknown_intents_are_never_replayed(self):
        ledger = self.ledger()
        original = deepcopy(ledger.data)
        for key, value in (("status", "INCOMPLETE"), ("schema", 1), ("pending", [{"creating": "applications"}]),
                           ("checks", ["unknown"]), ("invoice_mode", "unknown")):
            ledger.data = {**deepcopy(original), key: value}; ledger.save()
            with self.subTest(key=key), self.assertRaises(smoke.SmokeError): self.run_replay()
        ledger.data = original; ledger.save()
        duplicate = ledger.path.with_name(self.context.revision + "-" + str(uuid4()) + ".json")
        duplicate.write_bytes(ledger.path.read_bytes()); duplicate.chmod(0o600)
        with self.assertRaisesRegex(smoke.SmokeError, "ambiguous_completed_business_ledger"):
            self.run_replay()

    def test_cleanup_ownership_numeric_and_financial_corruption_are_denied(self):
        ledger = self.ledger(); original = deepcopy(ledger.data)
        mutations = (
            lambda d: d["cleanup"].update(failed_owned_ids=[next(iter(d["applications"]))]),
            lambda d: d["cleanup"].update(unknown_mutation_intents_retained=True),
            lambda d: d["cleanup"].update(funded_retained_application_ids=[]),
            lambda d: d["applications"].update({next(iter(d["applications"])): "foreign"}),
            lambda d: d["loans"].update({next(iter(d["loans"])): str(uuid4())}),
            lambda d: d["facts"]["financing"].update(confirm_submit_events=True),
            lambda d: d["facts"]["financing"]["options"][0].update(monthlyPayment=float("nan")),
            lambda d: d["facts"]["financing"].update(annual_rate_percent=1e-308),
            lambda d: d["facts"]["reserve"][0]["received"].update(requiredAmount=False),
            lambda d: d["facts"]["reserve"][1]["delayed"].update(shortfall=False),
            lambda d: d["facts"]["reserve"][1]["confirmed"].update(tranches_after=3),
        )
        for index, mutate in enumerate(mutations):
            data = deepcopy(original); mutate(data)
            # Intentionally corrupt the private bytes, not the normal journal writer.
            ledger.path.write_text(json.dumps(data))
            with self.subTest(index=index), self.assertRaises(smoke.SmokeError): self.run_replay()

    def test_private_path_mode_filename_duplicate_json_and_nonregular_fail_closed(self):
        ledger = self.ledger(); raw = ledger.path.read_bytes()
        ledger.path.chmod(0o644)
        with self.assertRaises(smoke.SmokeError): self.run_replay()
        ledger.path.chmod(0o600)
        record = ledger.path
        target = self.root / "outside.json"; target.write_bytes(raw); target.chmod(0o600)
        record.unlink(); record.symlink_to(target)
        with self.assertRaises(smoke.SmokeError): self.run_replay()
        record.unlink(); record.write_bytes(b'{"schema":2,"schema":2}')
        record.chmod(0o600)
        with self.assertRaises(smoke.SmokeError): self.run_replay()
        record.write_bytes(raw)
        renamed = record.with_name(self.context.revision + "-malformed.json"); record.rename(renamed)
        with self.assertRaises(smoke.SmokeError): self.run_replay()


class ActualControllerResume(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack(); self.addCleanup(self.stack.close)
        self.stack.enter_context(patch("subprocess.run", side_effect=AssertionError("offline no processes")))
        self.stack.enter_context(patch("socket.create_connection", side_effect=AssertionError("offline no sockets")))
        self.stack.enter_context(patch("socket.getaddrinfo", side_effect=AssertionError("offline no DNS")))
        self.state = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.controller = actual_controller()
        revision = "a" * 40
        path = self.state / "release.json"; path.write_text(json.dumps(remote_manifest(revision)))
        self.release = RegistryRelease(path)
        alpha = {"generation": "g8b1a02f4d62af8c0", "read_enabled": False}
        self.previous = {"revision": "c" * 40, "manifest_sha256": "d" * 64,
                         **{name: {"mode": "remote"} for name in smoke.RUNTIMES}, "alpha_dev": alpha}
        self.desired = {**self.previous, "revision": revision, "manifest_sha256": self.release.checksum}
        for name, value in (("current.json", self.previous), ("pending.json", self.desired)):
            p = self.state / name; p.write_text(json.dumps(value)); p.chmod(0o600)
        self.env = self.state / "cabinet.env"; self.env.write_text("CABINET_ALPHA_DEV_ENABLED=true\nCATALOG_DB_PASSWORD=synthetic\n"); self.env.chmod(0o600)
        self.context = smoke.Context(revision, self.release.checksum, self.state, self.previous, self.desired, "e" * 32)
        self.attempt = {"attempt": self.context.attempt_id, "previous": self.previous, "target": self.desired,
                        "alpha": {"frozen": {"synthetic_frozen": True}}}
        self.entry = {"attempt": self.context.attempt_id, "previous": self.previous, "target": self.desired}
        self.deployment = SimpleNamespace(
            values={"CABINET_ALPHA_DEV_ENABLED": "true", "PUBLIC_CABINET_URL": smoke.ORIGIN},
            catalog_enabled=True, catalog_mode="remote", catalog_state=self.state / "catalog", servicing=None, documents=None,
            alpha_dev=SimpleNamespace(state=lambda: alpha, generation=alpha["generation"], read_enabled=False),
            runtime_modes={name: "remote" for name in smoke.RUNTIMES}, dev_payment_reserve_simulation=True, compose=Mock())
        self.stack.enter_context(patch.object(self.controller, "Deployment", return_value=self.deployment))
        self.stack.enter_context(patch.object(self.controller, "read_credentials", return_value={}))
        self.stack.enter_context(patch.object(self.controller, "provision"))
        self.stack.enter_context(patch.object(self.controller, "verify", return_value=[]))
        self.stack.enter_context(patch.object(self.controller, "run", return_value=json.dumps({"passed": True})))
        self.stack.enter_context(patch.object(self.controller, "ensure_tls"))
        self.stack.enter_context(patch.object(self.controller, "read_record", return_value={"caught_up": True, "manifest_sha256": self.release.checksum}))
        attempts = self.controller.runtime_attempts
        self.stack.enter_context(patch.object(attempts, "enrollment", return_value=self.entry))
        self.stack.enter_context(patch.object(attempts, "prebackup_replacement_env", side_effect=lambda *args: args[-1]))
        self.stack.enter_context(patch.object(attempts, "enrollment_backup", return_value=None))
        self.stack.enter_context(patch.object(attempts, "begin", return_value=self.attempt))
        self.stack.enter_context(patch.object(attempts, "load", return_value=self.attempt))
        self.closed = self.stack.enter_context(patch.object(attempts, "close_enrollment"))
        self.verified = self.stack.enter_context(patch.object(smoke, "verified_context", return_value=self.context))

    def deploy(self):
        with redirect_stdout(StringIO()) as output:
            self.controller.deploy(SimpleNamespace(verify_only=False, deployment_fd=123, host_memory_gib=8), self.state, self.release)
        return json.loads(output.getvalue())

    def test_first_business_pass_then_alpha_subprocess_failure_then_read_only_resume_and_commit(self):
        flow = Mock(); credentials = {"AGENT": {}, "UNDERWRITER": {}}
        products = [{"code": "WORKING_CAPITAL"}, {"code": "OVERDRAFT", "revolving": True,
                     "minAmount": 100000, "maxAmount": 500000, "minTermMonths": 1, "maxTermMonths": 24}]
        flow.call.side_effect = [{"demoAccounts": False}, products]
        def make_flow(_api, ledger, _credentials):
            flow.mandate.side_effect = lambda: completed_fixture(ledger, status="INCOMPLETE")
            flow.cleanup.return_value = True
            return flow
        import runtime_backup
        failure = subprocess.CalledProcessError(1, ["private-do-not-display"], output="private-output")
        fresh = {"caught_up": True, "credit_status_source": "sql", "checkpoint": {"generation": "g8b1a02f4d62af8c0"}}
        with (patch.object(smoke, "jury_credentials", return_value=credentials) as jury,
              patch.object(smoke, "API") as api,
              patch.object(smoke, "Smoke", side_effect=make_flow) as construct,
              patch.object(runtime_backup, "verify_alpha_catchup", side_effect=[failure, fresh]) as catchup):
            with self.assertRaises(subprocess.CalledProcessError): self.deploy()
            self.assertEqual(json.loads((self.state / "current.json").read_text()), self.previous)
            self.assertEqual(json.loads((self.state / "pending.json").read_text()), self.desired)
            ledger, = (self.state / "business-smoke").glob("*.json")
            saved = ledger.read_bytes()
            self.assertEqual(json.loads(saved)["status"], "PASS")
            self.deployment.compose.assert_called_once_with("stop", "caddy")
            report = self.deploy()
        self.assertEqual(api.call_count, 1); self.assertEqual(jury.call_count, 1); self.assertEqual(construct.call_count, 1)
        self.assertEqual(flow.signin.call_count, 2)
        for method in (flow.financing, flow.private_statement, flow.reserve, flow.mandate, flow.cleanup): method.assert_called_once()
        self.assertEqual(flow.register.call_count, 2); self.assertEqual(flow.grant.call_count, 2)
        self.assertEqual(catchup.call_count, 2); self.assertEqual(self.verified.call_count, 2)
        proof = report["release"]["business_smoke"]
        self.assertTrue(proof["reused_completed"]); self.assertTrue(proof["alpha_catchup_verified"])
        self.assertEqual(proof["ledger_sha256"], hashlib.sha256(saved).hexdigest())
        self.assertEqual(ledger.read_bytes(), saved)
        self.assertEqual(report["alpha_continuity"], {**fresh, "manifest_sha256": self.release.checksum})
        self.assertEqual(json.loads((self.state / "current.json").read_text()), self.desired)
        self.assertFalse((self.state / "pending.json").exists()); self.closed.assert_called_once()

    def test_existing_completed_pass_then_timeout_closes_caddy_without_new_business_and_keeps_pending(self):
        ledger = completed_fixture(smoke.Ledger(self.context)); saved = ledger.path.read_bytes()
        import runtime_backup
        with (patch.object(smoke, "jury_credentials", side_effect=AssertionError("replay auth forbidden")),
              patch.object(smoke, "API", side_effect=AssertionError("replay API forbidden")),
              patch.object(runtime_backup, "verify_alpha_catchup", side_effect=subprocess.TimeoutExpired(["private-command"], 3)),
              self.assertRaises(subprocess.TimeoutExpired)):
            self.deploy()
        self.deployment.compose.assert_called_once_with("stop", "caddy")
        self.assertEqual(json.loads((self.state / "current.json").read_text()), self.previous)
        self.assertEqual(json.loads((self.state / "pending.json").read_text()), self.desired)
        self.assertEqual(ledger.path.read_bytes(), saved); self.closed.assert_not_called()

    def test_main_sanitizes_subprocess_failure_without_echoing_command_or_outputs(self):
        release_dir = self.state / self.release.revision; release_dir.mkdir()
        manifest = release_dir / "release.json"; manifest.write_bytes(self.release.path.read_bytes())
        release = RegistryRelease(manifest)
        for error in (subprocess.CalledProcessError(1, ["PRIVATE-CREDENTIAL"], output="PRIVATE-STDOUT", stderr="PRIVATE-STDERR"),
                      subprocess.TimeoutExpired(["PRIVATE-CREDENTIAL"], 3, output="PRIVATE-STDOUT", stderr="PRIVATE-STDERR")):
            with (self.subTest(error=type(error).__name__),
                  patch.object(self.controller, "ROOT", release_dir),
                  patch.object(self.controller, "read_request", return_value=None),
                  patch.object(self.controller, "RegistryRelease", return_value=release),
                  patch.object(self.controller, "deploy_lock", return_value=nullcontext(123)),
                  patch.object(self.controller, "deploy", side_effect=error),
                  patch.object(sys, "argv", ["dev-release.py", "--state-dir", str(self.state), "--release-manifest", str(manifest)]),
                  redirect_stderr(StringIO()) as output,
                  self.assertRaises(SystemExit) as raised):
                self.controller.main()
            self.assertEqual(raised.exception.code, 1)
            self.assertIn(type(error).__name__, output.getvalue())
            self.assertNotIn("PRIVATE", output.getvalue())


if __name__ == "__main__":
    unittest.main()
