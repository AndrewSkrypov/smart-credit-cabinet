#!/usr/bin/env python3
"""Compare an explicit role policy to every OpenAPI operation and the live cabinet."""
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sys
import time
from urllib.parse import urlparse
from uuid import uuid4
import yaml

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet, synthetic_inn  # noqa: E402

parser = argparse.ArgumentParser(description="Полная матрица ролей и владения объектами")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-submission")
parser.add_argument("--check-only", action="store_true", help="Проверить полноту таблицы без обращения к стенду")
parser.add_argument("--write-table", action="store_true", help="Обновить читаемую таблицу из access-policy.json без обращения к стенду")
args = parser.parse_args()
policy = json.loads((ROOT / "security/access-policy.json").read_text(encoding="utf-8"))
roles = policy["roles"]
declared = set()
for service, method, path, fixture, statuses in policy["endpoints"]:
    key = (service, method, path)
    assert key not in declared, f"Повтор строки: {key}"
    assert len(statuses) == len(roles) and all(code in {200, 201, 204, 401, 403, 404, 410, 503} for code in statuses)
    declared.add(key)
actual = set()
for file in sorted((ROOT / "contracts/openapi").glob("*.yaml")):
    contract = yaml.safe_load(file.read_text(encoding="utf-8"))
    for path, methods in contract["paths"].items():
        for method in methods:
            if method.upper() in {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}:
                actual.add((file.stem, method.upper(), path))
assert declared == actual, f"Матрица не совпадает с OpenAPI: отсутствуют {actual-declared}; лишние {declared-actual}"
table = ["# Матрица ожидаемого доступа", "", "Источник ожиданий — [access-policy.json](access-policy.json). Полнота проверяется по четырём OpenAPI.", ""]
table.extend(f"- {note}" for note in policy["notes"])
table.extend(["", "| Сервис | Операция | " + " | ".join(policy["role_titles"]) + " |",
              "| --- | --- | " + " | ".join(["---"]*len(roles)) + " |"])
for service, method, path, fixture, statuses in policy["endpoints"]:
    table.append(f"| {service} | `{method} {path}` | " + " | ".join(map(str, statuses)) + " |")
table_text = "\n".join(table) + "\n"
table_file = ROOT / "security/access-matrix.md"
if args.write_table:
    table_file.write_text(table_text, encoding="utf-8")
else:
    assert table_file.read_text(encoding="utf-8").replace("\r\n", "\n") == table_text, "Обновите таблицу: python security/matrix.py --write-table"
print(f"Полнота: {len(actual)} операций × {len(roles)} ролей = {len(actual)*len(roles)} проверок", flush=True)
if args.check_only or args.write_table:
    raise SystemExit(0)
if not re.fullmatch(r"cabinet-(?:submission|reliability|e2e|release|polish)(?:-[a-z0-9]+)?", args.project):
    parser.error("Разрешён только отдельный синтетический тестовый проект")
if urlparse(args.base).hostname not in {"localhost", "127.0.0.1", "::1"}:
    parser.error("HTTP-стенд должен быть локальным; внешние серверы этой проверкой не изменяются")

api = DockerCabinet(args.base, args.project)
reports = ROOT / "security/reports"
reports.mkdir(exist_ok=True)
pdf = (ROOT / "contracts/postman/fixtures/synthetic.pdf").read_bytes()
created = []
rows = []
extra_checks = []
completed = False
failure = None
original_rules = None
original_products = []
original_modes = api.json("GET", "/sim/control")["sources"]
run = uuid4().hex[:12]


def internal(service, method, path, body):
    return api.service(service, method, path, body)["body"]


def close(app_id, token):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        app = api.json("GET", f"/api/v1/applications/{app_id}", token=token)
        if app["status"] in {"DISBURSED", "REJECTED", "WITHDRAWN", "CLOSED"}:
            return
        if "WITHDRAW" in app["allowedActions"]:
            response = api.request("POST", f"/api/v1/applications/{app_id}/withdraw", token=token, expected=(200, 409))
            if response["status"] == 200:
                return
        time.sleep(0.2)
    raise AssertionError(f"Не закрыта синтетическая заявка {app_id}")


