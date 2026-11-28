"""Real business API checks on test-local's owned CI stack, never a Dev receipt.

No URL/credentials CLI, no production context bypass. The caller owns the fresh
restore project and its normal finally/down cleanup. Only Identity role fixtures
use the existing operator SQL channel; every business object uses the HTTP API.
"""
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import subprocess
import time
from uuid import uuid4

import business_smoke as business
from common import Deployment, ROOT

OVERLAY = ROOT / "ci/gitlab/quality/compose.business-api.yaml"
CHECKS = business.CHECKS
SERVICES = ("identity", "credit", "engagement", "external-sim", "frontend")


def command(*arguments):
    result = subprocess.run(["docker", *arguments], capture_output=True, timeout=45)
    business.need(result.returncode == 0 and len(result.stdout) <= 1024 * 1024,
                  "ci_docker_metadata_unconfirmed")
    return result.stdout.decode("utf-8")


def ci_environment(environ, revision):
    business.need(environ.get("CI") == "true"
                  and re.fullmatch(r"[1-9][0-9]{0,15}", environ.get("CI_JOB_ID", ""))
                  and re.fullmatch(r"[a-f0-9]{40}", revision or "")
                  and environ.get("CI_COMMIT_SHA") == revision, "ci_source_context_required")
    host = environ.get("CABINET_CI_DOCKER_HOST", "")
    business.need(re.fullmatch(r"unix:///[A-Za-z0-9_./-]+", host)
                  and ".." not in host.split("/")
                  and host not in {"unix:///var/run/docker.sock", "unix:///run/docker.sock"}
                  and environ.get("DOCKER_HOST") == host and not environ.get("DOCKER_CONTEXT"),
                  "isolated_ci_daemon_required")


def ci_context(environ, project, revision):
    ci_environment(environ, revision)
    business.need(re.fullmatch(r"cabinet-prod-restore-test-[a-f0-9]{12}", project or ""),
                  "owned_restore_project_required")


def daemon_guard():
    labels = json.loads(command("info", "--format", "{{json .Labels}}"))
    business.need(isinstance(labels, list) and "cabinet-ci=true" in labels,
                  "isolated_ci_daemon_label_required")


def verified_fixture(deployment, revision, runtime):
    business.need(isinstance(deployment, Deployment), "real_deployment_required")
    ci_context(os.environ, deployment.project, revision)
    business.need(deployment.local is True and deployment.release is None
                  and not deployment.runtime_modes and not deployment.alpha_dev
                  and deployment.values.get("CABINET_DOMAIN") == "localhost"
                  and deployment.values.get("DEMO_ACCOUNTS") == "false"
                  and deployment.values.get("SIM_CONTROL_ENABLED") == "false"
                  and deployment.dev_payment_reserve_simulation is False,
                  "isolated_base_fixture_required")
    suffix = deployment.project.removeprefix("cabinet-prod-restore-test-")
    expected = ROOT / "deploy/runtime" / ("test-" + suffix)
    business.need(runtime == expected and runtime.resolve() == runtime
                  and deployment.path == runtime / "target.env", "provider_runtime_binding_required")
    port = deployment.values.get("HTTPS_PORT", "")
    business.need(re.fullmatch(r"[0-9]{4,5}", port) and 1024 <= int(port) < 65535
                  and deployment.values.get("PUBLIC_CABINET_URL") == "https://localhost:" + port,
                  "provider_loopback_origin_required")
    daemon_guard()
    images = {}
    for service in SERVICES:
        container = deployment.container(service)
        info = json.loads(command("inspect", "--format",
                                  '{{json .Config.Labels}}', container))
        business.need(info.get("com.docker.compose.project") == deployment.project
                      and info.get("com.docker.compose.service") == service,
                      "provider_container_ownership_required")
        image = command("inspect", "--format", "{{.Image}}", container).strip()
        actual = command("image", "inspect", "--format",
                         '{{index .Config.Labels "org.opencontainers.image.revision"}}', image).strip()
        business.need(actual == revision, "provider_image_revision_required")
        images[service] = image
    return int(port), images


class LoopbackHTTPS(http.client.HTTPSConnection):
    """Pin the numeric socket; verify the provider certificate as localhost."""
    def connect(self):
        business.need(not self._tunnel_host, "ci_proxy_forbidden")
        raw = socket.create_connection(("127.0.0.1", self.port), self.timeout)
        try:
            self.sock = self._context.wrap_socket(raw, server_hostname="localhost")
        except BaseException:
            raw.close()
            raise


