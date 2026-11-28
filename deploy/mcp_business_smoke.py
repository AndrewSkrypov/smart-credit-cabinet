"""Controller-only, host-side OAuth MCP acceptance for the protected Dev release."""
from base64 import b64encode, urlsafe_b64encode
from contextlib import contextmanager
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_UP, localcontext
from hashlib import sha256
import http.client
from http.cookies import CookieError, SimpleCookie
import json
from pathlib import Path
import re
import secrets
import signal
import ssl
import time
from urllib.parse import parse_qs, urlencode, urlsplit

from business_smoke import (
    API, HOST, JURY_COMPANY, Ledger, MAX_REQUESTS, MAX_TOTAL_BYTES, ORIGIN,
    RUN_SECONDS, Smoke, SmokeError, context_digest, deadline, identifier,
    jury_credentials, need, pdf, private_dir, private_record,
    resume_completed as resume_business, verified_context,
)
from mcp_dev import (
    FLAG, MCP_METADATA as METADATA, MCP_RESOURCE as RESOURCE_PATH, OAUTH_METADATA as DISCOVERY, OAUTH_PATHS,
)

CLIENT = "alpha-hack-dev"
REDIRECT = "http://127.0.0.1:38765/callback"
RESOURCE = ORIGIN + RESOURCE_PATH
AUTHORIZE, TOKEN, REVOKE = OAUTH_PATHS
SCOPE = "cabinet:mcp:read"
PROTOCOL = "2025-11-25"
FLOW_COOKIE = "__Host-cabinet-mcp-oauth"
OPAQUE = re.compile(r"[A-Za-z0-9_-]{43}")
ACCESS_TOKEN = re.compile(r"mcp_at_[A-Za-z0-9_-]{43}")
SHA256 = re.compile(r"[a-f0-9]{64}")
NAMESPACE = "mcp-smoke"
TOOLS = ("list_agent_next_actions", "get_agent_application_summary", "preview_agent_financing_scenario")
CHECKS = (
    "canonical_oauth_pkce_and_single_use_code",
    "three_read_only_tools_real_credit_and_web_equivalence",
    "preview_leaves_application_scenario_documents_and_events_unchanged",
    "foreign_case_not_found_and_private_statement_hidden",
    "own_mandate_revoked_client_keeps_handed_packet",
)
AMOUNT, OPENING, RECEIPT = 2_000_000, 150_000, 140_000
BASE_TERM, ALT_TERM = 12, 24
MANDATES = "/api/v1/companies/me/agent-mandates"
CASE_FIELDS = {"applicationId", "number", "companyName", "status", "actionOwner", "actionLabel", "actionDeadline", "cabinetPath"}
SUMMARY_FIELDS = {"application", "applicationVersion", "savedScenarioRevision", "statementRequirement", "asOf", "source"}
NOT_FOUND = {"code": "CASE_NOT_FOUND", "message": "Заявка недоступна или не найдена."}


def digest(value):
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return sha256(raw.encode()).hexdigest()


def json_object(raw):
    def pairs(entries):
        values = {}
        for key, value in entries:
            need(key not in values, "mcp_duplicate_json_key")
            values[key] = value
        return values

    def invalid_constant(_):
        raise SmokeError("mcp_invalid_json")

    try:
        data = json.loads(raw, object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeError):
        raise SmokeError("mcp_invalid_json") from None
    need(isinstance(data, dict), "mcp_invalid_json_shape")
    return data