def draft(token):
    app = api.create(token, revenue=999_000_000)
    created.append((app["id"], token))
    return app


def prepare(kind):
    if kind in {"company_invitation", "employee_access"}:
        invitation = api.json("POST", "/api/v1/companies/me/invitations", {"email": f"matrix-employee-{uuid4().hex}@example.test", "canSign": False}, tokens["owner"], headers={"Idempotency-Key": str(uuid4())})
        if kind == "company_invitation":
            return {"id": invitation["id"]}
        assert invitation.get("link"), "Матрица приглашений требует выключенного почтового профиля"
        employee = api.json("POST", "/api/v1/auth/invitations/accept", {"token": invitation["link"].split("#token=", 1)[1], "fullName": "Синтетический сотрудник", "password": "Test2026safe", "personalDataConsent": True})
        assert not employee["user"]["canSign"]
        for method, path, body in [
            ("POST", "/api/v1/companies/me/invitations", {"email": f"denied-{uuid4().hex}@example.test"}),
            ("DELETE", f"/api/v1/companies/me/employees/{employee['user']['id']}/access", None),
        ]:
            response = api.request(method, path, body, employee["token"], headers={"Idempotency-Key": str(uuid4())}, expected=(403,))
            extra_checks.append({"fixture": "non_signer", "method": method, "path": path, "expected": 403, "actual": response["status"]})
        return {"id": employee["user"]["id"], "employee_token": employee["token"]}
    if kind not in {"draft", "draft_document", "submitted", "submitted_document", "manual", "info", "offer", "signing", "artifact", "chat_read", "chat_upload", "chat_send"}:
        return {}
    app = draft(tokens["owner"])
    app_id = app["id"]
    result = {"id": app_id}
    if kind in {"draft_document", "submitted_document"}:
        result["documentId"] = api.upload(tokens["owner"], app_id, "OTHER", pdf)["body"]["id"]
    if kind.startswith("draft"):
        return result
    api.json("POST", f"/api/v1/applications/{app_id}/submit", token=tokens["owner"], headers={"Idempotency-Key": f"matrix-{app_id}"})
    api.wait(tokens["owner"], app_id, {"MANUAL_REVIEW"}, timeout=90)
    if kind == "chat_read":
        api.json("POST", f"/api/v1/applications/{app_id}/messages", {"text": "Синтетическое сообщение для проверки курсора"},
                 tokens["owner"], headers={"Idempotency-Key": str(uuid4())})
    if kind == "info":
        api.json("POST", f"/api/v1/underwriting/applications/{app_id}/request-info", {
            "items": [{"documentType": "OTHER"}, {"question": "Поясните синтетическую выручку"}], "comment": "Подготовка проверки прав",
        }, tokens["underwriter"])
        api.upload(tokens["owner"], app_id, "OTHER", pdf)
    if kind in {"offer", "signing", "artifact"}:
        api.json("POST", f"/api/v1/underwriting/applications/{app_id}/approve", {
            "comment": "Синтетическая проверка прав: расхождение рассмотрено специалистом",
        }, tokens["underwriter"])
    if kind == "signing":
        app = api.json("POST", f"/api/v1/applications/{app_id}/offer/accept", token=tokens["owner"])
        result["code"] = app["process"]["signing"]["code"]
    if kind == "artifact":
        deadline = time.monotonic() + 90
        while True:
            files = api.json("GET", f"/api/v1/applications/{app_id}/artifacts", token=tokens["owner"])
            ready = next((item for item in files if item["kind"] == "SCHEDULE_PDF" and item["status"] == "READY"), None)
            if ready:
                result["artifactId"] = ready["id"]
                break
            if time.monotonic() >= deadline:
                raise AssertionError("Файл матрицы доступа не подготовлен")
            time.sleep(0.5)
    return result