class API:
    def __init__(self, port, ca):
        business.need(type(port) is int and 1024 <= port < 65535, "invalid_ci_https_port")
        business.need(ca.is_file() and not ca.is_symlink(), "provider_ca_required")
        self.port = port
        self.tls = ssl.create_default_context(cafile=str(ca))
        business.need(self.tls.check_hostname and self.tls.verify_mode == ssl.CERT_REQUIRED,
                      "ci_tls_verification_required")
        self.started = time.monotonic()
        self.requests = self.bytes = 0

    def request(self, method, path, token=None, data=None, file=None, key=None):
        business.need(business.allowed_route(method, path), "unexpected_api_route")
        remaining = business.RUN_SECONDS - (time.monotonic() - self.started)
        business.need(remaining > 0 and self.requests < business.MAX_REQUESTS, "request_budget_exhausted")
        self.requests += 1
        headers = {"Accept-Encoding": "identity", "Accept": "application/json"}
        if token:
            business.need(isinstance(token, str) and not any(ord(c) < 33 for c in token), "invalid_session")
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = business.identifier(key)
        body = None
        if file is not None:
            filename, content = file if isinstance(file, tuple) else ("synthetic.pdf", file)
            business.need(data is None and isinstance(content, bytes)
                          and filename in {"synthetic.pdf", "private-smoke.pdf"}, "invalid_upload")
            boundary = "ci-smoke" + uuid4().hex
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\n'
                    'Content-Type: application/pdf\r\n\r\n').encode() + content + f"\r\n--{boundary}--\r\n".encode()
        elif data is not None:
            headers["Content-Type"] = "application/json"
            body = json.dumps(data, ensure_ascii=False, allow_nan=False).encode()
        business.need(body is None or len(body) <= business.MAX_REQUEST, "request_body_oversize")
        connection = LoopbackHTTPS("localhost", self.port, timeout=min(15, remaining), context=self.tls)
        try:
            with business.deadline(min(25, remaining)):
                connection.request(method, path, body=body, headers=headers)
                response = connection.getresponse()
                business.need(response.status not in {301, 302, 303, 307, 308}, "redirect_rejected")
                business.need(response.getheader("Content-Encoding", "identity").strip().lower() in {"", "identity"},
                              "response_encoding")
                size = response.getheader("Content-Length")
                business.need(size is None or re.fullmatch(r"[0-9]{1,10}", size), "response_length")
                business.need(size is None or int(size) <= business.MAX_RESPONSE, "response_oversize")
                raw = bytearray()
                while True:
                    chunk = response.read1(8192)
                    if not chunk:
                        break
                    business.need(len(raw) + len(chunk) <= business.MAX_RESPONSE
                                  and self.bytes + len(chunk) <= business.MAX_TOTAL_BYTES,
                                  "response_budget_exhausted")
                    self.bytes += len(chunk)
                    raw.extend(chunk)
                return response.status, bytes(raw)
        except business.SmokeError:
            raise
        except (OSError, http.client.HTTPException, ValueError):
            raise business.SmokeError("ci_https_request_unconfirmed") from None
        finally:
            connection.close()


def provision_roles(deployment):
    """Two job-owned roles only, following provision-bank-user's private channel."""
    from bcrypt import gensalt, hashpw
    credentials = {}
    rows = []
    for role in ("AGENT", "UNDERWRITER"):
        identifier = str(uuid4())
        email = role.lower() + "-" + identifier + "@ci-business.invalid"
        password = secrets.token_urlsafe(32)
        password_hash = hashpw(password.encode(), gensalt(rounds=12)).decode()
        business.need(re.fullmatch(r"\$2[aby]\$12\$[./A-Za-z0-9]{53}", password_hash), "ci_password_hash_invalid")
        rows.append(f"('{identifier}', 'Synthetic CI operator', '{email}', '{role}', 'ACTIVE', null, false, '{password_hash}')")
        credentials[role] = {"email": email, "password": password}
    deployment.sql("identity", "begin; insert into users (id, full_name, email, role, status, company_id, can_sign, password_hash) values "
                   + ",".join(rows) + "; commit;")
    return credentials


def flags(deployment):
    result = {}
    for service, names in {"credit": ("DEV_PAYMENT_RESERVE_SIMULATION_ENABLED", "CABINET_DEPLOYMENT_PROJECT", "DEMO_ACCOUNTS"),
                           "identity": ("DEMO_ACCOUNTS",), "external-sim": ("SIM_CONTROL_ENABLED",)}.items():
        values = json.loads(command("inspect", "--format", "{{json .Config.Env}}", deployment.container(service)))
        pairs = [item.split("=", 1) for item in values if "=" in item]
        selected = {key: value for key, value in pairs if key in names}
        business.need(len([key for key, _ in pairs if key in names]) == len(names), "runtime_flags_missing_or_duplicate")
        result[service] = selected
    return result


