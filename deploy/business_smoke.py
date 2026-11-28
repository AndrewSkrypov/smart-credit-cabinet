"""Host-side Dev API acceptance. No standalone network-enabled CLI or fixture access."""
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, localcontext
import fcntl
import hashlib
import hmac
import http.client
import json
import math
import os
from pathlib import Path
import re
import secrets
import signal
import ssl
import stat
import tempfile
import time
from urllib.parse import urlsplit
from uuid import UUID, uuid4

from dev_payment_reserve import ORIGIN, canonical_origin

HOST = "dev.credit-cabinet.ru"
MAX_RECORD = 128 * 1024
MAX_RESPONSE = 1024 * 1024
MAX_REQUEST = 256 * 1024
MAX_TOTAL_BYTES = 32 * 1024 * 1024
MAX_REQUESTS = 400
RUN_SECONDS = 1200
TERMINAL = {"DISBURSED", "REJECTED", "WITHDRAWN", "CLOSED"}
JURY_COMPANY = "6f1d4c1e-0b8e-4c55-9a51-000000000001"
RUNTIMES = ("catalog", "assessment", "servicing", "documents")
CHECKS = frozenset({"financing_versions_consent_bank_pep_simulated_funding",
    "private_statement_bank_only_exact_bytes_and_agent_readiness",
    "reserve_0_130000_limit80000_shortfall50000_single_effect",
    "own_mandate_revocation_replay_404_client_continuity"})


class SmokeError(RuntimeError):
    """Only fixed codes are allowed; never attach a response, request or credential."""


def need(value, code):
    if not value:
        raise SmokeError(code)


def identifier(value):
    need(isinstance(value, str), "invalid_uuid")
    try:
        need(str(UUID(value)) == value, "invalid_uuid")
    except ValueError:
        raise SmokeError("invalid_uuid") from None
    return value


def private_dir(path):
    info = path.lstat()
    need(path.is_absolute() and path.resolve() == path and stat.S_ISDIR(info.st_mode)
         and stat.S_IMODE(info.st_mode) == 0o700 and info.st_uid == os.geteuid(), "private_directory_invalid")


def private_record(path):
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK), "rb") as stream:
        info = os.fstat(stream.fileno())
        need(stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode) == 0o600
             and info.st_uid == os.geteuid() and info.st_size <= MAX_RECORD, "private_record_invalid")
        raw = stream.read(MAX_RECORD + 1)
    need(len(raw) <= MAX_RECORD, "private_record_oversize")
    def unique(pairs):
        value = {}
        for key, item in pairs:
            need(key not in value, "private_record_json_invalid")
            value[key] = item
        return value
    def invalid_constant(_):
        raise SmokeError("private_record_json_invalid")
    try:
        return raw, json.loads(raw, object_pairs_hook=unique, parse_constant=invalid_constant)
    except (ValueError, UnicodeError):
        raise SmokeError("private_record_json_invalid") from None


def private_json(path):
    return private_record(path)[1]


@dataclass(frozen=True)
class Context:
    revision: str
    digest: str
    state: Path
    current: dict
    desired: dict
    attempt_id: str | None = None


