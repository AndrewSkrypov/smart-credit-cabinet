"""Offline controller, fixture, and protocol acceptance contracts."""
from email.message import Message
from hashlib import sha256
import json
from pathlib import Path
import ssl
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
from urllib.parse import urlencode, urlsplit
from uuid import uuid4

from business_smoke import Context, Ledger, SmokeError, allowed_route
import mcp_business_smoke as m


def packed(value):
    return json.dumps(value).encode()


def headers(**mapping):
    result = Message()
    for name, value in mapping.items():
        result[name.replace("_", "-")] = value
    return result


class FakeCredit:
    """Stateful, in-memory Web/Identity/Credit/MCP model; no socket or database."""
    def __init__(self):
        self.requests = []
        self.users = {}
        self.apps = {}
        self.mandates = {}
        self.browser = "eyJhbGciOiJIUzI1NiJ9.payload.signature"
        self.bank = "eyJhbGciOiJIUzI1NiJ9.other.signature"
        self.flow, self.csrf, self.code, self.access = "f" * 43, "c" * 43, "a" * 43, "mcp_at_" + "t" * 43
        self.query = None
        self.redeemed = self.revoked = False
        self.break_grant = self.ambiguous_create = self.leak_statement = self.preview_mutation = False
        self.web_preview_missing = self.web_preview_mismatch = self.web_preview_mutation = False
        self.web_previews = []
        self.jury_agent = str(uuid4())
        self.foreign = None
        self.time = 0
        self.before_mutation = lambda: None

    def item(self, app):
        row = self.apps[app]
        return {"applicationId": app, "number": None, "companyName": "Synthetic",
                "status": row["status"], "actionOwner": "AGENT", "actionLabel": "Next",
                "actionDeadline": None, "cabinetPath": f"/agent/applications/{app}"}

    def exchange(self, method, path, **kwargs):
        self.requests.append((method, path))
        reply = lambda code, body, **values: (code, headers(**values), packed(body))
        if path == m.DISCOVERY:
            return reply(200, {"issuer": m.ORIGIN, "authorization_endpoint": m.ORIGIN + m.AUTHORIZE,
                               "token_endpoint": m.ORIGIN + m.TOKEN, "revocation_endpoint": m.ORIGIN + m.REVOKE,
                               "code_challenge_methods_supported": ["S256"], "scopes_supported": [m.SCOPE]})
        if path == m.METADATA:
            return reply(200, {"resource": m.RESOURCE, "authorization_servers": [m.ORIGIN], "scopes_supported": [m.SCOPE]})
        if path == m.AUTHORIZE and method == "GET":
            self.before_mutation()
            query = kwargs["query"]
            if query["redirect_uri"] != m.REDIRECT:
                return reply(400, {"error": "invalid_request"})
            assert query["client_id"] == m.CLIENT and query["scope"] == m.SCOPE and query["resource"] == m.RESOURCE
            assert query["code_challenge_method"] == "S256" and query["response_type"] == "code"
            self.query = query
            cookie = f"{m.FLOW_COOKIE}={self.flow}; Path=/; Max-Age=600; Secure; HttpOnly; SameSite=Lax"
            html = (f'<input type="hidden" name="csrf" value="{self.csrf}">' * 2).encode()
            return 200, headers(Set_Cookie=cookie), html
        if path == m.AUTHORIZE:
            self.before_mutation()
            assert kwargs["cookie"] == self.flow and kwargs["form"]["csrf"] == self.csrf
            assert kwargs["form"]["decision"] == "approve" and kwargs["form"]["email"] == "agent@jury.test"
            location = m.REDIRECT + "?" + urlencode({"code": self.code, "state": self.query["state"], "iss": m.ORIGIN})
            return 303, headers(Location=location), b""
        if path == m.TOKEN:
            self.before_mutation()
            form = kwargs["form"]
            assert form["code"] == self.code and form["client_id"] == m.CLIENT and form["redirect_uri"] == m.REDIRECT
            assert form["grant_type"] == "authorization_code"
            if form["resource"] != m.RESOURCE:
                return reply(400, {"error": "invalid_target"})
            challenge = m.urlsafe_b64encode(sha256(form["code_verifier"].encode()).digest()).decode().rstrip("=")
            if self.redeemed or challenge != self.query["code_challenge"]:
                return reply(400, {"error": "invalid_grant"})
            self.redeemed = True
            return reply(200, {"access_token": self.access, "scope": "bad" if self.break_grant else m.SCOPE,
                               "expires_in": 900, "token_type": "Bearer"})
        if path == m.REVOKE:
            self.before_mutation()
            assert self.redeemed and not self.revoked and kwargs["form"] == {"client_id": m.CLIENT, "token": self.access}
            self.revoked = True
            return 200, headers(), b""
        assert path == m.RESOURCE_PATH
        if kwargs["token"] != self.access or self.revoked:
            return reply(401, {"error": "invalid_token"},
                         WWW_Authenticate=f'Bearer resource_metadata="{m.ORIGIN}{m.METADATA}"')
        msg = kwargs["message"]
        self.time += 1
        envelope = lambda body: reply(200, {"jsonrpc": "2.0", "id": msg["id"], "result": body})
        if msg["method"] == "initialize":
            return envelope({"protocolVersion": m.PROTOCOL, "serverInfo": {"name": "pump-cabinet-mcp"}})
        if msg["method"] == "tools/list":
            return envelope({"tools": [{"name": name, "annotations": {"readOnlyHint": True}} for name in m.TOOLS]})
        tool, args = msg["params"]["name"], msg["params"]["arguments"]
        app = args.get("applicationId")
        body = {"source": "credit", "asOf": f"2026-11-28T11:00:{self.time:02d}Z"}
        if tool == m.TOOLS[0]:
            body["items"] = [self.item(app) for app, row in self.apps.items() if row["alias"] in self.active_aliases()]
        elif self.apps[app]["alias"] not in self.active_aliases():
            return envelope({"isError": True, "content": [{"type": "text", "text": json.dumps(m.NOT_FOUND)}]})
        else:
            row = self.apps[app]
            scn = row.get("scenario")
            if tool == m.TOOLS[1]:
                expectation = "SATISFIED" if row.get("file") else "OPEN" if row["status"] == "INFO_REQUESTED" else "NOT_REQUESTED"
                body.update(application=self.item(app), applicationVersion=str(row["version"]),
                            savedScenarioRevision=None if not scn else str(scn["revision"]),
                            statementRequirement=expectation)
                if self.leak_statement and expectation == "SATISFIED":
                    body["application"]["actionLabel"] = "private-smoke.pdf"
            else:
                assert tool == m.TOOLS[2] and args["alternativeTermMonths"] == 24
                days = args["delayDays"]
                body = self.preview_result(app, days)
                if self.preview_mutation and days == 6:
                    row["events"].append({"event": "UNEXPECTED_WRITE"})
        return envelope({"structuredContent": body, "content": [{"type": "text", "text": json.dumps(body)}]})

    def preview_result(self, app, days):
        row = self.apps[app]
        saved = row["scenario"]
        return {"source": "credit", "asOf": f"2026-11-28T11:00:{self.time:02d}Z", "preview": True,
            "basis": {"applicationVersion": str(row["version"]), "productCode": "WORKING_CAPITAL",
                      "productVersion": "7", "savedScenarioRevision": str(saved["revision"])},
            "inputsUsed": {"currency": "RUB", "paymentModel": "ANNUITY", "loanAmountRub": "2000000", "annualRatePercent": "20",
                "baseTermMonths": 12, "alternativeTermMonths": 24, "openingBalanceRub": "150000",
                "expectedReceiptAmountRub": "140000", "expectedReceiptDate": saved["inputs"]["expectedReceiptDate"],
                "paymentDate": saved["inputs"]["paymentDate"], "delayDays": days, "receiptIncludedByPayment": days == 5,
                "lastIncludedDelayDays": 5},
            "options": [{"termMonths": option["termMonths"],
                         **{key + "Rub": str(value) for key, value in option.items() if key != "termMonths"}}
                        for option in self.web_options(days)],
            "cabinetPath": f"/agent/applications/{app}#draft-financing"}

    def active_aliases(self):
        return {row["alias"] for row in self.mandates.values() if row["status"] == "ACTIVE"}

    @staticmethod
    def web_options(days):
        amounts = [(12, 185269.01, 223228.15), (24, 101791.61, 442998.50)]
        return [{"termMonths": term, "monthlyPayment": payment, "totalInterest": interest,
                 "balanceOnPaymentDate": round(150000 + (140000 if days == 5 else 0) - payment, 2)}
                for term, payment, interest in amounts]

    def request(self, method, path, token=None, data=None, file=None, key=None):
        self.requests.append((method, path))
        if method != "GET":
            self.before_mutation()
        req = urlsplit(path)
        path = req.path
        reply = lambda body, status=200: (status, packed(body))
        if path.endswith("/auth/sign-in"):
            role = data["email"].split("@")[0].upper()
            return reply({"token": self.browser if role == "AGENT" else self.bank,
                          "user": {"id": self.jury_agent if role == "AGENT" else str(uuid4()), "role": role}})
        if path.endswith("/auth/sign-up"):
            alias = data["email"].split("@")[0][-1]
            self.users[alias] = {"id": str(uuid4()), "role": "CLIENT", "companyId": str(uuid4()),
                                 "canSign": True, "inn": data["inn"]}
            return reply({"token": alias, "user": self.users[alias]})
        if path == "/api/v1/companies/me":
            return reply({"id": self.users[token]["companyId"], "inn": self.users[token]["inn"]})
        if path == m.MANDATES:
            if method == "GET":
                return reply([row for row in self.mandates.values() if row["alias"] == token])
            ident = str(uuid4())
            row = {"id": ident, "agentUserId": data["agentUserId"], "status": "ACTIVE", "alias": token}
            self.mandates[ident] = row
            return reply(row, 201)
        if path.startswith(m.MANDATES + "/"):
            row = self.mandates[path.rsplit("/", 1)[1]]
            assert row["alias"] == token and row["status"] == "ACTIVE"
            row["status"] = "REVOKED"
            return 204, b""
        if path == "/api/v1/agent/applications":
            assert token == self.browser and data["productCode"] == "WORKING_CAPITAL"
            if self.ambiguous_create:
                raise SmokeError("synthetic_create_timeout")
            alias = next(k for k, v in self.users.items() if v["companyId"] == data["companyId"])
            assert alias in self.active_aliases()
            app = str(uuid4())
            self.apps[app] = {"id": app, "company": {"id": data["companyId"]}, "alias": alias, "status": "DRAFT",
                              "version": 0, "product": {"code": "WORKING_CAPITAL", "version": 7, "revolving": False},
                              "events": [], "docs": [], "requiredDocuments": [{"type": "OTHER", "uploaded": False}]}
            if alias == "B":
                self.foreign = app
            return reply(self.apps[app], 201)
        parts = path.split("/")
        at = parts.index("applications")
        app = parts[at + 1]
        row = self.apps[app]
        alias = row["alias"]
        suffix = "/".join(parts[at + 2:])
        is_agent = "/agent/" in path
        if is_agent and (token != self.browser or alias not in self.active_aliases()):
            return reply({}, 404)
        if not is_agent and token == alias and row["status"] == "DRAFT":
            return reply({}, 404)
        if suffix == "financing-scenario/preview":
            assert method == "POST" and is_agent and token == self.browser
            days = data["delayDays"]
            assert data == {"mode": "WEB_FORM", "delayDays": days, "alternativeTermMonths": 24,
                            "openingBalance": 150000, "expectedReceiptAmount": 140000,
                            "expectedReceiptDate": row["scenario"]["inputs"]["expectedReceiptDate"],
                            "paymentDate": row["scenario"]["inputs"]["paymentDate"],
                            "draftTermsOverride": {"amount": 2000000, "baseTermMonths": 12}}
            self.web_previews.append((app, days))
            if self.web_preview_missing:
                return reply({}, 404)
            self.time += 1
            value = self.preview_result(app, days)
            if self.web_preview_mismatch:
                value["options"][0]["monthlyPaymentRub"] = "0.01"
            if self.web_preview_mutation:
                row["events"].append({"event": "UNEXPECTED_WEB_PREVIEW_WRITE"})
            return reply(value)
        if suffix == "financing-scenario":
            if method == "PUT":
                row["scenario"] = {"revision": row.get("scenario", {}).get("revision", 0) + 1, "status": "DRAFT",
                                   "inputs": data, "loanAmount": 2_000_000, "annualRatePercent": 20,
                                   "options": self.web_options(data["delayDays"])}
            return reply(row["scenario"])
        if suffix == "documents":
            if method == "GET":
                return reply(row["docs"])
            doc = str(uuid4())
            if "privateToBank=true" in req.query:
                assert token == alias and alias == "B"
                row["file"] = file[1]
                row["private_doc"] = doc
            else:
                row["docs"].append({"id": doc})
            return reply({"id": doc}, 201)
        if suffix.startswith("documents/"):
            assert suffix.endswith(row["private_doc"])
            return (200, row["file"]) if token == self.bank else reply({}, 404)
        if suffix == "events":
            return reply(row["events"])
        if suffix == "request-confirmation":
            row["status"] = "PENDING_CLIENT_CONFIRMATION"
            if "scenario" in row:
                row["scenario"]["status"] = "AWAITING_CLIENT"
        if suffix == "confirm":
            assert alias == token == "B"
            row["status"] = "MANUAL_REVIEW"
        if suffix == "request-info":
            assert token == self.bank and alias == "B"
            row["status"] = "INFO_REQUESTED"
        if suffix in {"withdraw", "cancel"}:
            assert (suffix, token) in {("withdraw", alias), ("cancel", self.browser)}
            row["status"] = "WITHDRAWN"
        return reply({key: value for key, value in row.items() if key != "file"})


class ControllerTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.state = Path(tmp.name).resolve()
        self.context = Context("1" * 40, "2" * 64, self.state, {"revision": "0" * 40},
                               {"revision": "1" * 40, "mcp": {"enabled": True}}, "3" * 32)
        self.creds = {role: {"email": role.lower() + "@jury.test", "password": "secret-" + role + "-do-not-record"}
                      for role in ("AGENT", "UNDERWRITER", "CLIENT")}
        self.binding = {"business_ledger_sha256": "4" * 64, "mcp_image_digest": "sha256:" + "5" * 64}
        self.api = FakeCredit()
        self.api.before_mutation = self.check_intent
        for name, value in {"verified_context": self.context, "controller_proof": self.binding,
                            "jury_credentials": self.creds, "SmokeAPI": self.api}.items():
            patcher = patch.object(m, name, return_value=value)
            setattr(self, name, patcher.start())
            self.addCleanup(patcher.stop)

    def run_probe(self):
        return m.run(object(), self.state, {}, {}, {}, lock_fd=5)

    def ledger(self):
        return next((self.state / m.NAMESPACE).glob("*.json"))

    def check_intent(self):
        path = self.ledger()
        data = json.loads(path.read_bytes())
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertTrue(data["pending"])

    def test_realistic_full_flow_and_exact_read_only_resume(self):
        result = self.run_probe()
        self.assertEqual(result["api_mcp_business_proof"], "PASS")
        self.assertEqual(result["checks"], list(m.CHECKS))
        self.assertEqual(result["retained"]["token"], {"revoked_count": 1, "http_after_revoke": 401})
        self.assertEqual({app["status"] for app in self.api.apps.values()}, {"WITHDRAWN"})
        self.assertEqual({mandate["status"] for mandate in self.api.mandates.values()}, {"REVOKED"})
        self.assertEqual([days for _, days in self.api.web_previews], [5, 6])
        before = self.ledger().read_bytes()
        self.assertNotIn(b"private-smoke.pdf", before)
        for secret in (self.api.access, self.api.code, self.api.csrf, self.creds["AGENT"]["password"], self.api.browser):
            self.assertNotIn(secret.encode(), before)
        count = len(self.api.requests)
        result = self.run_probe()
        self.assertIs(result["reused_completed"], True)
        self.assertEqual(self.SmokeAPI.call_count, 1)
        self.assertEqual(self.jury_credentials.call_count, 1)
        self.assertEqual(count, len(self.api.requests))
        self.assertEqual(before, self.ledger().read_bytes())

    def test_web_preview_404_mismatch_and_mutation_fail_with_token_revoked(self):
        for failure, code in (("web_preview_missing", "unexpected_http_status"),
                              ("web_preview_mismatch", "mcp_web_preview_parity_mismatch"),
                              ("web_preview_mutation", "mcp_preview_modified_saved_data")):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                self.state = Path(temp)
                self.verified_context.return_value = Context(self.context.revision, self.context.digest, self.state,
                    self.context.current, self.context.desired, self.context.attempt_id)
                self.api = FakeCredit()
                self.SmokeAPI.return_value = self.api
                self.api.before_mutation = self.check_intent
                setattr(self.api, failure, True)
                with self.assertRaisesRegex(SmokeError, code):
                    self.run_probe()
                self.assertTrue(self.api.web_previews)
                self.assertTrue(self.api.revoked)
                self.assertEqual(json.loads(self.ledger().read_bytes())["status"], "INCOMPLETE")
                count = len(self.api.requests)
                with self.assertRaises(SmokeError):
                    self.run_probe()
                self.assertEqual(len(self.api.requests), count)

    def test_private_leak_and_preview_write_fail_with_known_token_revoked(self):
        for failure, code in (("leak_statement", "mcp_private_statement_leak"),
                              ("preview_mutation", "mcp_preview_modified_saved_data")):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temp:
                self.state = Path(temp)
                ctx = Context(self.context.revision, self.context.digest, self.state, self.context.current,
                              self.context.desired, self.context.attempt_id)
                self.verified_context.return_value = ctx
                self.api = FakeCredit()
                self.SmokeAPI.return_value = self.api
                self.api.before_mutation = self.check_intent
                setattr(self.api, failure, True)
                with self.assertRaisesRegex(SmokeError, code):
                    self.run_probe()
                self.assertTrue(self.api.revoked)
                self.assertEqual(json.loads(self.ledger().read_bytes())["status"], "INCOMPLETE")

    def test_invalid_grant_still_revokes_recognizable_token_and_never_replays(self):
        self.api.break_grant = True
        with self.assertRaisesRegex(SmokeError, "mcp_grant_invalid"):
            self.run_probe()
        self.assertTrue(self.api.revoked)
        data = json.loads(self.ledger().read_bytes())
        self.assertEqual(data["status"], "INCOMPLETE")
        self.assertEqual(data["pending"][0]["owner"], "exchange")
        count = len(self.api.requests)
        with self.assertRaises(SmokeError):
            self.run_probe()
        self.assertEqual(count, len(self.api.requests))

    def test_ambiguous_fixture_create_blocks_cleanup_and_new_run(self):
        self.api.ambiguous_create = True
        with self.assertRaisesRegex(SmokeError, "synthetic_create_timeout"):
            self.run_probe()
        self.assertTrue(json.loads(self.ledger().read_bytes())["pending"])
        count = len(self.api.requests)
        with self.assertRaises(SmokeError):
            self.run_probe()
        self.assertEqual(count, len(self.api.requests))
        self.assertEqual(self.api.requests.count(("POST", "/api/v1/agent/applications")), 1)
        self.assertTrue(all(row["status"] == "ACTIVE" for row in self.api.mandates.values()))

    def test_valid_resume_requires_one_unchanged_context_and_token_proof(self):
        self.run_probe()
        with self.assertRaises(SmokeError):
            m.resume_completed(Context(self.context.revision, self.context.digest, self.state, self.context.current,
                                       self.context.desired, "0" * 32), self.binding)
        path = self.ledger()
        contents = json.loads(path.read_bytes())
        contents["cleanup"]["token"]["http_after_revoke"] = 200
        path.write_bytes(packed(contents))
        with self.assertRaisesRegex(SmokeError, "mcp_cleanup_unconfirmed"):
            self.run_probe()
        extra = path.with_name(path.stem + "-other.json")
        extra.touch(mode=0o600)
        with self.assertRaisesRegex(SmokeError, "mcp_multiple_run_records"):
            self.run_probe()

    def test_unowned_or_funding_operation_never_reaches_web_transport(self):
        ledger = Ledger(self.context, namespace=m.NAMESPACE)
        smoke = m.McpSmoke(self.api, ledger, self.creds)
        ident = str(uuid4())
        with self.assertRaisesRegex(SmokeError, "unowned_uuid_route"):
            smoke.call("AGENT", "GET", f"/api/v1/agent/applications/{ident}")
        ledger.own("applications", ident, "A")
        for suffix in ("confirm", "sign", "offer/accept"):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(SmokeError, "unexpected_fixture_mutation"):
                smoke.call("A", "POST", f"/api/v1/applications/{ident}/{suffix}")
        preview = f"/api/v1/agent/applications/{ident}/financing-scenario/preview"
        for role, method, route in (("A", "POST", preview), ("UNDERWRITER", "POST", preview),
                                    ("AGENT", "PUT", preview), ("AGENT", "POST", preview + "?mode=WEB_FORM")):
            with self.subTest(role=role, method=method), self.assertRaisesRegex(SmokeError, "unexpected_fixture_mutation"):
                smoke.call(role, method, route)
        self.assertEqual(self.api.requests, [])

    def test_namespace_does_not_overlap_web_ledger(self):
        self.assertEqual(Ledger(self.context).path.parent.name, "business-smoke")
        self.assertEqual(Ledger(self.context, namespace=m.NAMESPACE).path.parent.name, "mcp-smoke")
        with self.assertRaisesRegex(SmokeError, "invalid_ledger_namespace"):
            Ledger(self.context, namespace="../bad")


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.api = m.SmokeAPI()
        fake = patch.object(m.http.client, "HTTPSConnection")
        self.connect = fake.start()
        self.addCleanup(fake.stop)
        self.connection = self.connect.return_value

    def response(self, status, chunks=(), **mapping):
        response = Mock(status=status, headers=headers(**mapping))
        response.getheader.side_effect = response.headers.get
        response.read1.side_effect = [*chunks, b""]
        self.connection.getresponse.return_value = response
        return response

    def test_exact_tls_origin_cookie_and_form_only_to_canonical_host(self):
        self.response(303, Location=m.REDIRECT + "?test", Content_Length="0")
        status, _, _ = self.api.exchange("POST", m.AUTHORIZE, form={"csrf": "mock"}, cookie="f" * 43)
        self.assertEqual(status, 303)
        self.connect.assert_called_once_with(m.HOST, 443, timeout=15, context=self.api.tls)
        self.assertTrue(self.api.tls.check_hostname)
        self.assertEqual(self.api.tls.verify_mode, ssl.CERT_REQUIRED)
        request = self.connection.request.call_args
        self.assertEqual(request.args, ("POST", m.AUTHORIZE))
        self.assertEqual(request.kwargs["headers"]["Origin"], m.ORIGIN)
        self.assertEqual(request.kwargs["headers"]["Cookie"], m.FLOW_COOKIE + "=" + "f" * 43)
        self.assertEqual(request.kwargs["headers"]["Content-Type"], "application/x-www-form-urlencoded")
        self.assertEqual(request.kwargs["body"], b"csrf=mock")
        self.connection.close.assert_called_once()

    def test_foreign_url_wrong_method_and_insecure_tls_fail_before_connect(self):
        for method, path in (("GET", "https://other.invalid/api/mcp"), ("GET", m.RESOURCE_PATH),
                             ("POST", "/api/mcp/"), ("GET", m.METADATA + "?target=other")):
            with self.subTest(path=path), self.assertRaisesRegex(SmokeError, "mcp_unexpected_route"):
                self.api.exchange(method, path)
        self.api.tls.check_hostname = False
        self.api.tls.verify_mode = ssl.CERT_NONE
        with self.assertRaisesRegex(SmokeError, "mcp_tls_required"):
            self.api.exchange("GET", m.METADATA)
        self.connect.assert_not_called()

    def test_web_preview_transport_allows_only_exact_agent_post_without_query(self):
        own = str(uuid4())
        route = f"/api/v1/agent/applications/{own}/financing-scenario/preview"
        self.assertTrue(allowed_route("POST", route))
        for method, path in (("GET", route), ("PUT", route), ("DELETE", route),
                             ("POST", route + "?mode=WEB_FORM"),
                             ("POST", route.replace("/agent/", "/underwriting/")),
                             ("POST", route + "/"), ("POST", "https://unrelated.invalid" + route)):
            with self.subTest(method=method, path=path):
                self.assertFalse(allowed_route(method, path))

    def test_transport_never_follows_redirects_or_reads_unbounded_responses(self):
        for status, attributes, code in (
            (302, {"Location": "https://unrelated.invalid"}, "mcp_redirect_rejected"),
            (200, {"Content_Length": "262145"}, "mcp_response_length"),
            (200, {"Content_Encoding": "gzip"}, "mcp_response_encoding"),
        ):
            with self.subTest(code=code):
                response = self.response(status, **attributes)
                with self.assertRaisesRegex(SmokeError, code):
                    self.api.exchange("GET", m.METADATA)
                response.read1.assert_not_called()
        self.assertEqual(self.connect.call_count, 3)
        self.response(200, [b"x" * 8192] * 33)
        with self.assertRaisesRegex(SmokeError, "mcp_response_budget_exhausted"):
            self.api.exchange("GET", m.METADATA)

    def test_probe_budget_saves_cleanup_capacity_and_stops_at_hard_cap(self):
        self.api.requests = m.MAX_REQUESTS - 24
        with self.assertRaisesRegex(SmokeError, "mcp_cleanup_budget_reserved"):
            self.api.exchange("GET", m.METADATA)
        self.connect.assert_not_called()
        self.api.cleaning = True
        self.response(200, (b"{}",), Content_Length="2")
        self.assertEqual(self.api.exchange("GET", m.METADATA)[0], 200)
        self.api.requests = m.MAX_REQUESTS
        with self.assertRaisesRegex(SmokeError, "mcp_request_budget_exhausted"):
            self.api.exchange("GET", m.METADATA)
        self.connect.assert_called_once()

    def test_callback_state_issuer_and_exact_redirect(self):
        fields = {"code": "c" * 43, "state": "s" * 43, "iss": m.ORIGIN}
        valid = "?" + urlencode(fields)
        self.assertEqual(m.callback_code(m.REDIRECT + valid, fields["state"]), fields["code"])
        locations = [
            m.REDIRECT + "/" + valid,
            "https://127.0.0.1:38765/callback" + valid,
            "http://127.0.0.1:38766/callback" + valid,
            m.REDIRECT + valid + "&state=dup",
            m.REDIRECT + valid + "#fragment",
            m.REDIRECT + "?" + urlencode({**fields, "state": "unexpected"}),
            m.REDIRECT + "?" + urlencode({**fields, "iss": "https://unrelated.invalid"}),
        ]
        for index, target in enumerate(locations):
            with self.subTest(index=index), self.assertRaisesRegex(SmokeError, "mcp_callback_invalid"):
                m.callback_code(target, fields["state"])

    def test_consent_requires_host_cookie_flags_and_single_csrf(self):
        valid = f"{m.FLOW_COOKIE}={'f' * 43}; Path=/; Max-Age=600; HttpOnly; Secure; SameSite=Lax"
        csrf = b'name="csrf" value="' + b"c" * 43 + b'"'
        self.assertEqual(m.flow_secrets(headers(Set_Cookie=valid), csrf), ("f" * 43, "c" * 43))
        for bad in (valid.replace("; Secure", ""), valid + "; Domain=credit-cabinet.ru", valid.replace("Lax", "None")):
            with self.assertRaisesRegex(SmokeError, "mcp_flow_cookie_invalid"):
                m.flow_secrets(headers(Set_Cookie=bad), csrf)
        with self.assertRaisesRegex(SmokeError, "mcp_consent_csrf_unconfirmed"):
            m.flow_secrets(headers(Set_Cookie=valid), csrf + csrf.replace(b"c" * 43, b"d" * 43))