def execute(api, ledger, credentials):
    """Reuse real host flow methods; do not call or loosen host verified_context."""
    smoke = business.Smoke(api, ledger, credentials)
    passed = clean = False
    failure = "ci_business_flow_incomplete"
    try:
        smoke.signin("AGENT", credentials["AGENT"])
        smoke.signin("UNDERWRITER", credentials["UNDERWRITER"])
        business.need(smoke.call(None, "GET", "/api/v1/auth/options")["demoAccounts"] is False, "demo_accounts_enabled")
        products = {item["code"]: item for item in smoke.call(None, "GET", "/api/v1/products")}
        product = products.get("OVERDRAFT", {})
        business.need("WORKING_CAPITAL" in products and product.get("revolving") is True
                      and product.get("minAmount", float("inf")) <= 160000 <= 400000 <= product.get("maxAmount", 0)
                      and product.get("minTermMonths", 13) <= 12 <= product.get("maxTermMonths", 0),
                      "absolute_line_fixture_unreachable")
        for alias in ("A", "B"):
            smoke.register(alias)
            smoke.grant(alias)
        smoke.financing()
        smoke.private_statement()
        smoke.reserve()
        smoke.mandate()
        business.need(set(ledger.data["checks"]) == CHECKS and len(ledger.data["checks"]) == len(CHECKS),
                      "business_case_set_incomplete")
        passed = True
    except business.SmokeError as error:
        failure = str(error)
    except Exception:
        failure = "ci_business_flow_unconfirmed"
    finally:
        try:
            clean = smoke.cleanup()
        except BaseException:
            clean = False
        ledger.data["status"] = "PASS" if passed and clean else "INCOMPLETE"
        ledger.save()
    business.need(passed and clean, failure)


def run(deployment, revision, runtime, output):
    port, images = verified_fixture(deployment, revision, runtime)
    state = runtime / "ci-business"
    state.mkdir(mode=0o700, exist_ok=False)
    # No manifest or immutable Dev state is fabricated: this is a separate CI fixture identity.
    fixture_digest = hashlib.sha256((revision + "\n" + deployment.project).encode()).hexdigest()
    ledger = business.Ledger(business.Context(revision, fixture_digest, state, {}, {}))
    ledger.data.update(evidence_scope="isolated-base-local-api", manifest_sha256=None, dev_accepted=False,
                       four_remote_accepted=False, fixture_fingerprint_sha256=fixture_digest)
    ledger.save()
    original = list(deployment.command)
    report = {"schema": 1, "source": revision, "ci_job_id": int(os.environ["CI_JOB_ID"]),
              "evidence_scope": "isolated-base-local-api", "physical_project": deployment.project,
              "runtime_modes": {name: "embedded" for name in business.RUNTIMES}, "images": images,
              "role_fixture": {"owner": "Identity", "roles": ["AGENT", "UNDERWRITER"], "created_by": "scoped-operator-channel"},
              "business_fixture": "normal-public-api-only", "four_remote_accepted": False,
              "dev_accepted": False, "browser_proof": "NOT_RUN", "passed": False,
              "feature_restored_false": False}
    try:
        credentials = provision_roles(deployment)
        daemon_guard()
        deployment.command.extend(["-f", str(OVERLAY)])
        deployment.compose("up", "-d", "--no-deps", "--no-build", "--wait", "--wait-timeout", "300", "credit")
        report["actual_flags"] = flags(deployment)
        business.need(report["actual_flags"] == {
            "credit": {"DEV_PAYMENT_RESERVE_SIMULATION_ENABLED": "true", "CABINET_DEPLOYMENT_PROJECT": "cabinet-dev", "DEMO_ACCOUNTS": "false"},
            "identity": {"DEMO_ACCOUNTS": "false"}, "external-sim": {"SIM_CONTROL_ENABLED": "false"}}, "actual_fixture_policy_changed")
        api = API(port, deployment.gateway_config.parent / "local-ca.crt")
        execute(api, ledger, credentials)
        report.update(checks=ledger.data["checks"], facts=ledger.data["facts"], requests=api.requests,
                      response_bytes=api.bytes, owned_cleanup=ledger.data["cleanup"], passed=True)
    finally:
        report["business_completed"] = report["passed"]
        report["passed"] = False
        deployment.command[:] = original
        try:
            daemon_guard()
            deployment.compose("up", "-d", "--no-deps", "--no-build", "--wait", "--wait-timeout", "300", "credit")
            after = flags(deployment)
            business.need(after["credit"]["DEV_PAYMENT_RESERVE_SIMULATION_ENABLED"] == "false"
                          and after["credit"]["CABINET_DEPLOYMENT_PROJECT"] == "cabinet-prod"
                          and after["credit"]["DEMO_ACCOUNTS"] == "false"
                          and after["identity"]["DEMO_ACCOUNTS"] == "false"
                          and after["external-sim"]["SIM_CONTROL_ENABLED"] == "false", "ci_feature_not_restored")
            report["feature_restored_false"] = True
            report["passed"] = report["business_completed"]
        finally:
            report["ledger_sha256"] = hashlib.sha256(ledger.path.read_bytes()).hexdigest()
            # The private journal remains only in deploy/runtime (not a CI artifact).
            (output / "business-api.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


if __name__ == "__main__":
    raise SystemExit("Only the owned test-local CI provider may invoke this module")
