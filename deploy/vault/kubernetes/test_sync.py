"""Synthetic TLS/API checks. Run with python3 -m unittest discover -s deploy/vault/kubernetes."""

import base64
import copy
import json
import os
from pathlib import Path
import ssl
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import sync


SECRET = "cabinet-s3-app"
SECOND = "cabinet-s3-admin"
FIXTURE_UID = "synthetic-secret-uid"
KUBE_READER = "test-kube-reader"
KUBE_WRITER = "test-kube-writer"
VAULT_READER = "test-vault-reader"
VAULT_SEEDER = "test-vault-seeder"


def fixture(name, value="synthetic-original"):
    data = {key: base64.b64encode(value.encode()).decode() for key in sync.SECRET_KEYS[name]}
    return {
        "kind": "Secret", "type": sync.SECRET_TYPES[name], "data": data,
        "metadata": {
            "uid": FIXTURE_UID, "resourceVersion": "100",
            "name": name, "namespace": sync.NAMESPACE,
        },
    }


class SyntheticApi(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def error_response(self, status):
        self.reply(status, {"message": "synthetic error body must never be logged"})

    def handle_api(self):
        state = self.server.state
        route, _, query = self.path.partition("?")
        state["calls"].append((self.command, route, query))
        if state["redirect"]:
            self.send_response(307)
            self.send_header("Location", "/unexpected-target")
            self.end_headers()
            return

        if route.startswith(sync.VAULT_PATH):
            name = route.removeprefix(sync.VAULT_PATH)
            token = self.headers.get("X-Vault-Token")
            if token not in (VAULT_READER, VAULT_SEEDER) or name not in state["kube"]:
                self.error_response(403)
                return
            if self.command == "POST":
                if token != VAULT_SEEDER:
                    self.error_response(403)
                    return
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                payload = json.loads(raw)
                if payload.get("options") != {"cas": 0} or name in state["vault"]:
                    self.error_response(400)
                    return
                state["vault"][name] = payload["data"]
                self.reply(200, {"data": {"version": 1}})
                return
            if name not in state["vault"] or query not in ("", "version=1"):
                self.error_response(404)
                return
            self.reply(200, {"data": {
                "data": state["vault"][name],
                "metadata": {"version": 1, "destroyed": False, "deletion_time": ""},
            }})
            return

        token = self.headers.get("Authorization")
        if token not in (f"Bearer {KUBE_WRITER}", f"Bearer {KUBE_READER}"):
            self.error_response(403)
            return
        if route == sync.KUBE_NAMESPACE_PATH and self.command == "GET":
            self.reply(200, {"kind": "Namespace", "metadata": {
                "name": sync.NAMESPACE, "uid": state["namespace_uid"],
            }})
            return
        name = route.removeprefix(sync.KUBE_SECRET_PATH)
        if not route.startswith(sync.KUBE_SECRET_PATH) or name not in state["kube"]:
            self.error_response(403)
            return
        current = state["kube"][name]
        if self.command == "GET":
            self.reply(200, current)
            return
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        patch = json.loads(raw)
        if self.command != "PATCH" or token != f"Bearer {KUBE_WRITER}":
            self.error_response(403)
            return
        if self.headers.get("Content-Type") != "application/json-patch+json":
            self.error_response(415)
            return
        expected = [
            {"op": "test", "path": "/metadata/uid", "value": current["metadata"]["uid"]},
            {"op": "test", "path": "/metadata/resourceVersion", "value": current["metadata"]["resourceVersion"]},
            {"op": "test", "path": "/type", "value": current["type"]},
        ]
        if state["conflict"] and not query or patch[:3] != expected or len(patch) != 4:
            self.error_response(409)
            return
        if patch[3].get("op") != "replace" or patch[3].get("path") != "/data":
            self.error_response(422)
            return

        updated = copy.deepcopy(current)
        updated["data"] = patch[3]["value"]
        if query == "dryRun=All":
            self.reply(200, updated)
            return
        updated["metadata"]["resourceVersion"] = str(int(updated["metadata"]["resourceVersion"]) + 1)
        state["kube"][name] = updated
        if state["response_failure_after_commit"]:
            self.error_response(500)
        else:
            self.reply(200, updated)

    do_GET = do_POST = do_PATCH = handle_api


class SyncTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.ca = cls.root / "api.crt"
        tls_key = cls.root / "api.key"
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
             "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
             "-days", "1", "-keyout", str(tls_key), "-out", str(cls.ca)],
            check=True, capture_output=True,
        )
        for token in (KUBE_READER, KUBE_WRITER, VAULT_READER, VAULT_SEEDER):
            token_path = cls.root / token
            token_path.touch(mode=0o600)
            token_path.write_text(token + "\n", encoding="ascii")
        cls.api = ThreadingHTTPServer(("127.0.0.1", 0), SyntheticApi)
        cls.addClassCleanup(cls.api.server_close)
        server_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server_context.load_cert_chain(cls.ca, tls_key)
        cls.api.socket = server_context.wrap_socket(cls.api.socket, server_side=True)
        thread = threading.Thread(target=cls.api.serve_forever, daemon=True)
        thread.start()
        cls.addClassCleanup(cls.api.shutdown)
        cls.address = f"https://localhost:{cls.api.server_port}"

    def setUp(self):
        self.api.state = {
            "calls": [],
            "kube": {name: fixture(name) for name in (SECRET, SECOND)},
            "vault": {name: fixture(name, "synthetic-replacement")["data"] for name in (SECRET, SECOND)},
            "namespace_uid": sync.NAMESPACE_UID,
            "conflict": False,
            "response_failure_after_commit": False,
            "redirect": False,
        }
        self.journal = self.root / "change.json"
        self.journal.unlink(missing_ok=True)

    def cli(self, action, *extra, kube=KUBE_WRITER, vault=VAULT_READER, address=None):
        base = [sys.executable, sync.__file__, action, "--secret", SECRET,
                "--kube-address", address or self.address, "--kube-ca", str(self.ca),
                "--kube-token-file", str(self.root / kube)]
        if action != "rollback":
            base.extend(["--vault-address", self.address, "--vault-ca", str(self.ca),
                         "--vault-token-file", str(self.root / vault)])
        result = subprocess.run([*base, *extra], capture_output=True, text=True, check=False, timeout=12)
        for forbidden in ("synthetic-original", "synthetic-replacement", "synthetic error body", "Traceback"):
            self.assertNotIn(forbidden, result.stdout + result.stderr)
        return result

    def writes(self, service=None):
        calls = self.api.state["calls"]
        return [call for call in calls if call[0] in ("PATCH", "POST") and (service is None or call[0] == service)]

    def test_materialize_and_rollback_single_secret(self):
        state = self.api.state
        original = copy.deepcopy(state["kube"][SECRET]["data"])
        check = self.cli("verify", kube=KUBE_READER)
        self.assertEqual(check.returncode, 1)
        self.assertIn("data drift", check.stderr)
        self.assertEqual(self.writes(), [])

        apply = self.cli("apply", "--vault-version", "1", "--journal", str(self.journal))
        self.assertEqual(apply.returncode, 0, apply.stderr)
        self.assertEqual(state["kube"][SECRET]["data"], state["vault"][SECRET])
        self.assertEqual(os.stat(self.journal).st_mode & 0o777, 0o600)
        self.assertEqual([query for _, _, query in self.writes("PATCH")], ["dryRun=All", ""])
        check = self.cli("verify", kube=KUBE_READER)
        self.assertEqual(check.returncode, 0, check.stderr)

        for _ in range(2):
            undo = self.cli("rollback", "--journal", str(self.journal))
            self.assertEqual(undo.returncode, 0, undo.stderr)
            self.assertEqual(state["kube"][SECRET]["data"], original)
        self.assertEqual([query for _, _, query in self.writes("PATCH")], ["dryRun=All", "", "dryRun=All", ""])

    def test_preflight_validates_entire_selection_and_prevents_bulk_apply(self):
        self.api.state["vault"][SECOND]["unexpected"] = "d3Jvbmc="
        check = self.cli("stage", "--secret", SECOND)
        self.assertEqual(check.returncode, 1)
        self.assertIn("key set", check.stderr)
        self.assertEqual(self.writes(), [])

        rejection = self.cli("apply", "--secret", SECOND, "--vault-version", "1", "--journal", str(self.journal))
        self.assertEqual(rejection.returncode, 2)
        self.assertEqual(self.writes(), [])
        self.assertFalse(self.journal.exists())

    def test_namespace_identity_tls_redirect_and_read_only_rbac(self):
        state = self.api.state
        state["namespace_uid"] = "other-namespace-uid"
        mismatch = self.cli("stage")
        self.assertIn("namespace identity", mismatch.stderr)
        self.assertEqual(state["calls"], [("GET", sync.KUBE_NAMESPACE_PATH, "")])

        state["namespace_uid"] = sync.NAMESPACE_UID
        state["calls"].clear()
        invalid_tls = self.cli("verify", address=self.address.replace("localhost", "127.0.0.1"))
        self.assertIn("TLS or transport failure", invalid_tls.stderr)
        self.assertEqual(state["calls"], [])
        self.assertIn("expected HTTPS origin", self.cli("verify", address="http://localhost").stderr)

        state["redirect"] = True
        aborted = self.cli("verify")
        self.assertIn("HTTP 307", aborted.stderr)
        self.assertEqual(state["calls"], [("GET", sync.KUBE_NAMESPACE_PATH, "")])

        state["redirect"] = False
        forbidden = self.cli("stage", kube=KUBE_READER)
        self.assertIn("HTTP 403", forbidden.stderr)
        self.assertEqual([query for _, _, query in self.writes("PATCH")], ["dryRun=All"])
        for path in (sync.KUBE_SECRET_PATH, sync.KUBE_SECRET_PATH + "cabinet-prod-tls"):
            kube = sync.HttpsApi("Kubernetes", self.address, str(self.ca), "Authorization", f"Bearer {KUBE_WRITER}")
            with self.assertRaisesRegex(sync.HttpError, "HTTP 403"):
                kube.request("GET", path, "outside allowlist")

    def test_seed_create_only_and_no_patch(self):
        state = self.api.state
        state["vault"].clear()
        for _ in range(2):
            seeded = self.cli("seed", kube=KUBE_READER, vault=VAULT_SEEDER)
            self.assertEqual(seeded.returncode, 0, seeded.stderr)
            self.assertEqual(state["vault"][SECRET], state["kube"][SECRET]["data"])
        self.assertEqual(self.writes("POST"), [("POST", sync.VAULT_PATH + SECRET, "")])
        self.assertEqual(self.writes("PATCH"), [])

        state["vault"][SECRET] = fixture(SECRET, "synthetic-replacement")["data"]
        failed = self.cli("seed", kube=KUBE_READER, vault=VAULT_SEEDER)
        self.assertIn("refusing to overwrite", failed.stderr)
        self.assertEqual(len(self.writes()), 1)

    def test_conflict_blocks_and_unknown_response_checks_state_without_retry(self):
        state = self.api.state
        state["conflict"] = True
        extra = ["--vault-version", "1", "--journal", str(self.journal)]
        blocked = self.cli("apply", *extra)
        self.assertEqual(blocked.returncode, 1)
        self.assertIn("HTTP 409", blocked.stderr)
        self.assertTrue(self.journal.exists())
        self.assertEqual(state["kube"][SECRET]["data"], fixture(SECRET)["data"])

        self.journal.unlink()
        state["calls"].clear()
        state["conflict"] = False
        state["response_failure_after_commit"] = True
        applied = self.cli("apply", *extra)
        self.assertEqual(applied.returncode, 0, applied.stderr)
        self.assertEqual(state["kube"][SECRET]["data"], state["vault"][SECRET])
        self.assertEqual([query for _, _, query in self.writes("PATCH")], ["dryRun=All", ""])

        state["kube"][SECRET]["data"] = fixture(SECRET, "third-party-data")["data"]
        refused = self.cli("rollback", "--journal", str(self.journal))
        self.assertIn("refusing rollback", refused.stderr)
        self.assertEqual([query for _, _, query in self.writes("PATCH")], ["dryRun=All", ""])

    def test_token_and_value_validation(self):
        exposed = self.root / "exposed"
        exposed.touch(mode=0o644)
        exposed.write_text(KUBE_READER)
        rejected = self.cli("verify", kube=exposed.name)
        self.assertIn("owner-only", rejected.stderr)
        self.assertEqual(self.api.state["calls"], [])

        app_data = {key: "eA==" for key in sync.SECRET_KEYS["cabinet-secrets"]}
        with self.assertRaisesRegex(sync.SyncError, "key set"):
            sync.validate_data("cabinet-secrets", {**app_data, "S3_ACCESS_KEY": "eA=="})
        sync.validate_data("cabinet-secrets", {**app_data, "S3_ACCESS_KEY": "eA==", "S3_SECRET_KEY": "eA=="})
        with self.assertRaisesRegex(sync.SyncError, "canonical base64"):
            sync.validate_data(SECRET, {key: "eB==" for key in sync.SECRET_KEYS[SECRET]})


if __name__ == "__main__":
    unittest.main()
