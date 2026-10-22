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
    assert len(statuses) == len(roles) and all(code in {200, 201, 204, 401, 403, 404} for code in statuses)
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
if not re.fullmatch(r"cabinet-(?:submission|reliability|e2e)(?:-[a-z0-9]+)?", args.project):
    parser.error("Разрешён только отдельный синтетический тестовый проект")
if urlparse(args.base).hostname not in {"localhost", "127.0.0.1", "::1"}:
    parser.error("HTTP-стенд должен быть локальным; внешние серверы этой проверкой не изменяются")

api = DockerCabinet(args.base, args.project)
reports = ROOT / "security/reports"
reports.mkdir(exist_ok=True)
pdf = (ROOT / "contracts/postman/fixtures/synthetic.pdf").read_bytes()
created = []
rows = []
completed = False
failure = None
original_rules = None
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
    if kind not in {"draft", "draft_document", "submitted", "submitted_document", "manual", "info", "offer", "signing"}:
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
    if kind == "info":
        api.json("POST", f"/api/v1/underwriting/applications/{app_id}/request-info", {
            "items": [{"documentType": "OTHER"}, {"question": "Поясните синтетическую выручку"}], "comment": "Подготовка проверки прав",
        }, tokens["underwriter"])
        api.upload(tokens["owner"], app_id, "OTHER", pdf)
    if kind in {"offer", "signing"}:
        api.json("POST", f"/api/v1/underwriting/applications/{app_id}/approve", {
            "comment": "Синтетическая проверка прав: расхождение рассмотрено специалистом",
        }, tokens["underwriter"])
    if kind == "signing":
        app = api.json("POST", f"/api/v1/applications/{app_id}/offer/accept", token=tokens["owner"])
        result["code"] = app["process"]["signing"]["code"]
    return result


def invoke(method, template, kind, role, fixture):
    token = tokens.get(role)
    body = None
    parameters = {"id": fixture.get("id", str(uuid4())), "documentId": fixture.get("documentId", str(uuid4())),
                  "code": "WORKING_CAPITAL", "inn": "000100000004", "applicationId": str(uuid4()), "source": "BUREAU"}
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
        body = {"params": original_rules, "comment": "Проверка права публикации без изменения порогов"}
    if template == "/api/v1/assistant/messages":
        body = {"question": "Какой статус у этой заявки?", "applicationId": fixture["id"], "history": []}
    if kind == "sim_mode":
        body = {"mode": "OK"}
    if kind in {"sign_up", "sign_in"}:
        time.sleep(2.1)
        if kind == "sign_in":
            body = {"email": owner["email"], "password": "Test2026safe"}
        else:
            body = {"inn": synthetic_inn(), "email": f"matrix-{uuid4().hex}@example.test", "password": "Test2026safe",
                    "fullName": "Представитель синтетической компании", "personalDataConsent": True}
    if template.endswith("/documents"):
        return api.upload(token, fixture["id"], "OTHER", pdf, expected=None)
    response = api.request(method, route, body, token, expected=None)
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
        if fixture.get("id"):
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
                restored = api.json("POST", "/api/v1/rules", {"params": original_rules, "comment": "Восстановление после матрицы доступа"}, tokens["risk_admin"], expected=(201,))
                assert restored["params"] == original_rules, "Пороги правил не восстановлены"
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
               "failure": failure, "cleanup_errors": cleanup_errors,
               "passed": completed and not cleanup_errors and len(rows) == len(actual)*len(roles) and all(row["expected"] == row["actual"] for row in rows)}
    (reports / "access-matrix.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    if completed and cleanup_errors:
        raise AssertionError("Не завершено восстановление стенда: " + "; ".join(cleanup_errors))