class SmokeAPI(API):
    """Share Web API TLS, clocks and budgets with the narrow OAuth/MCP transport."""
    def __init__(self):
        super().__init__()
        self.cleaning = False

    def check_probe_budget(self):
        need(self.cleaning or (time.monotonic() - self.started < RUN_SECONDS - 120
             and self.requests < MAX_REQUESTS - 24 and self.bytes < MAX_TOTAL_BYTES - 4 * 1024 * 1024),
             "mcp_cleanup_budget_reserved")

    def request(self, *args, **kwargs):
        self.check_probe_budget()
        return super().request(*args, **kwargs)

    def exchange(self, method, path, *, query=None, form=None, message=None, token=None, cookie=None):
        self.check_probe_budget()
        need(((method == "GET" and path in {DISCOVERY, METADATA, AUTHORIZE})
              or (method == "POST" and path in {AUTHORIZE, TOKEN, REVOKE, RESOURCE_PATH})),
             "mcp_unexpected_route")
        need((query is not None) == ((method, path) == ("GET", AUTHORIZE)), "mcp_unexpected_query")
        need((form is not None) == (method == "POST" and path != RESOURCE_PATH)
             and (message is not None) == (path == RESOURCE_PATH)
             and (cookie is not None) == ((method, path) == ("POST", AUTHORIZE))
             and (token is None or path == RESOURCE_PATH), "mcp_unexpected_request_shape")
        need(self.tls.check_hostname is True and self.tls.verify_mode == ssl.CERT_REQUIRED, "mcp_tls_required")
        remaining = RUN_SECONDS - (time.monotonic() - self.started)
        need(remaining > 0 and self.requests < MAX_REQUESTS, "mcp_request_budget_exhausted")
        self.requests += 1
        headers = {"Accept-Encoding": "identity", "Accept": "application/json"}
        body = None
        if form is not None:
            headers["Content-Type"] = "application/x-www-form-urlencoded"
            body = urlencode(form).encode("ascii")
        if message is not None:
            headers.update({"Accept": "application/json, text/event-stream", "Content-Type": "application/json",
                            "MCP-Protocol-Version": PROTOCOL, "Origin": ORIGIN})
            body = json.dumps(message, allow_nan=False).encode()
        if cookie is not None:
            need(isinstance(cookie, str) and OPAQUE.fullmatch(cookie), "mcp_flow_cookie_invalid")
            headers.update({"Cookie": FLOW_COOKIE + "=" + cookie, "Origin": ORIGIN})
        if token is not None:
            need(isinstance(token, str) and re.fullmatch(r"[A-Za-z0-9_.-]{1,8192}", token), "mcp_bearer_invalid")
            headers["Authorization"] = "Bearer " + token
        target = path if query is None else path + "?" + urlencode(query)
        need(len(target) <= 4096 and (body is None or len(body) <= 16 * 1024), "mcp_request_oversize")
        connection = http.client.HTTPSConnection(HOST, 443, timeout=min(15, remaining), context=self.tls)
        try:
            with deadline(min(25, remaining)):
                connection.request(method, target, body=body, headers=headers)
                response = connection.getresponse()
                need(not 300 <= response.status < 400 or (method, path, response.status) == ("POST", AUTHORIZE, 303),
                     "mcp_redirect_rejected")
                need(response.getheader("Content-Encoding", "identity").strip().lower() in {"", "identity"},
                     "mcp_response_encoding")
                declared = response.getheader("Content-Length")
                limit = 256 * 1024
                need(declared is None or re.fullmatch(r"[0-9]{1,9}", declared) and int(declared) <= limit,
                     "mcp_response_length")
                raw = bytearray()
                while True:
                    chunk = response.read1(8192)
                    if not chunk:
                        break
                    need(len(raw) + len(chunk) <= limit and self.bytes + len(chunk) <= MAX_TOTAL_BYTES,
                         "mcp_response_budget_exhausted")
                    raw.extend(chunk)
                    self.bytes += len(chunk)
                return response.status, response.headers, bytes(raw)
        except (OSError, ValueError, http.client.HTTPException):
            raise SmokeError("mcp_https_unconfirmed") from None
        finally:
            connection.close()


def header(headers, name):
    values = headers.get_all(name, [])
    need(len(values) == 1 and isinstance(values[0], str) and len(values[0]) <= 4096, "mcp_header_unconfirmed")
    return values[0]


def flow_secrets(headers, body):
    try:
        cookie = SimpleCookie(header(headers, "Set-Cookie"))
    except CookieError:
        raise SmokeError("mcp_flow_cookie_invalid") from None
    flow = cookie.get(FLOW_COOKIE)
    need(len(cookie) == 1 and flow is not None and OPAQUE.fullmatch(flow.value)
         and flow["secure"] and flow["httponly"] and flow["samesite"].lower() == "lax"
         and flow["path"] == "/" and not flow["domain"], "mcp_flow_cookie_invalid")
    csrf = set(re.findall(rb'name="csrf" value="([A-Za-z0-9_-]{43})"', body))
    need(len(csrf) == 1, "mcp_consent_csrf_unconfirmed")
    return flow.value, csrf.pop().decode("ascii")


def callback_code(location, state):
    try:
        target = urlsplit(location)
        query = parse_qs(target.query, keep_blank_values=True, strict_parsing=True, max_num_fields=3)
    except ValueError:
        raise SmokeError("mcp_callback_invalid") from None
    need(location.startswith(REDIRECT + "?") and target._replace(query="").geturl() == REDIRECT
         and not target.fragment and set(query) == {"code", "state", "iss"}
         and query["state"] == [state] and query["iss"] == [ORIGIN]
         and len(query["code"]) == 1 and OPAQUE.fullmatch(query["code"][0]), "mcp_callback_invalid")
    return query["code"][0]


@contextmanager
def oauth_intent(ledger, action):
    # Flow IDs, codes and bearer secrets never enter the journal.
    intent = ledger.beginning("oauth", action)
    yield
    ledger.data["pending"].remove(intent)
    ledger.save()