def context_digest(context):
    return hashlib.sha256(json.dumps({"current": context.current, "desired": context.desired},
        ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def verified_context(deployment, state, current, desired, report, lock_fd):
    try:
        return _verified_context(deployment, state, current, desired, report, lock_fd)
    except SmokeError:
        raise
    except (OSError, ValueError, TypeError, KeyError, AttributeError, IndexError, RuntimeError):
        # This check runs before the API/finally block. Keep malformed metadata
        # inside the controller's RuntimeError fail-closed ingress boundary.
        raise SmokeError("verified_dev_context_unconfirmed") from None


def _verified_context(deployment, state, current, desired, report, lock_fd):
    # The ordinary, versioned controller passes its validated Deployment and held lock.
    from common import Deployment, ROOT
    from runtime_attempts import load

    need(isinstance(deployment, Deployment) and deployment.project == "cabinet-dev"
         and deployment.local is False and deployment.dev_payment_reserve_simulation is True,
         "verified_dev_feature_context_required")
    release = deployment.release
    need(release is not None and release.has_all_runtimes and release.alpha_dev
         and ROOT.name == release.revision and release.path == ROOT / "release.json"
         and re.fullmatch(r"[a-f0-9]{40}", release.revision)
         and hashlib.sha256(release.path.read_bytes()).hexdigest() == release.checksum,
         "versioned_release_context_required")
    private_dir(state)
    need(deployment.path == state / "cabinet.env" and canonical_origin(deployment.values.get("PUBLIC_CABINET_URL")) == ORIGIN
         and deployment.values.get("DEMO_ACCOUNTS") == "false"
         and deployment.values.get("SIM_CONTROL_ENABLED") == "false", "dev_origin_or_feature_policy_changed")
    need(type(lock_fd) is int and os.path.samestat((state / "deploy.lock").lstat(), os.fstat(lock_fd)),
         "held_deployment_lock_required")
    fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    need(private_json(state / "current.json") == current and private_json(state / "pending.json") == desired
         and desired.get("revision") == release.revision and desired.get("manifest_sha256") == release.checksum,
         "actual_current_pending_or_manifest_changed")
    attempt = load(state, release)
    need(isinstance(attempt.get("attempt"), str) and re.fullmatch(r"[a-f0-9]{32}", attempt["attempt"])
         and attempt["previous"] == current and attempt["target"] == desired
         and isinstance(attempt.get("backup"), dict)
         and re.fullmatch(r"[a-f0-9]{64}", attempt["backup"].get("sha256", ""))
         and attempt.get("alpha", {}).get("frozen", {}).get("checkpoint", {}).get("generation") == desired.get("alpha_dev", {}).get("generation")
         and all(desired.get(name) == {"mode": "remote"}
                 and isinstance(getattr(release, name), dict) and getattr(release, name).get("dev_mode") == "remote"
                 for name in RUNTIMES)
         and desired.get("alpha_dev") == current.get("alpha_dev")
         and desired["alpha_dev"]["read_enabled"] is False, "immutable_attempt_or_remote_authority_changed")
    ready = report.get("release", {})
    need(report.get("manifest_sha256") == release.checksum and ready.get("passed") is True
         and ready.get("expected_revision") == release.revision
         and all(ready.get(name, {}).get("mode") == "remote" for name in RUNTIMES)
         and all(ready[name].get("revision") == release.revision and ready[name].get("manifest_sha256") == release.checksum
                 for name in ("assessment", "servicing", "documents")), "runtime_verification_required")
    return Context(release.revision, release.checksum, state, current, desired, attempt["attempt"])


def jury_credentials(state):
    value = private_json(state / "jury.json")
    need(isinstance(value, dict) and set(value) == {"CLIENT", "AGENT", "UNDERWRITER"}, "jury_schema_invalid")
    for role, entry in value.items():
        need(isinstance(entry, dict) and set(entry) == {"email", "password"}
             and entry["email"] == role.lower() + "@jury.test"
             and isinstance(entry["password"], str) and 16 <= len(entry["password"].encode()) <= 72,
             "jury_schema_invalid")
    return value


@contextmanager
def deadline(seconds):
    # A socket read timeout alone does not bound a slowly dripping HTTP header.
    need(signal.getitimer(signal.ITIMER_REAL) == (0.0, 0.0), "existing_process_alarm")
    previous = signal.getsignal(signal.SIGALRM)
    def expired(*_):
        raise SmokeError("request_deadline")
    signal.signal(signal.SIGALRM, expired)
    signal.setitimer(signal.ITIMER_REAL, seconds)
    try:
        yield
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous)


def allowed_route(method, path):
    if method not in {"GET", "POST", "PUT", "DELETE"} or not isinstance(path, str):
        return False
    p = urlsplit(path)
    if p.scheme or p.netloc or p.fragment or not path.startswith("/api/v1/") or "\\" in path or "%" in path:
        return False
    if any(ord(c) < 32 for c in path) or any(x in {".", ".."} for x in p.path.split("/")):
        return False
    fixed = {"/api/v1/auth/sign-in", "/api/v1/auth/sign-up", "/api/v1/auth/options", "/api/v1/products",
             "/api/v1/companies/me", "/api/v1/companies/me/agent-mandates", "/api/v1/agent/applications"}
    uuid = r"[a-f0-9-]{36}"
    return (p.path in fixed or re.fullmatch(r"/api/v1/companies/me/agent-mandates/" + uuid, p.path) is not None
            or re.fullmatch(r"/api/v1/(?:agent/|underwriting/)?applications/" + uuid
                            + r"(?:/(?:financing-scenario|request-confirmation|return-to-agent|confirm|cancel|withdraw|submit|events|messages|info-provided|request-info|approve|signing/code|sign|offer/accept|loan|invoice-extraction|payment-reserve|documents(?:/" + uuid + r")?))?", p.path) is not None
            or re.fullmatch(r"/api/v1/loans/" + uuid + r"/(?:tranches|payment-reserve(?:/(?:receipt-event|confirm-draw))?)", p.path) is not None)


class API:
    def __init__(self):
        self.started = time.monotonic()
        self.requests = self.bytes = 0
        self.tls = ssl.create_default_context()

    def request(self, method, path, token=None, data=None, file=None, key=None):
        need(allowed_route(method, path), "unexpected_api_route")
        remaining = RUN_SECONDS - (time.monotonic() - self.started)
        need(remaining > 0 and self.requests < MAX_REQUESTS, "request_budget_exhausted")
        self.requests += 1
        headers = {"Accept-Encoding": "identity", "Accept": "application/json"}
        if token:
            need(isinstance(token, str) and not any(ord(c) < 33 for c in token), "invalid_session")
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = identifier(key)
        body = None
        if file is not None:
            filename, content = file if isinstance(file, tuple) else ("synthetic.pdf", file)
            need(data is None and isinstance(content, bytes) and filename in {"synthetic.pdf", "private-smoke.pdf"}, "invalid_upload")
            boundary = "smoke" + uuid4().hex
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                    'Content-Type: application/pdf\r\n\r\n').encode() + content + f"\r\n--{boundary}--\r\n".encode()
        elif data is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(data, ensure_ascii=False, allow_nan=False).encode()
        need(body is None or len(body) <= MAX_REQUEST, "request_body_oversize")
        connection = http.client.HTTPSConnection(HOST, 443, timeout=min(15, remaining), context=self.tls)
        try:
            with deadline(min(25, remaining)):
                connection.request(method, path, body=body, headers=headers)
                response = connection.getresponse()
                need(response.status not in {301, 302, 303, 307, 308}, "redirect_rejected")
                need(response.getheader("Content-Encoding", "identity").strip().lower() in {"", "identity"}, "response_encoding")
                declared = response.getheader("Content-Length")
                need(declared is None or re.fullmatch(r"[0-9]{1,10}", declared), "response_length")
                need(declared is None or int(declared) <= MAX_RESPONSE, "response_oversize")
                result = bytearray()
                while True:
                    chunk = response.read1(8192)
                    if not chunk:
                        break
                    need(len(result) + len(chunk) <= MAX_RESPONSE and self.bytes + len(chunk) <= MAX_TOTAL_BYTES,
                         "response_budget_exhausted")
                    self.bytes += len(chunk)
                    result.extend(chunk)
                return response.status, bytes(result)
        except SmokeError:
            raise
        except (OSError, http.client.HTTPException, ValueError):
            raise SmokeError("https_request_unconfirmed") from None
        finally:
            connection.close()