def invoke(method, template, kind, role, fixture):
    token = tokens.get(role)
    body = None
    parameters = {"id": fixture.get("id", str(uuid4())), "documentId": fixture.get("documentId", str(uuid4())), "artifactId": fixture.get("artifactId", str(uuid4())),
                  "version": 1, "code": "WORKING_CAPITAL", "inn": "000100000004", "applicationId": str(uuid4()), "source": "BUREAU"}
    if kind == "notification":
        parameters["id"] = notifications["owner" if role in {"anonymous", "owner", "other_client"} else role]
    route = template.format(**parameters)
    if kind == "analytics":
        now = datetime.now(timezone.utc)
        route += f"?from={(now-timedelta(days=1)).isoformat().replace('+00:00','Z')}&to={(now+timedelta(hours=1)).isoformat().replace('+00:00','Z')}"
    if kind == "calculation":
        route += "?amount=1000000&termMonths=12"
    if template == "/api/v1/products/match":
        body = {"purpose": "WORKING_CAPITAL", "amount": 1_000_000, "termMonths": 12}
    if template == "/api/v1/applications" and method == "POST" or method == "PUT" and template == "/api/v1/applications/{id}":
        body = {"productCode": "WORKING_CAPITAL", "purpose": "WORKING_CAPITAL", "amount": 1_000_000, "termMonths": 12,
                "declaredRevenue12m": 999_000_000, "declaredMonthlyDebtPayments": 0, "bureauConsent": True}
    if template.endswith("/counter-offer"):
        body = {"amount": 900_000, "termMonths": 12, "comment": "Синтетическая матрица доступа: обоснованные другие условия"}
    if template.endswith(("/approve", "/reject")):
        body = {"comment": "Синтетическая матрица доступа: обоснованное решение специалиста"}
    if template.endswith("/request-info"):
        body = {"items": [{"question": "Уточните синтетическую выручку"}], "comment": "Проверка прав"}
    if template.endswith("/info-provided"):
        body = {"answer": "Выручка синтетическая, указана для проверки прав"}
    if template.endswith("/sign"):
        body = {"code": fixture.get("code", "123456")}
    if template == "/api/v1/rules" and method == "POST":
        current = api.json("GET", "/api/v1/rules", token=tokens["risk_admin"])
        params = {**current["params"], "maxLoad": round(current["params"]["maxLoad"] + (0.01 if current["params"]["maxLoad"] < 0.8 else -0.01), 2)}
        body = {"params": params, "expectedVersion": current["version"], "comment": "Синтетическая проверка права публикации с восстановлением порогов"}
    if kind == "product_edit":
        product = api.json("GET", "/api/v1/bank/products/WORKING_CAPITAL", token=tokens["risk_admin"])[0]["product"]
        body = {**product, "name": product["name"][:240] + " [матрица]", "expectedVersion": product["version"], "reason": "Синтетическая проверка права публикации с восстановлением условий"}
    if template == "/api/v1/assistant/messages":
        body = {"question": "Какой статус у этой заявки?", "applicationId": fixture["id"], "history": []}
    if kind == "sim_mode":
        body = {"mode": "OK"}
    if kind == "notification_settings" and method == "PUT":
        preference = api.json("GET", template, token=token) if token else {"emailEnabled": False, "version": 0}
        body = {"emailEnabled": preference["emailEnabled"], "version": preference["version"]}
    if kind in {"sign_up", "sign_in"}:
        time.sleep(2.1)
        if kind == "sign_in":
            body = {"email": owner["email"], "password": "Test2026safe"}
        else:
            body = {"inn": synthetic_inn(), "email": f"matrix-{uuid4().hex}@example.test", "password": "Test2026safe",
                    "fullName": "Представитель синтетической компании", "personalDataConsent": True}
    if kind == "password_recovery":
        time.sleep(2.1)
        body = {"email": f"matrix-recovery-{uuid4().hex}@example.test"}
    if kind == "invalid_link":
        time.sleep(2.1)
        body = {"token": "00000000-0000-0000-0000-000000000000." + "x"*43, "password": "Test2026safe", "fullName": "Синтетический сотрудник", "personalDataConsent": True}
    if kind == "password_change":
        time.sleep(2.1)
        password = "Test2026safe" if role in {"owner", "other_client", "anonymous"} else "Demo2026"
        body = {"currentPassword": password, "password": password}
    if kind == "logout_all":
        time.sleep(2.1)
    if kind == "profile_update":
        # The participant's own unchanged name and email: access is checked, the account stays as it was.
        current = api.request("GET", "/api/v1/users/me", None, token, expected=None)["body"] or {}
        body = {"fullName": current.get("fullName", "Синтетический участник"), "email": current.get("email", "matrix-anonymous@example.test")}
    if kind == "invite":
        body = {"email": f"matrix-invite-{uuid4().hex}@example.test", "canSign": False}
    if template.endswith("/documents"):
        return api.upload(token, fixture["id"], "OTHER", pdf, expected=None)
    if kind == "chat_upload":
        return api.upload_chat(token, fixture["id"], pdf, key=str(uuid4()), expected=None)
    if kind == "chat_send":
        body = {"text": "Синтетическая проверка доступа к переписке"}
    if kind == "chat_read":
        body = {"lastReadSeq": 1}
    headers = {"Idempotency-Key": str(uuid4())} if kind in {"invite", "company_invitation", "employee_access", "chat_send"} else None
    response = api.request(method, route, body, token, expected=None, headers=headers)
    if kind in {"password_change", "logout_all"} and response["status"] == 200:
        time.sleep(2.1)
        email = owner["email"] if role == "owner" else other["email"] if role == "other_client" else {"manager": "manager@demo.test", "underwriter": "underwriter@demo.test", "risk_admin": "risk@demo.test"}[role]
        password = "Test2026safe" if role in {"owner", "other_client"} else "Demo2026"
        tokens[role] = api.json("POST", "/api/v1/auth/sign-in", {"email": email, "password": password})["token"]
    if kind in {"invite", "company_invitation"} and method == "POST" and response["status"] == 200:
        api.json("DELETE", f"/api/v1/companies/me/invitations/{response['body']['id']}", token=token, headers={"Idempotency-Key": str(uuid4())})
    if kind == "create" and response["status"] == 201:
        created.append((response["body"]["id"], token))
    if template == "/api/v1/applications" and method == "GET" and role == "other_client":
        assert all(app["id"] != fixture["id"] for app in response["body"]), "Список другого клиента содержит чужую заявку"
    return response