class OAuth:
    def __init__(self, api, ledger):
        self.api, self.ledger = api, ledger
        self.tokens = set()
        self.sequence = 0

    def identity(self):
        for path, expect in (
            (DISCOVERY, {"issuer": ORIGIN, "authorization_endpoint": ORIGIN + AUTHORIZE,
                         "token_endpoint": ORIGIN + TOKEN, "revocation_endpoint": ORIGIN + REVOKE,
                         "code_challenge_methods_supported": ["S256"], "scopes_supported": [SCOPE]}),
            (METADATA, {"resource": RESOURCE, "authorization_servers": [ORIGIN], "scopes_supported": [SCOPE]}),
        ):
            status, _, raw = self.api.exchange("GET", path)
            need(status == 200, "mcp_discovery_http")
            value = json_object(raw)
            need(all(value.get(k) == v for k, v in expect.items()), "mcp_discovery_identity")

    def rpc(self, method, params, token, status=200):
        self.sequence += 1
        message = {"jsonrpc": "2.0", "id": self.sequence, "method": method, "params": params}
        actual, headers, raw = self.api.exchange("POST", RESOURCE_PATH, message=message, token=token)
        need(actual == status, "mcp_unexpected_http_status")
        value = json_object(raw)
        if status == 401:
            need(value == {"error": "invalid_token"}
                 and f'resource_metadata="{ORIGIN}{METADATA}"' in header(headers, "WWW-Authenticate"),
                 "mcp_unauthorized_challenge_invalid")
            return None
        need(set(value) == {"jsonrpc", "id", "result"} and value["jsonrpc"] == "2.0"
             and type(value["id"]) is int and value["id"] == message["id"]
             and isinstance(value["result"], dict), "mcp_rpc_envelope_invalid")
        return value["result"]

    def grant(self, credentials, browser_jwt):
        self.identity()
        need(isinstance(browser_jwt, str) and browser_jwt.count(".") == 2, "mcp_real_browser_jwt_required")
        for token in (None, browser_jwt):
            self.rpc("tools/list", {}, token, 401)
        verifier = secrets.token_urlsafe(32)
        state = secrets.token_urlsafe(32)
        registration = {"client_id": CLIENT, "redirect_uri": REDIRECT}
        query = {**registration, "state": state, "resource": RESOURCE, "scope": SCOPE,
                 "response_type": "code", "code_challenge_method": "S256",
                 "code_challenge": urlsafe_b64encode(sha256(verifier.encode()).digest()).decode().rstrip("=")}
        with oauth_intent(self.ledger, "wrong_callback"):
            status, _, raw = self.api.exchange("GET", AUTHORIZE, query={**query, "redirect_uri": REDIRECT + "/"})
            need(status == 400 and json_object(raw) == {"error": "invalid_request"}, "mcp_wrong_callback_not_denied")
        with oauth_intent(self.ledger, "start"):
            status, headers, raw = self.api.exchange("GET", AUTHORIZE, query=query)
            need(status == 200, "mcp_consent_unconfirmed")
            flow, csrf = flow_secrets(headers, raw)
        with oauth_intent(self.ledger, "approve"):
            status, headers, _ = self.api.exchange(
                "POST", AUTHORIZE, form={**credentials, "csrf": csrf, "decision": "approve"}, cookie=flow,
            )
            need(status == 303, "mcp_consent_not_approved")
            code = callback_code(header(headers, "Location"), state)
        exchange = {**registration, "resource": RESOURCE, "code": code,
                    "code_verifier": verifier, "grant_type": "authorization_code"}
        self.exchange_code("wrong_resource", {**exchange, "resource": RESOURCE + "/"}, "invalid_target")
        self.exchange_code("wrong_verifier", {**exchange, "code_verifier": secrets.token_urlsafe(32)}, "invalid_grant")
        token = self.exchange_code("exchange", exchange)
        self.exchange_code("code_replay", exchange, "invalid_grant")
        return token

    def exchange_code(self, action, form, error=None):
        with oauth_intent(self.ledger, action):
            status, _, raw = self.api.exchange("POST", TOKEN, form=form)
            data = json_object(raw)
            token = data.get("access_token")
            if isinstance(token, str) and ACCESS_TOKEN.fullmatch(token):
                # Record every recognizable token in memory even after an unexpected 200 or malformed grant.
                self.tokens.add(token)
            if error is not None:
                need(status == 400 and data == {"error": error}, "mcp_code_boundary_failed")
                return None
            need(status == 200 and isinstance(token, str) and token in self.tokens
                 and set(data) == {"access_token", "scope", "token_type", "expires_in"}
                 and data["scope"] == SCOPE and data["token_type"] == "Bearer"
                 and type(data["expires_in"]) is int and 0 < data["expires_in"] <= 900, "mcp_grant_invalid")
            return token

    def revoke_all(self):
        revoked = 0
        for token in self.tokens:
            try:
                with oauth_intent(self.ledger, "revoke"):
                    status, _, raw = self.api.exchange("POST", REVOKE, form={"client_id": CLIENT, "token": token})
                    need(status == 200 and not raw, "mcp_revoke_unconfirmed")
                    self.rpc("tools/list", {}, token, 401)
                revoked += 1
            except (Exception, KeyboardInterrupt):
                continue
        return {"revoked_count": revoked, "http_after_revoke": 401 if revoked == len(self.tokens) and revoked else None}

    def tool(self, token, name, args, *, denied=False, private_markers=()):
        result = self.rpc("tools/call", {"name": name, "arguments": args}, token)
        content = result.get("content")
        need(isinstance(content, list) and len(content) == 1 and isinstance(content[0], dict)
             and set(content[0]) == {"type", "text"} and content[0]["type"] == "text"
             and isinstance(content[0]["text"], str), "mcp_tool_content_invalid")
        text = content[0]["text"]
        value = json_object(text)
        if denied:
            need(set(result) == {"isError", "content"} and result["isError"] is True and value == NOT_FOUND,
                 "mcp_case_not_found_required")
            return None
        need(set(result) == {"structuredContent", "content"} and result["structuredContent"] == value,
             "mcp_tool_structured_content_mismatch")
        need(value.get("source") == "credit", "mcp_credit_source_required")
        timestamp = datetime.fromisoformat(value["asOf"].replace("Z", "+00:00"))
        need(timestamp.tzinfo is not None, "mcp_asof_invalid")
        if private_markers:
            structured = json.dumps(result["structuredContent"], ensure_ascii=False)
            need(all(marker not in text and marker not in structured for marker in private_markers),
                 "mcp_private_statement_leak")
        return value


