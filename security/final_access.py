"""Agent-channel access cases on safe, missing objects. Positive lifecycle is covered by Playwright."""

from datetime import datetime, timezone
import json
from pathlib import Path
import time
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]
MIN_REQUEST_INTERVAL_SECONDS = 1 / 25
ROLES = ("anonymous", "client", "other_client", "manager", "underwriter", "risk_admin", "agent", "other_agent")
ROLE_TITLES = ("Без токена", "Клиент", "Другой клиент", "Менеджер", "Андеррайтер", "Риск-администратор", "Агент", "Другой агент")
AGENT_ONLY = (401, 403, 403, 403, 403, 403, 200, 200)
AGENT_MISSING = (401, 403, 403, 403, 403, 403, 404, 404)
CLIENT_ONLY = (401, 200, 200, 403, 403, 403, 403, 403)
CLIENT_MISSING = (401, 404, 404, 403, 403, 403, 403, 403)
PRODUCT_MISSING = (401, 403, 403, 403, 403, 404, 403, 403)
PRODUCT_DUPLICATE = (401, 403, 403, 403, 403, 409, 403, 403)
SIGNED_IN = (401, 200, 200, 200, 200, 200, 200, 200)
HINTS_MISSING = (401, 404, 404, 200, 200, 200, 404, 404)
LOAN_MISSING = (401, 404, 404, 404, 404, 404, 404, 404)