try:
    time.sleep(2.1)
    owner = api.register(f"matrix-owner-{run}")
    time.sleep(2.1)
    other = api.register(f"matrix-other-{run}")
    sessions = {"owner": owner, "other_client": other}
    for role, email in [("manager", "manager@demo.test"), ("underwriter", "underwriter@demo.test"), ("risk_admin", "risk@demo.test")]:
        time.sleep(2.1)
        sessions[role] = api.sign_in(email)
    tokens = {role: session["token"] for role, session in sessions.items()}
    original_rules = api.json("GET", "/api/v1/rules", token=tokens["risk_admin"])["params"]
    original_products = api.json("GET", "/api/v1/bank/products", token=tokens["risk_admin"])
    notifications = {}
    for role, session in sessions.items():
        user_id = session.get("user_id") or session["user"]["id"]
        key = f"matrix-{run}-{role}"
        internal("engagement", "POST", "/internal/v1/notifications", {"items": [{
            "recipientId": user_id, "type": "APPLICATION_STATUS", "title": "Проверка прав",
            "body": "Синтетическое уведомление матрицы", "link": "/client", "dedupKey": key,
        }]})
        notifications[role] = next(item["id"] for item in api.json("GET", "/api/v1/notifications", token=tokens[role])["items"] if item["title"] == "Проверка прав")
    for service, method, template, kind, statuses in policy["endpoints"]:
        fixture = prepare(kind)
        for role, expected in zip(roles, statuses):
            if expected in {200, 201, 204} and method not in {"GET", "HEAD"} and kind == "manual" and fixture.get("used"):
                close(fixture["id"], tokens["owner"])
                fixture = prepare(kind)
            response = invoke(method, template, kind, role, fixture)
            row = {"service": service, "method": method, "path": template, "role": role,
                   "expected": expected, "actual": response["status"], "duration_ms": response["duration_ms"]}
            rows.append(row)
            assert response["status"] == expected, f"{service}: {method} {template} / {role}: {response['status']} вместо {expected}"
            if method not in {"GET", "HEAD"} and response["status"] in {200, 201, 204}:
                fixture["used"] = True
        if kind == "rules":
            current = api.json("GET", "/api/v1/rules", token=tokens["risk_admin"])
            api.json("POST", "/api/v1/rules", {"params": original_rules, "expectedVersion": current["version"], "comment": "Восстановление после проверки прав"}, tokens["risk_admin"], expected=(201,))
        if kind == "employee_access":
            for path in ["/api/v1/users/me", "/api/v1/applications", "/api/v1/notifications"]:
                response = api.request("GET", path, token=fixture["employee_token"], expected=(401,))
                extra_checks.append({"fixture": "revoked", "method": "GET", "path": path, "expected": 401, "actual": response["status"]})
            response = api.request("DELETE", f"/api/v1/companies/me/employees/{owner['user_id']}/access", token=tokens["owner"], headers={"Idempotency-Key": str(uuid4())}, expected=(403,))
            extra_checks.append({"fixture": "self_revoke", "expected": 403, "actual": response["status"]})
        if fixture.get("id") and kind not in {"company_invitation", "employee_access"}:
            close(fixture["id"], tokens["owner"])
        if kind == "create":
            for app_id, token in created:
                close(app_id, token)
        print(f"Проверено {len(rows)}/{len(actual)*len(roles)}: {method} {template}", flush=True)
    assert api.json("GET", "/api/v1/rules", token=tokens["risk_admin"])["params"] == original_rules
    completed = True