def options_equivalent(preview, web, inputs, days):
    """Compare decimals to the Web result and independently amortize the amount."""
    number = re.compile(r"-?[0-9]{1,18}(?:\.[0-9]{1,10})?")

    def money(value):
        need(type(value) in {str, int, float} and number.fullmatch(str(value)), "mcp_decimal_invalid")
        return Decimal(str(value))

    def cents(value):
        return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)

    need(type(web["loanAmount"]) is int and web["loanAmount"] == AMOUNT
         and {"source": None, **web["inputs"]} == {**inputs, "delayDays": days},
         "mcp_web_scenario_inputs_changed")
    rate = money(web["annualRatePercent"])
    need(rate >= 0, "mcp_annual_rate_invalid")
    used = preview["inputsUsed"]
    expected = {"currency": "RUB", "paymentModel": "ANNUITY", "baseTermMonths": BASE_TERM,
                "alternativeTermMonths": ALT_TERM, "delayDays": days, "lastIncludedDelayDays": 5,
                "receiptIncludedByPayment": days == 5, "expectedReceiptDate": inputs["expectedReceiptDate"],
                "paymentDate": inputs["paymentDate"]}
    amounts = {"loanAmountRub": Decimal(AMOUNT), "annualRatePercent": rate,
               "openingBalanceRub": Decimal(OPENING), "expectedReceiptAmountRub": Decimal(RECEIPT)}
    need(set(used) == expected.keys() | amounts.keys() and all(type(used[k]) is type(v) and used[k] == v for k, v in expected.items())
         and all(isinstance(used[k], str) and money(used[k]) == v for k, v in amounts.items()), "mcp_preview_inputs_invalid")
    terms = [BASE_TERM, ALT_TERM]
    need([x["termMonths"] for x in preview["options"]] == [x["termMonths"] for x in web["options"]] == terms,
         "mcp_preview_terms_invalid")
    balances = {}
    with localcontext() as context:
        context.prec = 48
        monthly_rate = rate / Decimal(1200)
        for term, output, reference in zip(terms, preview["options"], web["options"], strict=True):
            fields = {"monthlyPaymentRub": "monthlyPayment", "totalInterestRub": "totalInterest",
                      "balanceOnPaymentDateRub": "balanceOnPaymentDate"}
            need(set(output) == fields.keys() | {"termMonths"} and type(output["termMonths"]) is int,
                 "mcp_preview_option_shape")
            values = {k: money(output[k]) for k in fields}
            need(all(isinstance(output[k], str) and a == money(reference[fields[k]]) for k, a in values.items()),
                 "mcp_web_calculation_mismatch")
            principal = Decimal(AMOUNT)
            payment = cents(principal / term if not monthly_rate else
                            principal * monthly_rate / (1 - (1 + monthly_rate) ** -term))
            total_interest = Decimal(0)
            for month in range(term):
                interest = cents(principal * monthly_rate)
                total_interest += interest
                if month < term - 1:
                    principal -= payment - interest
            available = Decimal(OPENING + (RECEIPT if days == 5 else 0))
            need(values["monthlyPaymentRub"] == payment and values["balanceOnPaymentDateRub"] == available - payment
                 and abs(values["totalInterestRub"] - total_interest) <= Decimal("0.01"),
                 "mcp_independent_calculation_mismatch")
            balances[term] = values["balanceOnPaymentDateRub"]
    return balances