ROUTES = (
    ("identity", "GET", "/api/v1/agent/clients", AGENT_ONLY),
    ("credit", "GET", "/api/v1/agent/applications", AGENT_ONLY),
    ("credit", "POST", "/api/v1/agent/applications", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/loan", AGENT_MISSING),
    ("credit", "PUT", "/api/v1/agent/applications/{id}", AGENT_MISSING),
    ("credit", "POST", "/api/v1/agent/applications/{id}/cancel", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/documents", AGENT_MISSING),
    ("credit", "POST", "/api/v1/agent/applications/{id}/documents", AGENT_MISSING),
    ("credit", "DELETE", "/api/v1/agent/applications/{id}/documents/{documentId}", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/documents/{documentId}", AGENT_MISSING),
    ("credit", "POST", "/api/v1/agent/applications/{id}/info-provided", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/messages", AGENT_MISSING),
    ("credit", "POST", "/api/v1/agent/applications/{id}/messages", AGENT_MISSING),
    ("credit", "POST", "/api/v1/agent/applications/{id}/messages/attachments", AGENT_MISSING),
    ("credit", "POST", "/api/v1/agent/applications/{id}/messages/read", AGENT_MISSING),
    ("credit", "POST", "/api/v1/agent/applications/{id}/request-confirmation", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/portfolio", AGENT_ONLY),
    ("credit", "GET", "/api/v1/agent/pre-approved-offers", AGENT_ONLY),
    ("credit", "POST", "/api/v1/agent/pre-approved-offers/{id}/apply", AGENT_MISSING),
    ("credit", "POST", "/api/v1/applications/{id}/confirm", CLIENT_MISSING),
    ("credit", "POST", "/api/v1/applications/{id}/return-to-agent", CLIENT_MISSING),
    ("credit", "GET", "/api/v1/applications/{id}/loan", LOAN_MISSING),
    ("credit", "POST", "/api/v1/loans/{loanId}/tranches", CLIENT_MISSING),
    ("credit", "POST", "/api/v1/loans/{loanId}/payments/next", CLIENT_MISSING),
    ("credit", "POST", "/api/v1/loans/{loanId}/repay-early", CLIENT_MISSING),
    ("credit", "POST", "/api/v1/bank/products", PRODUCT_DUPLICATE),
    ("credit", "POST", "/api/v1/bank/products/{code}/archive", PRODUCT_MISSING),
    ("credit", "POST", "/api/v1/bank/products/{code}/publish", PRODUCT_MISSING),
    ("credit", "POST", "/api/v1/bank/products/{code}/restore", PRODUCT_MISSING),
    ("credit", "GET", "/api/v1/pre-approved-offers", CLIENT_ONLY),
    ("credit", "POST", "/api/v1/pre-approved-offers/{id}/apply", CLIENT_MISSING),
    ("engagement", "POST", "/api/v1/assistant/messages/stream", SIGNED_IN),
    ("engagement", "GET", "/api/v1/assistant/next-steps", HINTS_MISSING),
    ("engagement", "POST", "/api/v1/assistant/reminders", AGENT_MISSING),
)


def check(api):
    accounts = {"client": "client", "other_client": "mebel", "manager": "manager", "underwriter": "underwriter",
                "risk_admin": "risk", "agent": "agent", "other_agent": "agent2"}
    tokens = {}
    for role, account in accounts.items():
        time.sleep(2.1)  # The real auth gateway enforces a 30/minute address limit.
        tokens[role] = api.sign_in(f"{account}@demo.test")["token"]

    missing = str(uuid4())
    parameters = {"id": missing, "loanId": missing, "documentId": str(uuid4()), "code": "MISSING_FINAL_MATRIX"}
    pdf = (ROOT / "contracts/postman/fixtures/synthetic.pdf").read_bytes()
    checks = []
    try:
        for service, method, template, statuses in ROUTES:
            for role, expected in zip(ROLES, statuses, strict=True):
                # Keep this access check under the gateway's 30 requests/second agent budget.
                time.sleep(MIN_REQUEST_INTERVAL_SECONDS)
                route = template.format(**parameters)
                body, headers = None, {}
                if method in {"POST", "PUT"} and template in {"/api/v1/agent/applications", "/api/v1/agent/applications/{id}"}:
                    body = {"companyId": missing, "productCode": "WORKING_CAPITAL", "purpose": "WORKING_CAPITAL", "amount": 1_000_000, "termMonths": 12}
                elif template == "/api/v1/bank/products":
                    body = {"code": "WORKING_CAPITAL", "name": "Тестовый продукт", "description": "Матрица доступа", "purposes": ["WORKING_CAPITAL"],
                            "minAmount": 100_000, "maxAmount": 2_000_000, "minTermMonths": 3, "maxTermMonths": 24,
                            "baseRate": 21, "autoApprovalLimit": 1_000_000, "revolving": False, "reason": "Синтетическая проверка доступа"}
                elif "/bank/products/" in template:
                    body = {"expectedVersion": 1, "reason": "Синтетическая проверка доступа"}
                elif template.endswith("/confirm"):
                    body = {"bureauConsent": True}
                elif template.endswith("/tranches"):
                    body = {"amount": 100_000, "purpose": "Синтетическая проверка доступа"}
                elif template.endswith("/messages") and method == "POST":
                    body, headers = {"text": "Синтетическая проверка доступа"}, {"Idempotency-Key": str(uuid4())}
                elif template.endswith("/messages/read"):
                    body = {"lastReadSeq": 1}
                elif template.endswith("/next-steps"):
                    route += "?applicationId=" + missing
                elif template.endswith("/reminders"):
                    body = {"applicationId": missing}
                elif template.endswith("/stream"):
                    body, headers = {"question": "Дай юридический совет"}, {"Accept": "text/event-stream"}
                token = tokens.get(role)
                if method == "POST" and template.endswith(("/documents", "/messages/attachments")):
                    upload_route = route + "?type=TAX_RETURN" if template.endswith("/documents") else route
                    response = api._upload(token, upload_route, pdf, "synthetic.pdf", None, {"Idempotency-Key": str(uuid4())})
                else:
                    response = api.request(method, route, body, token, expected=None, headers=headers)
                checks.append({"service": service, "method": method, "path": template, "role": role,
                               "expected": expected, "actual": response["status"], "duration_ms": response["duration_ms"]})
                if expected == 200 and template.endswith("/stream"):
                    assert "event: answer" in response["body"] and "event: failure" not in response["body"], "Ответ SSE не завершён"
        failures = [row for row in checks if row["actual"] != row["expected"]]
        assert not failures, f"Несовпадения в новых маршрутах: {json.dumps(failures[:12], ensure_ascii=False)}"
    finally:
        reports = ROOT / "security/reports"
        reports.mkdir(exist_ok=True)
        expected = len(ROUTES) * len(ROLES)
        outcome = {"checked_at": datetime.now(timezone.utc).isoformat(), "operations": len(ROUTES), "roles": ROLES,
                   "checks": checks, "expected_checks": expected,
                   "passed": len(checks) == expected and all(row["actual"] == row["expected"] for row in checks)}
        (reports / "final-access-matrix.json").write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Новые маршруты: {len(checks)}/{expected}", flush=True)