class Ledger:
    def __init__(self, context):
        directory = context.state / "business-smoke"
        directory.mkdir(mode=0o700, exist_ok=True)
        private_dir(directory)
        need(next(directory.glob(context.revision + "-*.json"), None) is None, "existing_run_requires_operator_reconciliation")
        self.run_id = str(uuid4())
        self.path = directory / (context.revision + "-" + self.run_id + ".json")
        self.data = {"schema": 2, "sha": context.revision, "manifest_sha256": context.digest, "run_id": self.run_id,
                     "attempt_id": context.attempt_id, "context_sha256": context_digest(context),
                     "status": "INCOMPLETE", "clients": {}, "mandates": {}, "applications": {}, "loans": {},
                     "documents": {}, "pending": [], "checks": [], "facts": {}, "cleanup": {}, "browser_proof": "NOT_RUN"}
        self.last = None
        self.save()

    def save(self):
        try:
            raw = (json.dumps(self.data, sort_keys=True, indent=2, allow_nan=False) + "\n").encode()
        except (TypeError, ValueError):
            raise SmokeError("ledger_serialization_invalid") from None
        need(len(raw) <= MAX_RECORD, "ledger_budget_exhausted")
        if self.last is None:
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
        else:
            need(private_json(self.path) == json.loads(self.last), "ledger_changed")
            fd, name = tempfile.mkstemp(prefix=".smoke-", dir=self.path.parent)
            try:
                with os.fdopen(fd, "wb") as stream:
                    stream.write(raw)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(name, self.path)
            finally:
                Path(name).unlink(missing_ok=True)
        fd = os.open(self.path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        self.last = raw

    def own(self, kind, ident, owner):
        ident = identifier(ident)
        need(kind in {"clients", "mandates", "applications", "loans", "documents"}, "invalid_owned_kind")
        need(ident != JURY_COMPANY and ident not in self.data[kind] and len(self.data[kind]) < 32, "owned_resource_conflict")
        self.data[kind][ident] = owner
        self.save()
        return ident

    def beginning(self, kind, owner):
        intent = {"id": str(uuid4()), "creating": kind, "owner": owner}
        self.data["pending"].append(intent)
        self.save()
        return intent

    def created(self, intent, ident):
        self.own(intent["creating"], ident, intent["owner"])
        self.data["pending"].remove(intent)
        self.save()
        return ident


def completed_proof(context, path, data, raw, *, reused):
    return {"schema": 1, "sha": context.revision, "manifest_sha256": context.digest,
            "attempt_id": context.attempt_id, "api_business_proof": "PASS", "checks": data["checks"],
            "invoice_mode": data.get("invoice_mode"), "browser_proof": "NOT_RUN",
            "ledger": str(path), "ledger_sha256": hashlib.sha256(raw).hexdigest(),
            "retained": data["cleanup"], "reused_completed": reused,
            "requires_fresh_alpha_catchup_after_smoke": True}


def validate_completed(context, path, data):
    need(isinstance(context.attempt_id, str) and re.fullmatch(r"[a-f0-9]{32}", context.attempt_id),
         "completed_dev_attempt_required")
    keys = {"schema", "sha", "manifest_sha256", "run_id", "attempt_id", "context_sha256", "status",
            "clients", "mandates", "applications", "loans", "documents", "pending", "checks", "facts", "cleanup", "browser_proof", "invoice_mode"}
    need(isinstance(data, dict) and set(data) == keys and type(data["schema"]) is int and data["schema"] == 2
         and data["sha"] == context.revision and data["manifest_sha256"] == context.digest
         and data["attempt_id"] == context.attempt_id and data["context_sha256"] == context_digest(context)
         and data["status"] == "PASS" and data["pending"] == [] and data["browser_proof"] == "NOT_RUN"
         and data["invoice_mode"] in {"MODEL", "MANUAL"}, "completed_ledger_binding_invalid")
    need(path.name == context.revision + "-" + identifier(data["run_id"]) + ".json"
         and isinstance(data["checks"], list) and len(data["checks"]) == len(CHECKS)
         and all(isinstance(value, str) for value in data["checks"]) and set(data["checks"]) == CHECKS,
         "completed_ledger_checks_invalid")
    for kind in ("clients", "mandates", "applications", "loans", "documents"):
        need(isinstance(data[kind], dict) and 0 < len(data[kind]) <= 32
             and all(identifier(key) and key != JURY_COMPANY for key in data[kind]), "completed_owned_registry_invalid")
    clients = data["clients"]
    need(len(clients) == 2 and all(isinstance(value, dict) and set(value) == {"alias", "user_id"}
         and value["alias"] in {"A", "B"} and identifier(value["user_id"]) for value in clients.values())
         and {value["alias"] for value in clients.values()} == {"A", "B"}
         and len({value["user_id"] for value in clients.values()}) == 2, "completed_client_scope_invalid")
    apps, loans, docs = data["applications"], data["loans"], data["documents"]
    need(len(apps) == 5 and list(apps.values()).count("A") == 4 and list(apps.values()).count("B") == 1
         and len(data["mandates"]) == 2 and set(data["mandates"].values()) == {"A", "B"}
         and len(loans) == 3 and len(set(loans.values())) == 3 and all(app in apps for app in loans.values())
         and all(app in apps for app in docs.values()), "completed_owned_registry_invalid")
    clean = data["cleanup"]
    need(isinstance(clean, dict) and set(clean) == {"failed_owned_ids", "funded_retained_application_ids", "audit_documents_accounts_retained", "unknown_mutation_intents_retained"}
         and clean["failed_owned_ids"] == [] and clean["audit_documents_accounts_retained"] is True
         and type(clean["unknown_mutation_intents_retained"]) is int and clean["unknown_mutation_intents_retained"] == 0
         and isinstance(clean["funded_retained_application_ids"], list)
         and len(clean["funded_retained_application_ids"]) == 3
         and set(clean["funded_retained_application_ids"]) == set(loans.values()), "completed_cleanup_unconfirmed")
    facts = data["facts"]
    need(isinstance(facts, dict) and set(facts) == {"financing", "private_statement", "reserve"}, "completed_facts_invalid")
    finance, private, reserves = facts["financing"], facts["private_statement"], facts["reserve"]
    need(isinstance(finance, dict) and set(finance) == {"application", "first_revision", "accepted_revision", "selected_term_months", "loan_amount", "annual_rate_percent", "options", "confirm_submit_events"}
         and finance.get("application") in loans.values() and apps[finance["application"]] == "A"
         and finance.get("selected_term_months") == 24 and finance.get("loan_amount") == 2_000_000
         and type(finance.get("first_revision")) is int and type(finance.get("accepted_revision")) is int
         and 0 < finance["first_revision"] < finance["accepted_revision"]
         and type(finance.get("confirm_submit_events")) is int and finance["confirm_submit_events"] == 1,
         "completed_financing_facts_invalid")
    checked_options({"loanAmount": finance["loan_amount"], "annualRatePercent": finance["annual_rate_percent"], "options": finance["options"]})
    need(isinstance(finance["options"], list) and len(finance["options"]) == 2
         and all(isinstance(option, dict) and set(option) == {"termMonths", "monthlyPayment", "totalInterest", "balanceOnPaymentDate"}
                 for option in finance["options"]), "completed_financing_facts_invalid")
    need(isinstance(private, dict) and set(private) == {"application", "document", "bank_exact_bytes_sha256", "agent_ready", "foreign_client_denied", "agent_bytes_denied"}
         and private.get("application") in apps and apps[private["application"]] == "B"
         and private.get("document") in docs and docs[private["document"]] == private["application"]
         and isinstance(private.get("bank_exact_bytes_sha256"), str) and re.fullmatch(r"[a-f0-9]{64}", private["bank_exact_bytes_sha256"])
         and all(private.get(key) is True for key in ("agent_ready", "foreign_client_denied", "agent_bytes_denied")),
         "completed_private_facts_invalid")
    need(isinstance(reserves, list) and len(reserves) == 2, "completed_reserve_facts_invalid")
    reserve_apps = set()
    for item, limit in zip(reserves, (160000, 400000)):
        expected_keys = {"application", "loan", "approved_limit", "initial_disbursed", "initial_available", "delayed",
                         "received" if limit == 160000 else "confirmed"}
        need(isinstance(item, dict) and set(item) == expected_keys and item.get("application") in apps and apps[item["application"]] == "A"
             and item.get("loan") in loans and loans[item["loan"]] == item["application"]
             and item.get("approved_limit") == limit and item.get("initial_disbursed") == limit // 2
             and item.get("initial_available") == limit // 2, "completed_reserve_facts_invalid")
        reserve_apps.add(item["application"])
        late = item.get("delayed", {})
        need(isinstance(late, dict) and set(late) == {"version", "requiredAmount", "remainingLimit", "shortfall", "canDraw"}
             and all(type(late.get(key)) is int for key in ("version", "requiredAmount", "remainingLimit", "shortfall"))
             and late["version"] > 0 and late.get("requiredAmount") == 130000
             and late.get("remainingLimit") == limit // 2 and late.get("shortfall") == max(130000 - limit // 2, 0)
             and late.get("canDraw") is (limit == 400000), "completed_reserve_facts_invalid")
        if limit == 160000:
            received = item.get("received")
            need(received == {"requiredAmount": 0, "shortfall": 0, "projectedBalance": 40000, "canDraw": False}
                 and all(type(received[key]) is int for key in ("requiredAmount", "shortfall", "projectedBalance"))
                 and received["canDraw"] is False,
                 "completed_reserve_facts_invalid")
        else:
            confirmed = item.get("confirmed", {})
            need(isinstance(confirmed, dict) and set(confirmed) == {"version_requested", "drawn_amount", "after_available", "after_disbursed", "tranches_before", "tranches_after"}
                 and type(confirmed.get("version_requested")) is int and confirmed["version_requested"] == late["version"] and confirmed.get("drawn_amount") == 130000
                 and confirmed.get("after_available") == 70000 and confirmed.get("after_disbursed") == 330000
                 and type(confirmed.get("tranches_before")) is int and confirmed["tranches_before"] == 1
                 and confirmed.get("tranches_after") == 2, "completed_reserve_facts_invalid")
    need(len(reserve_apps) == 2 and set(loans.values()) == reserve_apps | {finance["application"]}, "completed_facts_ownership_invalid")


def resume_completed(context):
    directory = context.state / "business-smoke"
    if not directory.exists() and not directory.is_symlink():
        return None
    try:
        private_dir(directory)
        candidates = []
        for path in directory.glob(context.revision + "-*"):
            candidates.append(path)
            need(len(candidates) <= 1, "ambiguous_completed_business_ledger")
        if not candidates:
            return None
        path = candidates[0]
        need(path.parent == directory and path.resolve() == path, "completed_ledger_path_invalid")
        raw, data = private_record(path)
        validate_completed(context, path, data)
        return completed_proof(context, path, data, raw, reused=True)
    except SmokeError:
        raise
    except (OSError, ValueError, TypeError, KeyError, AttributeError, IndexError, ArithmeticError):
        raise SmokeError("completed_business_ledger_unconfirmed") from None


def synthetic_inn(seed):
    digits = "00" + "".join(str(b % 10) for b in hashlib.sha256(seed.encode()).digest()[:7])
    check = sum(int(n) * w for n, w in zip(digits, (2, 4, 10, 3, 5, 9, 4, 6, 8))) % 11 % 10
    return digits + str(check)


def client_credentials(run_id, alias, credentials):
    need(alias in {"A", "B"}, "invalid_client_alias")
    tag = identifier(run_id) + ":" + alias
    password = "SmokeA1!" + hmac.new(credentials["AGENT"]["password"].encode(), tag.encode(), hashlib.sha256).hexdigest()[:40]
    return {"email": "dev-business-" + run_id + "-" + alias + "@example.test", "password": password}


def pdf(lines=()):
    text = "BT /F1 12 Tf 40 740 Td " + " 0 -20 Td ".join("(" + line + ") Tj" for line in lines) + " ET"
    if not lines:
        text = "50 700 200 12 re f"
    objects = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Count 1 /Kids [3 0 R] >>",
               "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
               "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", f"<< /Length {len(text.encode())} >>\nstream\n{text}\nendstream"]
    output = b"%PDF-1.4\n"
    offsets = []
    for number, value in enumerate(objects, 1):
        offsets.append(len(output))
        output += f"{number} 0 obj\n{value}\nendobj\n".encode()
    start = len(output)
    output += ("xref\n0 6\n0000000000 65535 f \n" + "".join(f"{n:010} 00000 n \n" for n in offsets)
               + f"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n").encode()
    return output


def checked_options(snapshot):
    need(snapshot["loanAmount"] == 2_000_000 and sorted(x["termMonths"] for x in snapshot["options"]) == [12, 24],
         "scenario_amount_or_terms_invalid")
    with localcontext() as context:
        context.prec = 40
        need(type(snapshot["annualRatePercent"]) in (int, float) and math.isfinite(snapshot["annualRatePercent"]), "scenario_rate_invalid")
        rate = Decimal(str(snapshot["annualRatePercent"])) / 1200
        need(rate >= 0, "scenario_rate_invalid")
        for option in snapshot["options"]:
            need(type(option["termMonths"]) is int and all(type(option[k]) in (int, float) and math.isfinite(option[k])
                 for k in ("monthlyPayment", "totalInterest", "balanceOnPaymentDate")), "scenario_values_invalid")
            term = option["termMonths"]
            monthly = Decimal(2_000_000) / term if rate == 0 else Decimal(2_000_000) * rate / (1 - (1 + rate) ** -term)
            payment = Decimal(str(option["monthlyPayment"]))
            need(abs(payment - monthly) <= 1 and abs(Decimal(str(option["balanceOnPaymentDate"])) - (150000 - payment)) <= 1
                 and abs(Decimal(str(option["totalInterest"])) - (monthly * term - 2_000_000)) <= 1,
                 "scenario_calculation_mismatch")


class Smoke:
    def __init__(self, api, ledger, credentials):
        self.api, self.ledger, self.credentials = api, ledger, credentials
        self.sessions = {}
        self.today = datetime.now(timezone.utc).date()

    def call(self, role, method, path, expected=200, *, data=None, file=None, key=None, binary=False):
        # Every UUID route must name a resource created and journalled by THIS run.
        ids = re.findall(r"[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}", path)
        known = set().union(*(set(self.ledger.data[k]) for k in ("clients", "mandates", "applications", "loans", "documents")))
        need(all(ident in known for ident in ids), "unowned_uuid_route")
        pending = None
        if method != "GET":
            pending = {"id": str(uuid4()), "role": role, "method": method, "path": path.split("?")[0]}
            need(len(self.ledger.data["pending"]) < 32, "pending_mutation_budget")
            self.ledger.data["pending"].append(pending)
            self.ledger.save()
        token = self.sessions.get(role, {}).get("token")
        status, raw = self.api.request(method, path, token, data, file, key)
        need(status in (expected if isinstance(expected, tuple) else (expected,)), "unexpected_http_status")
        if pending is not None:
            self.ledger.data["pending"].remove(pending)
            self.ledger.save()
        if binary:
            return raw
        if status == 204 or status >= 400:
            return None
        try:
            return json.loads(raw)
        except (ValueError, UnicodeError):
            raise SmokeError("response_json_invalid") from None

    def signin(self, role, credentials):
        session = self.call(role, "POST", "/api/v1/auth/sign-in", data=credentials)
        need(isinstance(session.get("token"), str) and session["token"] and session["user"]["role"] == role,
             "jury_session_role_invalid")
        identifier(session["user"]["id"])
        self.sessions[role] = session

    def register(self, alias):
        # Passwords are derived in memory for later operator cleanup, never written to the ledger/report.
        tag = self.ledger.run_id + ":" + alias
        credentials = client_credentials(self.ledger.run_id, alias, self.credentials)
        intent = self.ledger.beginning("clients", alias)
        session = self.call(alias, "POST", "/api/v1/auth/sign-up", data={**credentials, "inn": synthetic_inn(tag),
                            "fullName": "Synthetic Dev acceptance", "personalDataConsent": True})
        user = session["user"]
        need(user["role"] == "CLIENT" and user.get("canSign") is True and isinstance(session.get("token"), str), "new_client_invalid")
        intent["owner"] = {"alias": alias, "user_id": identifier(user["id"])}
        self.ledger.created(intent, user["companyId"])
        self.sessions[alias] = session
        company = self.call(alias, "GET", "/api/v1/companies/me")
        need(company["id"] == user["companyId"] and company["inn"] == synthetic_inn(tag), "new_company_scope_invalid")
        return user["companyId"]

    def grant(self, alias):
        intent = self.ledger.beginning("mandates", alias)
        row = self.call(alias, "POST", "/api/v1/companies/me/agent-mandates", 201,
                        data={"agentUserId": self.sessions["AGENT"]["user"]["id"],
                              "expiresAt": (datetime.now(timezone.utc) + timedelta(days=2)).isoformat()})
        need(row["agentUserId"] == self.sessions["AGENT"]["user"]["id"] and row["status"] == "ACTIVE", "new_mandate_invalid")
        return self.ledger.created(intent, row["id"])

    def app_path(self, ident, agent=False):
        need(ident in self.ledger.data["applications"], "unowned_application")
        return "/api/v1/" + ("agent/" if agent else "") + "applications/" + ident

    def create(self, alias, product="WORKING_CAPITAL", amount=2_000_000, term=12):
        company = self.sessions[alias]["user"]["companyId"]
        intent = self.ledger.beginning("applications", alias)
        row = self.call("AGENT", "POST", "/api/v1/agent/applications", 201,
                        data={"companyId": company, "productCode": product, "purpose": "CASH_GAP" if product == "OVERDRAFT" else "WORKING_CAPITAL",
                              "amount": amount, "termMonths": term, "declaredRevenue12m": 1_000_000_000,
                              "declaredMonthlyDebtPayments": 0, "purposeComment": "Synthetic Dev smoke " + self.ledger.run_id})
        need(row["status"] == "DRAFT" and row["company"]["id"] == company, "new_application_scope_invalid")
        return self.ledger.created(intent, row["id"])

    def upload(self, role, app, kind, content, private=False):
        intent = self.ledger.beginning("documents", app)
        path = self.app_path(app, role == "AGENT") + "/documents?type=" + kind + ("&privateToBank=true" if private else "")
        document = self.call(role, "POST", path, 201, file=("private-smoke.pdf", content) if private else content, key=str(uuid4()))
        return self.ledger.created(intent, document["id"])

    def hand(self, app, scenario_revision=0):
        path = self.app_path(app, True)
        row = self.call("AGENT", "GET", path)
        for document in row["requiredDocuments"]:
            if not document["uploaded"]:
                self.upload("AGENT", app, document["type"], pdf(["Synthetic required document"]))
        row = self.call("AGENT", "GET", path)
        row = self.call("AGENT", "POST", path + "/request-confirmation",
                        data={"expectedVersion": row["version"], "expectedScenarioRevision": scenario_revision})
        need(row["status"] == "PENDING_CLIENT_CONFIRMATION", "handoff_not_pending")

    def wait(self, alias, app, wanted, seconds=120):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            row = self.call(alias, "GET", self.app_path(app))
            if row["status"] in wanted:
                return row
            need(row["status"] not in TERMINAL and row["status"] != "OFFER_ISSUED", "unexpected_business_state")
            time.sleep(1)
        raise SmokeError("business_wait_timeout")

    def confirm(self, alias, app, revision=None):
        row = self.call(alias, "GET", self.app_path(app))
        data = {"bureauConsent": True, "acceptedProductVersion": row["product"]["version"]}
        if revision is not None:
            data["acceptedScenarioRevision"] = revision
        self.call(alias, "POST", self.app_path(app) + "/confirm", data=data)
        return data

    def fund(self, alias, app, expected_amount, revolving=False):
        path = self.app_path(app)
        self.wait(alias, app, {"MANUAL_REVIEW"})
        self.call("UNDERWRITER", "POST", "/api/v1/underwriting/applications/" + app + "/approve",
                  data={"comment": "Synthetic Dev API acceptance; simulated issuance only"})
        self.wait(alias, app, {"OFFER_ISSUED"})
        self.call(alias, "POST", path + "/offer/accept")
        signing = self.call(alias, "POST", path + "/signing/code")
        code = signing["process"]["signing"]["code"]
        need(isinstance(code, str) and re.fullmatch(r"[0-9]{6}", code), "signing_code_unavailable")
        self.call(alias, "POST", path + "/sign", data={"code": code})
        self.wait(alias, app, {"DISBURSED"})
        loan = self.call(alias, "GET", path + "/loan")
        need(loan["applicationId"] == app and loan["approvedLimit"] == expected_amount
             and loan["revolving"] is revolving and len(loan["tranches"]) == 1
             and loan["disbursedAmount"] == (expected_amount // 2 if revolving else expected_amount), "funded_loan_mismatch")
        self.ledger.own("loans", loan["id"], app)
        return loan

    def check(self, name):
        self.ledger.data["checks"].append(name)
        self.ledger.save()

    def financing(self):
        app = self.create("A")
        agent, owner, bank = self.app_path(app, True), self.app_path(app), "/api/v1/underwriting/applications/" + app
        due = self.today + timedelta(days=10)
        invoice = pdf(["Synthetic invoice", "Total amount due: 140 000 RUB", "Due date: " + due.strftime("%d.%m.%Y")])
        empty = self.call("AGENT", "POST", agent + "/invoice-extraction", file=pdf())
        need(empty["mode"] == "MANUAL" and not empty.get("amount") and empty["dates"] == [], "manual_fallback_missing")
        extracted = self.call("AGENT", "POST", agent + "/invoice-extraction", file=invoice)
        need(extracted["mode"] in {"MODEL", "MANUAL"}, "invoice_mode_invalid")
        if extracted["mode"] == "MODEL":
            need(extracted.get("amount", {}).get("value") == 140000 and any(d.get("value") == due.isoformat() for d in extracted["dates"]),
                 "model_invoice_facts_invalid")
        self.ledger.data["invoice_mode"] = extracted["mode"]
        self.ledger.save()
        document = self.upload("AGENT", app, "OTHER", invoice)
        source = {"page": 1, "amountQuote": "Total amount due: 140 000 RUB", "dateQuote": "Due date: " + due.strftime("%d.%m.%Y"), "documentId": document}
        inputs = {"openingBalance": 150000, "expectedReceiptAmount": 140000, "expectedReceiptDate": due.isoformat(),
                  "paymentDate": (due + timedelta(days=5)).isoformat(), "delayDays": 14,
                  "alternativeTermMonths": 24, "selectedTermMonths": 12, "source": source}
        first = self.call("AGENT", "PUT", agent + "/financing-scenario", data={**inputs, "expectedRevision": 0})
        checked_options(first)
        self.hand(app, first["revision"])
        first = self.call("A", "GET", owner + "/financing-scenario")
        self.call("UNDERWRITER", "GET", bank + "/financing-scenario", 404)
        self.call("A", "POST", owner + "/return-to-agent", data={"comment": "Synthetic choose24 months"})
        revised_draft = self.call("AGENT", "PUT", agent + "/financing-scenario",
                                  data={**inputs, "delayDays": 15, "selectedTermMonths": 24, "expectedRevision": first["revision"]})
        need(self.call("A", "GET", owner + "/financing-scenario") == first, "private_edit_changed_handed_snapshot")
        self.hand(app, revised_draft["revision"])
        revised = self.call("A", "GET", owner + "/financing-scenario")
        checked_options(revised)
        need(revised["revision"] > first["revision"] and revised["inputs"]["selectedTermMonths"] == 24, "scenario_revision_missing")
        self.call("A", "POST", owner + "/confirm", 409, data={"acceptedScenarioRevision": first["revision"], "bureauConsent": True})
        self.call("B", "POST", owner + "/confirm", 404, data={"acceptedScenarioRevision": revised["revision"], "bureauConsent": True})
        self.call("AGENT", "POST", owner + "/confirm", 403, data={"acceptedScenarioRevision": revised["revision"], "bureauConsent": True})
        data = self.confirm("A", app, revised["revision"])
        self.call("A", "POST", owner + "/confirm", data=data)
        accepted = self.call("UNDERWRITER", "GET", bank + "/financing-scenario")
        need(accepted["status"] == "CONFIRMED" and accepted["inputs"] == revised["inputs"]
             and accepted["options"] == revised["options"], "bank_package_differs")
        events = self.call("A", "GET", owner + "/events")
        need(sum(x["event"] == "CONFIRM_AND_SUBMIT" for x in events) == 1, "duplicate_submission_effect")
        self.ledger.data["facts"]["financing"] = {"application": app, "first_revision": first["revision"],
                "accepted_revision": revised["revision"], "selected_term_months": 24,
                "loan_amount": revised["loanAmount"], "annual_rate_percent": revised["annualRatePercent"],
                "options": [{k: option[k] for k in ("termMonths", "monthlyPayment", "totalInterest", "balanceOnPaymentDate")}
                            for option in revised["options"]], "confirm_submit_events": 1}
        self.fund("A", app, 2_000_000)
        self.check("financing_versions_consent_bank_pep_simulated_funding")

    def private_statement(self):
        app = self.create("B", amount=300000)
        self.hand(app)
        self.confirm("B", app)
        self.wait("B", app, {"MANUAL_REVIEW"})
        self.call("UNDERWRITER", "POST", "/api/v1/underwriting/applications/" + app + "/request-info",
                  data={"items": [{"documentType": "BANK_STATEMENT"}], "comment": "Synthetic private statement"})
        agent, owner = self.app_path(app, True), self.app_path(app)
        payload = pdf(["PRIVATE_DEV_" + self.ledger.run_id, "Synthetic bank statement"])
        self.call("A", "POST", owner + "/documents?type=BANK_STATEMENT&privateToBank=true", 404, file=("private-smoke.pdf", payload))
        doc = self.upload("B", app, "BANK_STATEMENT", payload, True)
        need(self.call("UNDERWRITER", "GET", owner + "/documents/" + doc, binary=True) == payload, "private_bank_bytes_differ")
        self.call("A", "GET", owner + "/documents/" + doc, 404)
        self.call("AGENT", "GET", agent + "/documents/" + doc, 404)
        self.call("AGENT", "GET", owner + "/documents/" + doc, (403, 404))
        for suffix in ("", "/documents", "/messages", "/events"):
            view = self.call("AGENT", "GET", agent + suffix)
            encoded = json.dumps(view)
            need(doc not in encoded and "private-smoke.pdf" not in encoded and "PRIVATE_DEV_" not in encoded, "private_document_leak")
        view = self.call("AGENT", "GET", agent)
        need(any(x["documentType"] == "BANK_STATEMENT" and x["satisfied"] is True for x in view["process"]["infoRequest"]["items"]),
             "private_document_readiness_missing")
        self.call("AGENT", "POST", agent + "/info-provided", (403, 409))
        self.call("B", "POST", owner + "/info-provided")
        self.ledger.data["facts"]["private_statement"] = {"application": app, "document": doc,
                "bank_exact_bytes_sha256": hashlib.sha256(payload).hexdigest(), "agent_ready": True,
                "foreign_client_denied": True, "agent_bytes_denied": True}
        self.check("private_statement_bank_only_exact_bytes_and_agent_readiness")

    def revoke(self, mandate):
        need(mandate in self.ledger.data["mandates"], "unowned_mandate")
        alias = self.ledger.data["mandates"][mandate]
        self.call(alias, "DELETE", "/api/v1/companies/me/agent-mandates/" + mandate, 204)

    def mandate(self):
        app = self.create("A", amount=300000)
        self.hand(app)
        agent, owner = self.app_path(app, True), self.app_path(app)
        self.call("UNDERWRITER", "GET", owner, 404)
        own_mandate = next(k for k, v in self.ledger.data["mandates"].items() if v == "A")
        self.revoke(own_mandate)
        self.revoke(own_mandate)
        for suffix in ("", "/events", "/documents", "/messages"):
            self.call("AGENT", "GET", agent + suffix, 404)
        self.call("A", "GET", owner)
        self.call("A", "POST", owner + "/withdraw", data={"comment": "Synthetic smoke cleanup"})
        self.check("own_mandate_revocation_replay_404_client_continuity")

    def reserve(self):
        dates = {"controlDate": (self.today + timedelta(days=7)).isoformat(), "paymentDate": (self.today + timedelta(days=14)).isoformat()}
        inputs = {"openingBalance": 1900000, "expectedReceiptAmount": 140000, "payableAmount": 2000000, "targetBuffer": 30000, **dates}
        self.ledger.data["facts"]["reserve"] = []
        for limit, available in ((160000, 80000), (400000, 200000)):
            app = self.create("A", "OVERDRAFT", limit)
            self.hand(app)
            self.confirm("A", app)
            loan = self.fund("A", app, limit, True)
            need(loan["availableLimit"] == available, "absolute_reserve_limit_unreachable")
            path = "/api/v1/loans/" + loan["id"] + "/payment-reserve"
            self.call("B", "GET", path, 404)
            self.call("AGENT", "GET", path, 403)
            self.call("B", "PUT", path, 404, data=inputs)
            self.call("B", "POST", path + "/receipt-event", 404, data={"eventId": str(uuid4()), "status": "DELAYED"})
            self.call("B", "POST", path + "/confirm-draw", 404, data={"expectedVersion": 1})
            self.call("AGENT", "POST", path + "/confirm-draw", 403, data={"expectedVersion": 1})
            waiting = self.call("A", "PUT", path, data=inputs)
            need(waiting["receiptStatus"] == "PENDING" and waiting["canDraw"] is False, "reserve_waiting_invalid")
            self.call("A", "POST", path + "/confirm-draw", 409, data={"expectedVersion": waiting["version"]})
            event = {"eventId": str(uuid4()), "status": "DELAYED"}
            late = self.call("A", "POST", path + "/receipt-event", data=event)
            need(late["requiredAmount"] == 130000 and late["remainingLimit"] == available
                 and late["shortfall"] == max(130000 - available, 0)
                 and late["canDraw"] is (available >= 130000), "absolute_reserve_130000_or_shortfall50000_failed")
            need(self.call("A", "POST", path + "/receipt-event", data=event) == late, "duplicate_receipt_effect")
            observed = {"application": app, "loan": loan["id"], "approved_limit": limit,
                        "initial_disbursed": loan["disbursedAmount"], "initial_available": available,
                        "delayed": {k: late[k] for k in ("version", "requiredAmount", "remainingLimit", "shortfall", "canDraw")}}
            self.call("A", "POST", path + "/receipt-event", 409, data={**event, "status": "RECEIVED", "receivedAmount": 140000})
            if available == 80000:
                self.call("A", "POST", path + "/confirm-draw", 409, data={"expectedVersion": late["version"]})
                received = self.call("A", "POST", path + "/receipt-event", data={"eventId": str(uuid4()), "status": "RECEIVED", "receivedAmount": 140000})
                need(received["requiredAmount"] == 0 and received["shortfall"] == 0 and received["projectedBalance"] == 40000
                     and received["canDraw"] is False, "absolute_reserve_zero_failed")
                self.call("A", "POST", path + "/confirm-draw", 409, data={"expectedVersion": received["version"]})
                need(self.call("A", "GET", self.app_path(app) + "/loan") == loan, "denied_reserve_changed_loan")
                observed["received"] = {k: received[k] for k in ("requiredAmount", "shortfall", "projectedBalance", "canDraw")}
            else:
                self.call("A", "POST", path + "/confirm-draw", 409, data={"expectedVersion": waiting["version"]})
                confirmation = {"expectedVersion": late["version"]}
                drawn = self.call("A", "POST", path + "/confirm-draw", data=confirmation)
                need(drawn["drawnAmount"] == 130000, "reserve_drawn_amount_invalid")
                need(self.call("A", "POST", path + "/confirm-draw", data=confirmation) == drawn, "duplicate_confirm_result_changed")
                after = self.call("A", "GET", self.app_path(app) + "/loan")
                need(after["disbursedAmount"] == loan["disbursedAmount"] + 130000 and len(after["tranches"]) == len(loan["tranches"]) + 1
                     and after["availableLimit"] == available - 130000, "reserve_draw_not_exactly_one_effect")
                observed["confirmed"] = {"version_requested": late["version"], "drawn_amount": drawn["drawnAmount"],
                        "after_available": after["availableLimit"], "after_disbursed": after["disbursedAmount"],
                        "tranches_before": len(loan["tranches"]), "tranches_after": len(after["tranches"])}
            self.ledger.data["facts"]["reserve"].append(observed)
            self.ledger.save()
        self.check("reserve_0_130000_limit80000_shortfall50000_single_effect")

    def cleanup(self):
        errors = []
        retained = []
        for app, alias in self.ledger.data["applications"].items():
            try:
                path = self.app_path(app)
                status, raw = self.api.request("GET", path, self.sessions[alias]["token"])
                if status == 404:
                    agent = self.call("AGENT", "GET", self.app_path(app, True))
                    need(agent["status"] == "DRAFT" and agent["company"]["id"] == self.sessions[alias]["user"]["companyId"],
                         "cleanup_unknown_application_state")
                    self.call("AGENT", "POST", self.app_path(app, True) + "/cancel")
                else:
                    need(status == 200, "cleanup_application_unconfirmed")
                    row = json.loads(raw)
                    need(row["id"] == app and row["company"]["id"] == self.sessions[alias]["user"]["companyId"], "cleanup_owner_mismatch")
                    if row["status"] in TERMINAL:
                        if row["status"] == "DISBURSED":
                            retained.append(app)
                    else:
                        end = time.monotonic() + 30
                        while row["status"] not in TERMINAL:
                            if row["status"] not in {"ENRICHING", "SCORING", "DISBURSING"}:
                                self.call(alias, "POST", path + "/withdraw", data={"comment": "Synthetic smoke cleanup"})
                            need(time.monotonic() < end, "cleanup_wait_timeout")
                            time.sleep(1)
                            row = self.call(alias, "GET", path)
            except Exception:
                errors.append(app)
        for mandate in self.ledger.data["mandates"]:
            try:
                self.revoke(mandate)
            except Exception:
                errors.append(mandate)
        self.ledger.data["cleanup"] = {"failed_owned_ids": errors, "funded_retained_application_ids": retained,
                                       "audit_documents_accounts_retained": True,
                                       "unknown_mutation_intents_retained": len(self.ledger.data["pending"])}
        self.ledger.save()
        return not errors and not self.ledger.data["pending"]


def cleanup_saved(deployment, state, current, desired, report, ledger_path, *, lock_fd):
    """Operator-invoked recovery in the same verified controller context; never resumes business mutations."""
    context = verified_context(deployment, state, current, desired, report, lock_fd)
    private_dir(state / "business-smoke")
    need(ledger_path.parent == state / "business-smoke" and ledger_path.resolve() == ledger_path, "cleanup_ledger_path_invalid")
    data = private_json(ledger_path)
    need(data.get("schema") == 2 and data.get("sha") == context.revision and data.get("manifest_sha256") == context.digest
         and data.get("attempt_id") == context.attempt_id and data.get("context_sha256") == context_digest(context)
         and ledger_path.name == context.revision + "-" + identifier(data["run_id"]) + ".json", "cleanup_ledger_binding_invalid")
    ledger = object.__new__(Ledger)
    ledger.path, ledger.run_id, ledger.data, ledger.last = ledger_path, data["run_id"], data, json.dumps(data).encode()
    credentials = jury_credentials(state)
    smoke = Smoke(API(), ledger, credentials)
    smoke.signin("AGENT", credentials["AGENT"])
    aliases = set()
    for company, owner in data["clients"].items():
        identifier(company)
        need(company != JURY_COMPANY and owner["alias"] in {"A", "B"} and owner["alias"] not in aliases, "cleanup_client_scope_invalid")
        alias = owner["alias"]
        aliases.add(alias)
        session = smoke.call(alias, "POST", "/api/v1/auth/sign-in", data=client_credentials(ledger.run_id, alias, credentials))
        need(session["user"]["role"] == "CLIENT" and session["user"]["id"] == owner["user_id"]
             and session["user"]["companyId"] == company, "cleanup_session_owner_mismatch")
        smoke.sessions[alias] = session
    for kind in ("applications", "mandates"):
        need(len(data[kind]) <= 32 and all(identifier(k) and v in aliases for k, v in data[kind].items()), "cleanup_owned_registry_invalid")
    # Unknown create intents remain explicit; no enumeration or speculative deletion.
    result = smoke.cleanup()
    return {"cleanup_complete": result, "business_acceptance": "NOT_GRANTED", "ledger": str(ledger.path), "retained": data["cleanup"]}


def run(deployment, state, current, desired, report, *, lock_fd):
    context = verified_context(deployment, state, current, desired, report, lock_fd)
    completed = resume_completed(context)
    if completed is not None:
        return completed
    credentials = jury_credentials(state)
    ledger = Ledger(context)
    smoke = Smoke(API(), ledger, credentials)
    previous = signal.getsignal(signal.SIGTERM)
    def terminated(*_):
        raise SmokeError("business_smoke_interrupted")
    signal.signal(signal.SIGTERM, terminated)
    passed = False
    failure = "business_smoke_incomplete"
    try:
        smoke.signin("AGENT", credentials["AGENT"])
        smoke.signin("UNDERWRITER", credentials["UNDERWRITER"])
        need(smoke.call(None, "GET", "/api/v1/auth/options")["demoAccounts"] is False, "demo_accounts_enabled")
        products = smoke.call(None, "GET", "/api/v1/products")
        by_code = {p["code"]: p for p in products}
        need(all(code in by_code for code in ("WORKING_CAPITAL", "OVERDRAFT"))
             and by_code["OVERDRAFT"]["revolving"] is True
             and by_code["OVERDRAFT"]["minAmount"] <= 160000 <= 400000 <= by_code["OVERDRAFT"]["maxAmount"]
             and by_code["OVERDRAFT"]["minTermMonths"] <= 12 <= by_code["OVERDRAFT"]["maxTermMonths"], "absolute_line_fixture_unreachable")
        smoke.register("A")
        smoke.register("B")
        smoke.grant("A")
        smoke.grant("B")
        smoke.financing()
        smoke.private_statement()
        smoke.reserve()
        smoke.mandate()
        need(set(ledger.data["checks"]) == CHECKS and len(ledger.data["checks"]) == len(CHECKS),
             "business_case_set_incomplete")
        passed = True
    except SmokeError as error:
        failure = str(error)
    except KeyboardInterrupt:
        failure = "business_smoke_interrupted"
    except Exception:
        failure = "business_smoke_unconfirmed"
    finally:
        try:
            clean = smoke.cleanup()
        except BaseException:
            clean = False
        signal.signal(signal.SIGTERM, previous)
        ledger.data["status"] = "PASS" if passed and clean else "INCOMPLETE"
        if passed and clean:
            try:
                validate_completed(context, ledger.path, ledger.data)
            except (SmokeError, TypeError, ValueError, KeyError, AttributeError, IndexError, ArithmeticError):
                passed = False
                failure = "business_completion_ledger_unconfirmed"
                ledger.data["status"] = "INCOMPLETE"
        ledger.save()
    need(passed and clean, failure)
    return completed_proof(context, ledger.path, ledger.data, ledger.last, reused=False)


if __name__ == "__main__":
    raise SystemExit("Host controller context required; no standalone network-enabled CLI")