class McpSmoke(Smoke):
    def __init__(self, api, ledger, credentials):
        super().__init__(api, ledger, credentials)
        self.oauth = OAuth(api, ledger)
        self.revoked_mandates = set()

    def call(self, role, method, path, *args, **kwargs):
        # Inherit UUID ownership and mutation journaling, while limiting money and other
        # operations to the exact fixture surface this smoke uses.
        route = path.split("?", 1)[0]
        if method != "GET":
            preview = re.fullmatch(r"/api/v1/agent/applications/([a-f0-9-]{36})/financing-scenario/preview", route)
            web_preview = (method == "POST" and role == "AGENT" and path == route and preview is not None
                           and self.ledger.data["applications"].get(preview[1]) in {"A", "B"})
            match = re.fullmatch(r"/api/v1/(?:(agent|underwriting)/)?applications/([a-f0-9-]{36})/([a-z-]+)", route)
            web_action = (method, role, route) in {
                ("POST", "AGENT", "/api/v1/auth/sign-in"), ("POST", "UNDERWRITER", "/api/v1/auth/sign-in"),
                ("POST", "A", "/api/v1/auth/sign-up"), ("POST", "B", "/api/v1/auth/sign-up"),
                ("POST", "A", MANDATES), ("POST", "B", MANDATES),
                ("POST", "AGENT", "/api/v1/agent/applications"),
            }
            mandate = route.removeprefix(MANDATES + "/")
            own_mandate = (method == "DELETE" and route.startswith(MANDATES + "/")
                           and role in {"A", "B"} and self.ledger.data["mandates"].get(mandate) == role)
            case_action = False
            if match:
                scope, app, action = match.groups()
                owner = self.ledger.data["applications"].get(app)
                if owner in {"A", "B"}:
                    case_action = (
                        (method, role, scope, action) in {
                            ("PUT", "AGENT", "agent", "financing-scenario"),
                            ("POST", "AGENT", "agent", "documents"),
                            ("POST", "AGENT", "agent", "request-confirmation"),
                            ("POST", "AGENT", "agent", "cancel"),
                            ("POST", owner, None, "withdraw"),
                        }
                        or owner == "B" and (method, role, scope, action) in {
                            ("POST", "B", None, "confirm"), ("POST", "B", None, "documents"),
                            ("POST", "UNDERWRITER", "underwriting", "request-info"),
                        }
                    )
            need(web_preview or web_action or own_mandate or case_action, "mcp_unexpected_fixture_mutation")
        return super().call(role, method, path, *args, **kwargs)

    def revoke_owned(self, mandate):
        alias = self.ledger.data["mandates"].get(mandate)
        need(alias in {"A", "B"} and mandate not in self.revoked_mandates, "mcp_unowned_or_revoked_mandate")
        self.revoke(mandate)
        self.revoked_mandates.add(mandate)
        matches = [row for row in self.call(alias, "GET", MANDATES) if row["id"] == mandate]
        need(len(matches) == 1 and matches[0]["status"] == "REVOKED"
             and matches[0]["agentUserId"] == self.sessions["AGENT"]["user"]["id"], "mcp_mandate_revocation_unconfirmed")

    def summary(self, token, app, requirement, *, private_markers=()):
        summary = self.oauth.tool(token, TOOLS[1], {"applicationId": app}, private_markers=private_markers)
        item = summary["application"]
        need(set(summary) == SUMMARY_FIELDS and set(item) == CASE_FIELDS and item["applicationId"] == app
             and item["cabinetPath"] in {f"/agent/applications/{app}", f"/agent/applications/{app}#application-info-request"}
             and summary["statementRequirement"] == requirement, "mcp_summary_invalid")
        return summary

    def preview_snapshot(self, path):
        card = self.call("AGENT", "GET", path)
        return {
            "version": card["version"], "status": card["status"], "product": card["product"],
            "scenario": self.call("AGENT", "GET", path + "/financing-scenario"),
            "documents": self.call("AGENT", "GET", path + "/documents"),
            "events": self.call("AGENT", "GET", path + "/events"),
        }

    def financing_preview(self, token, app):
        agent = self.app_path(app, True)
        owner = self.app_path(app)
        self.call("A", "GET", owner, 404)
        due = self.today + timedelta(days=10)
        inputs = {"openingBalance": OPENING, "expectedReceiptAmount": RECEIPT, "expectedReceiptDate": due.isoformat(),
                  "paymentDate": (due + timedelta(days=5)).isoformat(), "delayDays": 6, "source": None,
                  "alternativeTermMonths": ALT_TERM, "selectedTermMonths": BASE_TERM}
        web = {days: self.call("AGENT", "PUT", agent + "/financing-scenario", data={**inputs, "delayDays": days})
               for days in (5, 6)}
        need(type(web[5]["revision"]) is type(web[6]["revision"]) is int and 0 < web[5]["revision"] < web[6]["revision"],
             "mcp_web_saved_revisions_invalid")
        before = self.preview_snapshot(agent)
        need(before["status"] == "DRAFT" and before["scenario"] == web[6]
             and type(before["version"]) is int and before["version"] >= 0
             and before["product"]["code"] == "WORKING_CAPITAL" and before["product"]["revolving"] is False,
             "mcp_preview_fixture_not_saved")
        basis = {"applicationVersion": str(before["version"]), "productCode": "WORKING_CAPITAL",
                 "productVersion": str(before["product"]["version"]), "savedScenarioRevision": str(web[6]["revision"])}
        listing = self.oauth.tool(token, TOOLS[0], {"limit": 20})
        need(set(listing) == {"items", "source", "asOf"} and isinstance(listing["items"], list) and 1 <= len(listing["items"]) <= 20
             and all(isinstance(x, dict) and set(x) == CASE_FIELDS for x in listing["items"]), "mcp_list_invalid")
        own = [item for item in listing["items"] if item["applicationId"] == app]
        summary = self.summary(token, app, "NOT_REQUESTED")
        need(own == [summary["application"]] and summary["application"]["status"] == "DRAFT"
             and all(summary[key] == basis[key] for key in ("applicationVersion", "savedScenarioRevision")), "mcp_list_summary_mismatch")
        balances = {}
        for days in (5, 6):
            preview = self.oauth.tool(token, TOOLS[2], {"applicationId": app, "delayDays": days,
                                                       "alternativeTermMonths": ALT_TERM})
            need(set(preview) == {"asOf", "source", "preview", "basis", "inputsUsed", "options", "cabinetPath"}
                 and preview["preview"] is True and preview["basis"] == basis
                 and preview["cabinetPath"] == f"/agent/applications/{app}#draft-financing", "mcp_preview_basis_invalid")
            balances[days] = options_equivalent(preview, web[days], inputs, days)
            trial = self.call("AGENT", "POST", agent + "/financing-scenario/preview", data={
                "mode": "WEB_FORM", "delayDays": days, "alternativeTermMonths": ALT_TERM,
                "openingBalance": OPENING, "expectedReceiptAmount": RECEIPT,
                "expectedReceiptDate": inputs["expectedReceiptDate"], "paymentDate": inputs["paymentDate"],
                "draftTermsOverride": {"amount": AMOUNT, "baseTermMonths": BASE_TERM},
            })
            need(isinstance(trial, dict) and set(trial) == set(preview) and isinstance(trial["asOf"], str),
                 "mcp_web_preview_parity_mismatch")
            timestamp = datetime.fromisoformat(trial["asOf"].replace("Z", "+00:00"))
            need(timestamp.tzinfo is not None
                 and digest({key: value for key, value in trial.items() if key != "asOf"}) ==
                 digest({key: value for key, value in preview.items() if key != "asOf"}),
                 "mcp_web_preview_parity_mismatch")
        after = self.preview_snapshot(agent)
        later = self.summary(token, app, "NOT_REQUESTED")
        need(before == after and {k: v for k, v in summary.items() if k != "asOf"} ==
             {k: v for k, v in later.items() if k != "asOf"}
             and all(balances[5][term] - balances[6][term] == RECEIPT for term in (BASE_TERM, ALT_TERM)),
             "mcp_preview_modified_saved_data")
        return {"application_version": before["version"], "scenario_revision": web[6]["revision"],
                "unchanged_sha256": digest(before), "receipt_difference_rub": RECEIPT}

    def private_statement_probe(self, token, app):
        agent, owner = self.app_path(app, True), self.app_path(app)
        self.hand(app)
        self.confirm("B", app)
        self.wait("B", app, {"MANUAL_REVIEW"})
        self.call("UNDERWRITER", "POST", "/api/v1/underwriting/applications/" + app + "/request-info",
                  data={"items": [{"documentType": "BANK_STATEMENT"}], "comment": "Synthetic MCP statement boundary"})
        self.summary(token, app, "OPEN")
        marker = "PRIVATE_MCP_" + self.ledger.run_id
        payload = pdf([marker])
        doc = self.upload("B", app, "BANK_STATEMENT", payload, True)
        bank_bytes = self.call("UNDERWRITER", "GET", owner + "/documents/" + doc, binary=True)
        need(bank_bytes == payload, "mcp_private_statement_fixture_unconfirmed")
        self.call("AGENT", "GET", agent + "/documents/" + doc, 404)
        private_hash = sha256(payload).hexdigest()
        markers = (doc, "private-smoke.pdf", marker, private_hash, "%PDF-", b64encode(payload).decode("ascii"))
        self.summary(token, app, "SATISFIED", private_markers=markers)
        listing = self.oauth.tool(token, TOOLS[0], {"limit": 20}, private_markers=markers)
        need(set(listing) == {"items", "source", "asOf"} and isinstance(listing["items"], list)
             and len(listing["items"]) <= 20 and all(isinstance(x, dict) and set(x) == CASE_FIELDS for x in listing["items"]),
             "mcp_list_invalid")
        return doc, private_hash

    def verify(self):
        self.signin("AGENT", self.credentials["AGENT"])
        self.signin("UNDERWRITER", self.credentials["UNDERWRITER"])
        self.register("A")
        self.register("B")
        mandate, foreign_mandate = self.grant("A"), self.grant("B")
        app, foreign = self.create("A"), self.create("B", amount=300000)
        token = self.oauth.grant(self.credentials["AGENT"], self.sessions["AGENT"]["token"])
        self.check(CHECKS[0])
        init = self.oauth.rpc("initialize", {"protocolVersion": PROTOCOL, "capabilities": {},
                              "clientInfo": {"name": "cabinet-dev-acceptance", "version": "1"}}, token)
        need(init.get("protocolVersion") == PROTOCOL and init.get("serverInfo", {}).get("name") == "pump-cabinet-mcp",
             "mcp_initialize_invalid")
        listing = self.oauth.rpc("tools/list", {}, token)
        tools = listing["tools"]
        need(set(listing) == {"tools"} and isinstance(tools, list) and len(tools) == 3
             and {t["name"] for t in tools} == set(TOOLS)
             and all(t["annotations"] == {"readOnlyHint": True} for t in tools), "mcp_tool_set_invalid")
        preview = self.financing_preview(token, app)
        self.check(CHECKS[1])
        self.check(CHECKS[2])
        doc, private_hash = self.private_statement_probe(token, foreign)
        self.revoke_owned(foreign_mandate)
        self.call("AGENT", "GET", self.app_path(foreign, True), 404)
        need(self.call("B", "GET", self.app_path(foreign))["id"] == foreign, "mcp_foreign_case_not_confirmed")
        for name, args in ((TOOLS[1], {"applicationId": foreign}),
                           (TOOLS[2], {"applicationId": foreign, "delayDays": 5, "alternativeTermMonths": ALT_TERM})):
            self.oauth.tool(token, name, args, denied=True)
        self.check(CHECKS[3])
        self.hand(app)
        owner = self.app_path(app)
        packet = self.call("A", "GET", owner + "/financing-scenario")
        need(packet["revision"] == preview["scenario_revision"] and packet["status"] == "AWAITING_CLIENT",
             "mcp_handed_packet_unconfirmed")
        self.summary(token, app, "NOT_REQUESTED")
        self.revoke_owned(mandate)
        self.oauth.tool(token, TOOLS[1], {"applicationId": app}, denied=True)
        self.call("AGENT", "GET", self.app_path(app, True), 404)
        need(self.call("A", "GET", owner)["status"] == "PENDING_CLIENT_CONFIRMATION"
             and self.call("A", "GET", owner + "/financing-scenario") == packet, "mcp_handed_packet_lost")
        self.check(CHECKS[4])
        self.ledger.data["facts"].update({"application": app, "foreign_application": foreign,
            "private_document": doc, "private_sha256": private_hash, "preview": preview, "handed_sha256": digest(packet)})

    def cleanup_application(self, app, alias):
        owner = self.sessions[alias]["user"]
        company = owner["companyId"]
        need(self.ledger.data["clients"].get(company) == {"alias": alias, "user_id": owner["id"]},
             "mcp_cleanup_owner_unconfirmed")
        path = self.app_path(app)
        status, raw = self.api.request("GET", path, self.sessions[alias]["token"])
        if status == 404:
            need(any(value == alias and key not in self.revoked_mandates for key, value in self.ledger.data["mandates"].items()),
                 "mcp_cleanup_no_active_own_mandate")
            path = self.app_path(app, True)
            row = self.call("AGENT", "GET", path)
            need(row["id"] == app and row["company"]["id"] == company and row["status"] == "DRAFT",
                 "mcp_cleanup_unknown_draft")
            closed = self.call("AGENT", "POST", path + "/cancel")
        else:
            need(status == 200, "mcp_cleanup_owner_http")
            row = json_object(raw)
            need(row["id"] == app and row["company"]["id"] == company, "mcp_cleanup_application_scope")
            if row["status"] in {"WITHDRAWN", "REJECTED"}:
                return
            need(row["status"] in {"PENDING_CLIENT_CONFIRMATION", "DRAFT", "SUBMITTED", "ENRICHMENT", "SCORING",
                                   "MANUAL_REVIEW", "INFO_REQUESTED", "OFFER_ISSUED"}, "mcp_cleanup_unsafe_application_state")
            closed = self.call(alias, "POST", path + "/withdraw", data={"comment": "Synthetic MCP cleanup"})
        need(closed["id"] == app and closed["company"]["id"] == company and closed["status"] == "WITHDRAWN",
             "mcp_cleanup_withdraw_unconfirmed")

    def cleanup(self):
        data = self.ledger.data
        apps = []
        warnings = []
        for app, alias in data["applications"].items():
            try:
                need(not data["pending"], "mcp_cleanup_pending_write")
                self.cleanup_application(app, alias)
                apps.append(app)
            except (Exception, KeyboardInterrupt):
                warnings.append(app)
        for mandate in data["mandates"]:
            try:
                need(not data["pending"], "mcp_cleanup_pending_write")
                if mandate not in self.revoked_mandates:
                    self.revoke_owned(mandate)
            except (Exception, KeyboardInterrupt):
                warnings.append(mandate)
        return {"closed_applications": sorted(apps), "revoked_mandates": sorted(self.revoked_mandates),
                "failed_owned_ids": sorted(warnings), "audit_documents_accounts_retained": True}