class ControllerGateTests(unittest.TestCase):
    def setUp(self):
        self.context = Context("1" * 40, "2" * 64, Path("/somewhere"), {}, {"mcp": {"enabled": True}}, "3" * 32)
        mcp_image = "registry.test/dev/mcp@sha256:" + "5" * 64
        credit_image = "registry.test/dev/credit@sha256:" + "6" * 64
        self.deployment = SimpleNamespace(mcp=True, values={
            "CABINET_DOMAIN": m.HOST, "CABINET_DEV_MCP_ENABLED": "true",
            "CABINET_MCP_OAUTH_CLIENT_ID": m.CLIENT, "CABINET_MCP_OAUTH_REDIRECT_URI": m.REDIRECT,
        }, release=SimpleNamespace(image_for={"mcp": mcp_image, "credit": credit_image}.__getitem__))
        self.business = {"reused_completed": True, "ledger_sha256": "7" * 64, "api_business_proof": "PASS"}
        self.report = {"release": {"mcp": {"resource": m.RESOURCE, "metadata_http": 200, "unauthenticated_http": 401, "private_http": 404},
            "https": {"origin": m.ORIGIN, "status": 200},
            "images": {service: {"registry_ref": ref, "revision": self.context.revision, "image_id": "sha256:" + "8" * 64}
                       for service, ref in (("cabinet-mcp", mcp_image), ("credit", credit_image))},
            "business_smoke": {**self.business, "reused_completed": False}}}

    def test_release_images_and_completed_business_bound(self):
        with patch.object(m, "resume_business", return_value=self.business) as resume:
            bound = m.controller_proof(self.deployment, self.context, self.report)
            self.assertEqual(bound, {"mcp_image_digest": "sha256:" + "5" * 64, "business_ledger_sha256": "7" * 64})
            resume.assert_called_once_with(self.context)
            self.report["release"]["images"]["cabinet-mcp"]["registry_ref"] += "f"
            with self.assertRaisesRegex(SmokeError, "mcp_release_image_unconfirmed"):
                m.controller_proof(self.deployment, self.context, self.report)

    def test_missing_business_and_noncanonical_redirect_are_denied(self):
        with patch.object(m, "resume_business", return_value=None):
            with self.assertRaisesRegex(SmokeError, "mcp_completed_business_required"):
                m.controller_proof(self.deployment, self.context, self.report)
            self.deployment.values["CABINET_MCP_OAUTH_REDIRECT_URI"] = m.REDIRECT + "/"
            with self.assertRaisesRegex(SmokeError, "mcp_dev_configuration_required"):
                m.controller_proof(self.deployment, self.context, self.report)

    def test_controller_denial_precedes_credentials_transport_and_ledger(self):
        with patch.object(m, "verified_context", side_effect=SmokeError("held_deployment_lock_required")) as context, \
             patch.object(m, "jury_credentials") as credentials, patch.object(m, "SmokeAPI") as transport, \
             patch.object(m, "Ledger") as ledger:
            with self.assertRaisesRegex(SmokeError, "held_deployment_lock_required"):
                m.run(self.deployment, Path("/unverified"), {}, {}, self.report, lock_fd=19)
            context.assert_called_once_with(self.deployment, Path("/unverified"), {}, {}, self.report, 19)
            for guard in (credentials, transport, ledger):
                guard.assert_not_called()


if __name__ == "__main__":
    unittest.main()