except Exception as error:
    failure = str(error)
    raise
finally:
    cleanup_errors = []
    if original_rules is not None:
        try:
            current = api.json("GET", "/api/v1/rules", token=tokens["risk_admin"])
            if current["params"] != original_rules:
                restored = api.json("POST", "/api/v1/rules", {"params": original_rules, "expectedVersion": current["version"], "comment": "Восстановление после матрицы доступа"}, tokens["risk_admin"], expected=(201,))
                assert restored["params"] == original_rules, "Пороги правил не восстановлены"
        except Exception as error:
            cleanup_errors.append(str(error))
    for original in original_products:
        try:
            current = api.json("GET", f"/api/v1/bank/products/{original['code']}", token=tokens["risk_admin"])[0]["product"]
            if {k:v for k,v in current.items() if k != "version"} != {k:v for k,v in original.items() if k != "version"}:
                api.json("PUT", f"/api/v1/bank/products/{original['code']}", {**original, "expectedVersion": current["version"], "reason": "Восстановление после матрицы доступа"}, tokens["risk_admin"])
        except Exception as error:
            cleanup_errors.append(str(error))
    for source in original_modes:
        try:
            api.json("PUT", f"/sim/control/sources/{source['source']}", {"mode": source["mode"]})
        except Exception as error:
            cleanup_errors.append(str(error))
    for app_id, token in created:
        try:
            close(app_id, token)
        except Exception as error:
            cleanup_errors.append(str(error))
    summary = {"checked_at": datetime.now(timezone.utc).isoformat(), "operations": len(actual), "roles": roles,
               "expected_checks": len(actual)*len(roles), "checks": rows,
               "failure": failure, "cleanup_errors": cleanup_errors, "extra_checks": extra_checks,
               "passed": completed and not cleanup_errors and len(rows) == len(actual)*len(roles) and all(row["expected"] == row["actual"] for row in rows)}
    (reports / "access-matrix.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    if completed and cleanup_errors:
        raise AssertionError("Не завершено восстановление стенда: " + "; ".join(cleanup_errors))