def controller_proof(deployment, context, report):
    release = report["release"]
    need(deployment.mcp is True and context.desired.get("mcp") == {"enabled": True}
         and deployment.values.get(FLAG) == "true"
         and deployment.values.get("CABINET_DOMAIN") == HOST
         and deployment.values.get("CABINET_MCP_OAUTH_CLIENT_ID") == CLIENT
         and deployment.values.get("CABINET_MCP_OAUTH_REDIRECT_URI") == REDIRECT, "mcp_dev_configuration_required")
    need(release["mcp"] == {"resource": RESOURCE, "metadata_http": 200, "unauthenticated_http": 401, "private_http": 404}
         and release["https"]["origin"] == ORIGIN and release["https"]["status"] == 200,
         "mcp_public_verification_required")
    image_digest = None
    for service, component in (("credit", "credit"), ("cabinet-mcp", "mcp")):
        image = deployment.release.image_for(component)
        attested = release["images"][service]
        need(isinstance(image, str) and re.fullmatch(r"[^@/\s]+(?:/[^@/\s]+)+@sha256:[a-f0-9]{64}", image)
             and attested["registry_ref"] == image and attested["revision"] == context.revision
             and re.fullmatch(r"sha256:[a-f0-9]{64}", attested["image_id"]), "mcp_release_image_unconfirmed")
        if component == "mcp":
            image_digest = image.rsplit("@", 1)[1]
    previously = resume_business(context)
    business = release["business_smoke"]
    need(previously is not None and isinstance(business, dict) and set(business) == set(previously)
         and type(business["reused_completed"]) is bool
         and all(business[k] == v for k, v in previously.items() if k != "reused_completed"),
         "mcp_completed_business_required")
    return {"business_ledger_sha256": previously["ledger_sha256"], "mcp_image_digest": image_digest}


