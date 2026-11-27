"""Agent-channel access checks against isolated synthetic fixtures."""

from datetime import datetime, timedelta, timezone
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
BANK_MISSING = (401, 403, 403, 404, 404, 404, 403, 403)
CLIENT_APPLICATION_MISSING = (401, 404, 404, 403, 403, 403, 404, 404)
MANDATE_CREATED = (401, 201, 201, 403, 403, 403, 403, 403)
MANDATE_REVOKED = (401, 204, 404, 403, 403, 403, 403, 403)
PRODUCT_MISSING = (401, 403, 403, 403, 403, 404, 403, 403)
PRODUCT_DUPLICATE = (401, 403, 403, 403, 403, 409, 403, 403)
SIGNED_IN = (401, 200, 200, 200, 200, 200, 200, 200)
HINTS_MISSING = (401, 404, 404, 200, 200, 200, 404, 404)
LOAN_MISSING = (401, 404, 404, 404, 404, 404, 404, 404)
MANDATES = "/api/v1/companies/me/agent-mandates"

ROUTES = (
    ("identity", "GET", "/api/v1/agent/clients", AGENT_ONLY),
    ("identity", "GET", MANDATES, CLIENT_ONLY),
    ("identity", "POST", MANDATES, MANDATE_CREATED),
    ("identity", "DELETE", MANDATES + "/{id}", MANDATE_REVOKED),
    ("credit", "GET", "/api/v1/agent/applications", AGENT_ONLY),
    ("credit", "POST", "/api/v1/agent/applications", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/loan", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/events", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/notification-access", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/financing-scenario", AGENT_MISSING),
    ("credit", "PUT", "/api/v1/agent/applications/{id}/financing-scenario", AGENT_MISSING),
    ("credit", "POST", "/api/v1/agent/applications/{id}/invoice-extraction", AGENT_MISSING),
    ("credit", "GET", "/api/v1/agent/applications/{id}/payment-reserve", AGENT_MISSING),
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
    ("credit", "GET", "/api/v1/applications/{id}/financing-scenario", CLIENT_APPLICATION_MISSING),
    ("credit", "GET", "/api/v1/underwriting/applications/{id}/financing-scenario", BANK_MISSING),
    ("credit", "POST", "/api/v1/applications/{id}/return-to-agent", CLIENT_MISSING),
    ("credit", "GET", "/api/v1/applications/{id}/loan", LOAN_MISSING),
    ("credit", "GET", "/api/v1/loans/{loanId}/payment-reserve", CLIENT_MISSING),
    ("credit", "PUT", "/api/v1/loans/{loanId}/payment-reserve", CLIENT_MISSING),
    ("credit", "POST", "/api/v1/loans/{loanId}/payment-reserve/receipt-event", CLIENT_MISSING),
    ("credit", "POST", "/api/v1/loans/{loanId}/payment-reserve/confirm-draw", CLIENT_MISSING),
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
    sessions = {}
    for role, account in accounts.items():
        time.sleep(2.1)  # The real auth gateway enforces a 30/minute address limit.
        sessions[role] = api.sign_in(f"{account}@demo.test")
    tokens = {role: session["token"] for role, session in sessions.items()}

    missing = str(uuid4())
    parameters = {"id": missing, "loanId": missing, "documentId": str(uuid4()), "code": "MISSING_FINAL_MATRIX"}
    pdf = (ROOT / "contracts/postman/fixtures/synthetic.pdf").read_bytes()
    checks = []
    mandate_tokens = {}
    issued_mandates = {}
    cleanup_errors = []
    completed = False
    now = datetime.now(timezone.utc)
    control_date = (now + timedelta(days=10)).date().isoformat()
    payment_date = (now + timedelta(days=15)).date().isoformat()
    try:
        for role in ("client", "other_client"):
            time.sleep(2.1)
            mandate_tokens[role] = api.register(f"final-matrix-{role}")["token"]
        for service, method, template, statuses in ROUTES:
            cases = list(zip(ROLES, statuses, strict=True))
            if method == "DELETE" and template == MANDATES + "/{id}":
                # Check foreign access while the tested grant is still active.
                cases = cases[:1] + cases[2:] + cases[1:2]
            for role, expected in cases:
                # Keep this access check under the gateway's 30 requests/second agent budget.
                time.sleep(MIN_REQUEST_INTERVAL_SECONDS)
                route = template.format(**parameters)
                body, headers = None, {}
                token = tokens.get(role)
                if template.startswith(MANDATES):
                    token = mandate_tokens.get(role, token)
                    if method == "POST":
                        body = {
                            "agentUserId": sessions["agent"]["user"]["id"],
                            "expiresAt": (now + timedelta(days=1)).isoformat(),
                        }
                    elif method == "DELETE":
                        route = f"{MANDATES}/{issued_mandates['client']}"
                elif method == "PUT" and template.endswith("/financing-scenario"):
                    body = {
                        "openingBalance": 150_000, "expectedReceiptAmount": 140_000,
                        "expectedReceiptDate": control_date, "paymentDate": payment_date,
                        "delayDays": 14, "alternativeTermMonths": 24, "selectedTermMonths": 12,
                    }
                elif method == "PUT" and template.endswith("/payment-reserve"):
                    body = {
                        "openingBalance": 1_900_000, "expectedReceiptAmount": 140_000,
                        "payableAmount": 2_000_000, "targetBuffer": 30_000,
                        "controlDate": control_date, "paymentDate": payment_date,
                    }
                elif template.endswith("/payment-reserve/receipt-event"):
                    body = {"eventId": str(uuid4()), "status": "DELAYED"}
                elif template.endswith("/payment-reserve/confirm-draw"):
                    body = {"expectedVersion": 1}
                elif method in {"POST", "PUT"} and template in {"/api/v1/agent/applications", "/api/v1/agent/applications/{id}"}:
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
                    body, headers = {"amount": 100_000, "purpose": "Синтетическая проверка доступа"}, {"Idempotency-Key": str(uuid4())}
                elif template.endswith("/payments/next"):
                    body, headers = {"paymentId": missing}, {"Idempotency-Key": str(uuid4())}
                elif template.endswith("/messages") and method == "POST":
                    body, headers = {"text": "Синтетическая проверка доступа"}, {"Idempotency-Key": str(uuid4())}
                elif template.endswith("/messages/read"):
                    body = {"lastReadSeq": 1}
                elif template.endswith("/next-steps"):
                    route += "?applicationId=" + missing
                elif template.endswith("/reminders"):
                    body = {"applicationId": missing, "infoRequestId": str(uuid4())}
                elif template.endswith("/stream"):
                    body, headers = {"question": "Дай юридический совет"}, {"Accept": "text/event-stream"}
                if method == "POST" and template.endswith(("/documents", "/messages/attachments", "/invoice-extraction")):
                    upload_route = route + "?type=TAX_RETURN" if template.endswith("/documents") else route
                    response = api._upload(token, upload_route, pdf, "synthetic.pdf", None, {"Idempotency-Key": str(uuid4())})
                else:
                    response = api.request(method, route, body, token, expected=None, headers=headers)
                checks.append({"service": service, "method": method, "path": template, "role": role,
                               "expected": expected, "actual": response["status"], "duration_ms": response["duration_ms"]})
                if template == MANDATES:
                    if method == "GET" and response["status"] == 200:
                        assert response["body"] == [], "Новая синтетическая компания получила чужие мандаты"
                    elif method == "POST" and expected == 201:
                        assert response["status"] == 201, f"Мандат для {role} не выдан: HTTP {response['status']}"
                        grant = response["body"]
                        issued_mandates[role] = grant["id"]
                        assert grant["agentUserId"] == body["agentUserId"] and grant["status"] == "ACTIVE"
                if expected == 200 and template.endswith("/stream"):
                    assert "event: answer" in response["body"] and "event: failure" not in response["body"], "Ответ SSE не завершён"
        failures = [row for row in checks if row["actual"] != row["expected"]]
        assert not failures, f"Несовпадения в новых маршрутах: {json.dumps(failures[:12], ensure_ascii=False)}"
        for role, own_id in issued_mandates.items():
            expected_status = "REVOKED" if role == "client" else "ACTIVE"
            grants = api.json("GET", MANDATES, token=mandate_tokens[role])
            assert [(grant["id"], grant["status"]) for grant in grants] == [(own_id, expected_status)]
        completed = True
    finally:
        for role, grant_id in issued_mandates.items():
            try:
                api.request("DELETE", f"{MANDATES}/{grant_id}", token=mandate_tokens[role], expected=(204,))
            except Exception as error:
                cleanup_errors.append(str(error))
        reports = ROOT / "security/reports"
        reports.mkdir(exist_ok=True)
        expected = len(ROUTES) * len(ROLES)
        outcome = {"checked_at": datetime.now(timezone.utc).isoformat(), "operations": len(ROUTES), "roles": ROLES,
                   "checks": checks, "expected_checks": expected, "cleanup_errors": cleanup_errors,
                   "passed": completed and not cleanup_errors and len(checks) == expected}
        (reports / "final-access-matrix.json").write_text(json.dumps(outcome, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if completed and cleanup_errors:
            raise AssertionError("Не завершена очистка мандатов: " + "; ".join(cleanup_errors))
    print(f"Новые маршруты: {len(checks)}/{expected}", flush=True)