def completed(context, binding, path, raw, data, *, reused):
    fields = {"schema", "sha", "manifest_sha256", "run_id", "attempt_id", "context_sha256", "status", "clients",
              "mandates", "applications", "loans", "documents", "pending", "checks", "facts", "cleanup", "browser_proof"}
    need(isinstance(data, dict) and set(data) == fields and type(data["schema"]) is int and data["schema"] == 2
         and data["sha"] == context.revision and data["manifest_sha256"] == context.digest
         and data["attempt_id"] == context.attempt_id and context.attempt_id
         and data["context_sha256"] == context_digest(context)
         and path.name == context.revision + "-" + identifier(data["run_id"]) + ".json"
         and data["status"] == "PASS" and data["pending"] == [] and data["loans"] == {}
         and data["browser_proof"] == "NOT_RUN" and data["checks"] == list(CHECKS), "mcp_ledger_binding_invalid")
    apps, clients, mandates, docs = (data[k] for k in ("applications", "clients", "mandates", "documents"))
    need(all(isinstance(group, dict) and all(identifier(key) != JURY_COMPANY for key in group)
             for group in (apps, clients, mandates, docs))
         and all(len(group) == 2 for group in (apps, clients, mandates)) and 1 <= len(docs) <= 32
         and all(set(group.values()) == {"A", "B"} for group in (apps, mandates))
         and all(app in apps for app in docs.values()), "mcp_owned_registry_invalid")
    need(all(isinstance(client, dict) and set(client) == {"alias", "user_id"} and identifier(client["user_id"])
             for client in clients.values()) and {c["alias"] for c in clients.values()} == {"A", "B"}
         and len({c["user_id"] for c in clients.values()}) == 2, "mcp_owned_clients_invalid")
    facts = data["facts"]
    need(isinstance(facts, dict) and set(facts) == binding.keys() |
         {"application", "foreign_application", "private_document", "private_sha256", "preview", "handed_sha256"}
         and all(facts[k] == value for k, value in binding.items())
         and apps.get(facts["application"]) == "A" and apps.get(facts["foreign_application"]) == "B"
         and docs.get(facts["private_document"]) == facts["foreign_application"]
         and all(SHA256.fullmatch(facts[key]) for key in ("handed_sha256", "private_sha256")), "mcp_facts_binding_invalid")
    preview = facts["preview"]
    need(isinstance(preview, dict) and set(preview) ==
         {"application_version", "scenario_revision", "unchanged_sha256", "receipt_difference_rub"}
         and type(preview["application_version"]) is int and preview["application_version"] >= 0
         and type(preview["scenario_revision"]) is int and preview["scenario_revision"] > 0
         and type(preview["receipt_difference_rub"]) is int and preview["receipt_difference_rub"] == RECEIPT
         and SHA256.fullmatch(preview["unchanged_sha256"]), "mcp_preview_facts_invalid")
    expected_cleanup = {"closed_applications": sorted(apps), "revoked_mandates": sorted(mandates),
                        "failed_owned_ids": [], "audit_documents_accounts_retained": True,
                        "token": {"revoked_count": 1, "http_after_revoke": 401}}
    need(data["cleanup"] == expected_cleanup and data["cleanup"]["audit_documents_accounts_retained"] is True
         and type(data["cleanup"]["token"]["revoked_count"]) is int
         and type(data["cleanup"]["token"]["http_after_revoke"]) is int, "mcp_cleanup_unconfirmed")
    return {"schema": 1, "sha": context.revision, "manifest_sha256": context.digest, "attempt_id": context.attempt_id,
            "api_mcp_business_proof": "PASS", "checks": list(CHECKS), "browser_proof": "NOT_RUN",
            "ledger": str(path), "ledger_sha256": sha256(raw).hexdigest(), "reused_completed": reused,
            **binding, "retained": data["cleanup"], "requires_fresh_alpha_catchup_after_smoke": True}


def resume_completed(context, binding):
    directory = context.state / NAMESPACE
    if not directory.exists() and not directory.is_symlink():
        return None
    private_dir(directory)
    paths = []
    for candidate in directory.glob(context.revision + "-*"):
        paths.append(candidate)
        need(len(paths) == 1, "mcp_multiple_run_records")
    if not paths:
        return None
    path = paths[0]
    need(path.resolve() == path, "mcp_ledger_path_invalid")
    raw, data = private_record(path)
    return completed(context, binding, path, raw, data, reused=True)


def run(deployment, state, current, desired, report, *, lock_fd):
    """Invoke only under the ordinary release controller, before fresh Alpha catchup."""
    try:
        context = verified_context(deployment, state, current, desired, report, lock_fd)
        binding = controller_proof(deployment, context, report)
        proof = resume_completed(context, binding)
        if proof is not None:
            return proof
        credentials = jury_credentials(state)
        api = SmokeAPI()
        ledger = Ledger(context, namespace=NAMESPACE)
    except SmokeError:
        raise
    except (OSError, ValueError, TypeError, KeyError, AttributeError, RuntimeError):
        raise SmokeError("mcp_controller_context_unconfirmed") from None
    ledger.data["facts"].update(binding)
    smoke = McpSmoke(api, ledger, credentials)
    previous = signal.getsignal(signal.SIGTERM)

    def interrupted(*_):
        raise SmokeError("mcp_smoke_interrupted")

    signal.signal(signal.SIGTERM, interrupted)
    failure = None
    try:
        smoke.verify()
    except SmokeError as error:
        failure = str(error)
    except (Exception, KeyboardInterrupt):
        failure = "mcp_business_flow_unconfirmed"
    finally:
        try:
            api.cleaning = True
            tokens = smoke.oauth.revoke_all()
            ledger.data["cleanup"] = {**smoke.cleanup(), "token": tokens}
            if failure is None:
                ledger.data["status"] = "PASS"
                try:
                    completed(context, binding, ledger.path, b"", ledger.data, reused=False)
                except (SmokeError, TypeError, ValueError, KeyError, AttributeError):
                    ledger.data["status"] = "INCOMPLETE"
                    failure = "mcp_completion_unconfirmed"
            ledger.save()
        except (Exception, KeyboardInterrupt):
            failure = "mcp_cleanup_unconfirmed"
        finally:
            signal.signal(signal.SIGTERM, previous)
    need(failure is None, failure)
    return completed(context, binding, ledger.path, ledger.last, ledger.data, reused=False)


if __name__ == "__main__":
    raise SystemExit("Host controller context required; no standalone network-enabled CLI")
