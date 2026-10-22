#!/usr/bin/env python3
"""Exercise object isolation, token integrity, content validation and real rate limits."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import hmac
import json
from pathlib import Path
import sys
import time
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet  # noqa: E402
from gateway_rate import probe as probe_gateway  # noqa: E402

parser = argparse.ArgumentParser(description="Негативные проверки на отдельном локальном синтетическом стенде")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-submission")
args = parser.parse_args()
api = DockerCabinet(args.base, args.project)
directory = ROOT / "security/reports"
directory.mkdir(exist_ok=True)
pdf = (ROOT / "contracts/postman/fixtures/synthetic.pdf").read_bytes()
checks = []
created = []
measurements = {}
completed = False
failure = None
run = uuid4().hex[:12]


def expect(name, response, status):
    checks.append({"name": name, "expected": status, "actual": response["status"], "passed": response["status"] == status})
    assert response["status"] == status, f"{name}: HTTP {response['status']} вместо {status}"
    return response["body"]


def rule(name, condition):
    checks.append({"name": name, "passed": bool(condition)})
    assert condition, name


def encode(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def decode(value):
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def rewritten(token, changes, key=None):
    header, payload, signature = token.split(".")
    claims = json.loads(decode(payload))
    claims.update(changes)
    payload = encode(json.dumps(claims, separators=(",", ":"), ensure_ascii=False).encode())
    if key is not None:
        algorithm = json.loads(decode(header))["alg"]
        digest = {"HS256": hashlib.sha256, "HS384": hashlib.sha384, "HS512": hashlib.sha512}[algorithm]
        signature = encode(hmac.new(key.encode(), f"{header}.{payload}".encode(), digest).digest())
    return f"{header}.{payload}.{signature}"


def close(app_id, token):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        app = api.json("GET", f"/api/v1/applications/{app_id}", token=token)
        if app["status"] in {"WITHDRAWN", "CLOSED", "DISBURSED", "REJECTED"}:
            return
        if "WITHDRAW" in app["allowedActions"]:
            if api.request("POST", f"/api/v1/applications/{app_id}/withdraw", token=token, expected=(200, 409))["status"] == 200:
                return
        time.sleep(0.2)
    raise AssertionError(f"Не закрыта синтетическая заявка {app_id}")


try:
    time.sleep(2.1)
    owner = api.register(f"negative-owner-{run}")
    time.sleep(2.1)
    other = api.register(f"negative-other-{run}")
    time.sleep(2.1)
    underwriter = api.sign_in("underwriter@demo.test")["token"]
    token = owner["token"]
    stranger = other["token"]
    key = api.test_secret("credit", "JWT_SECRET")
    expect("Исходный JWT действителен", api.request("GET", "/api/v1/users/me", token=token, expected=None), 200)
    expect("Контроль повторной подписи тестовым ключом", api.request("GET", "/api/v1/users/me", token=rewritten(token, {}, key), expected=None), 200)
    for name, value in [
        ("Изменённая роль без подписи", rewritten(token, {"role": "RISK_ADMIN"})),
        ("Просроченный правильно подписанный JWT", rewritten(token, {"exp": int(time.time()) - 3600}, key)),
        ("Поддельная подпись", token.rsplit(".", 1)[0] + "." + encode(b"x" * 32)),
        ("Алгоритм none", encode(b'{"alg":"none"}') + "." + token.split(".")[1] + "."),
    ]:
        for route in ["/api/v1/users/me", "/api/v1/applications", "/api/v1/notifications"]:
            expect(f"{name}: {route}", api.request("GET", route, token=value, expected=None), 401)
    expect("Роль в заголовке не заменяет JWT", api.request("GET", "/api/v1/rules", token=token,
           headers={"X-Role": "RISK_ADMIN"}, expected=None), 403)
    basic = base64.b64encode(b"manager@demo.test:Demo2026").decode()
    expect("HTTP Basic выключен", api.request("GET", "/api/v1/users/me", headers={"Authorization": "Basic " + basic}, expected=None), 401)

    company = api.json("GET", "/api/v1/companies/me", token=token)
    app = api.create(token, revenue=999_000_000)
    app_id = app["id"]
    created.append((app_id, token))
    route = f"/api/v1/applications/{app_id}"
    expect("IDOR заявки", api.request("GET", route, token=stranger, expected=None), 404)
    expect("Компания в заголовке не заменяет JWT", api.request("GET", route, token=stranger,
           headers={"X-Company-Id": company["id"]}, expected=None), 404)
    other_app = expect("Компания в JSON не назначает чужого владельца", api.request("POST", "/api/v1/applications", {
        "companyId": company["id"], "productCode": "WORKING_CAPITAL", "purpose": "WORKING_CAPITAL", "amount": 1_000_000,
        "termMonths": 12, "declaredRevenue12m": 999_000_000, "declaredMonthlyDebtPayments": 0, "bureauConsent": True,
    }, stranger, expected=None), 201)
    created.append((other_app["id"], stranger))
    expect("Созданная заявка принадлежит второму клиенту", api.request("GET", f"/api/v1/applications/{other_app['id']}", token=stranger, expected=None), 200)
    expect("Первый клиент не видит её по переданному companyId", api.request("GET", f"/api/v1/applications/{other_app['id']}", token=token, expected=None), 404)

    expect("Исполняемое содержимое под видом PDF", api.upload(token, app_id, "OTHER", b"MZ synthetic executable", expected=None), 400)
    rule("Поддельный PDF не сохранён", not api.json("GET", route, token=token)["documents"])
    document = expect("Собственный синтетический PDF принят", api.upload(token, app_id, "OTHER", pdf, expected=None), 201)
    doc_id = document["id"]
    expect("IDOR документа", api.request("GET", route + f"/documents/{doc_id}", token=stranger, expected=None), 404)
    expect("Чужой документ через свою заявку", api.request("GET", f"/api/v1/applications/{other_app['id']}/documents/{doc_id}", token=stranger, expected=None), 404)
    expect("IDOR помощника", api.request("POST", "/api/v1/assistant/messages", {
        "question": "Какой статус у этой заявки?", "applicationId": app_id,
    }, stranger, expected=None), 404)

    notification = {"recipientId": owner["user_id"], "type": "APPLICATION_STATUS", "title": f"Проверка безопасности {run}",
                    "body": "Синтетическое уведомление", "link": "/client", "dedupKey": "negative-" + run}
    expect("Подготовка личного уведомления", api.service("engagement", "POST", "/internal/v1/notifications", {"items": [notification]}, expected=None), 200)
    page = api.json("GET", "/api/v1/notifications", token=token)
    notification_id = next(item["id"] for item in page["items"] if item["title"] == notification["title"])
    expect("IDOR уведомления", api.request("POST", f"/api/v1/notifications/{notification_id}/read", token=stranger, expected=None), 404)
    rule("Список другого получателя не раскрывает уведомление", all(item["id"] != notification_id for item in api.json("GET", "/api/v1/notifications", token=stranger)["items"]))
    expect("Владелец может прочитать уведомление", api.request("POST", f"/api/v1/notifications/{notification_id}/read", token=token, expected=None), 204)
    for link in ["https://evil.example", "//evil.example", "javascript:alert(1)", "/\\evil.example"]:
        expect("Внешняя ссылка отклонена: " + link, api.service("engagement", "POST", "/internal/v1/notifications", {
            "items": [{**notification, "link": link, "dedupKey": uuid4().hex}],
        }, expected=None), 400)
    rule("Отклонённые ссылки не создали уведомления", api.json("GET", "/api/v1/notifications", token=token)["total"] == page["total"])

    for active_token in [None, token]:
        for path in [f"/internal/v1/users/{owner['user_id']}", f"/ext/registry/v1/companies/{owner['inn']}"]:
            expect("Закрытый путь шлюза " + path, api.request("GET", path, token=active_token, expected=None), 404)
        expect("Внутренняя доставка не опубликована", api.request("POST", "/internal/v1/notifications", {"items": [notification]}, active_token, expected=None), 404)
    for label, headers, expected in [("без ключа", {}, 401), ("неверный ключ", {"X-Api-Key": "wrong-test-key"}, 401),
                                     ("правильный ключ", {"X-Api-Key": api.test_secret("external-sim", "SIM_API_KEY")}, 200)]:
        expect("Системный API: " + label, api.service("external-sim", "GET", f"/ext/registry/v1/companies/{owner['inn']}", headers=headers, expected=None), expected)

    for path in ["/", "/sign-in", "/api/v1/products"]:
        response = api.request("GET", path)
        headers = {name.lower(): value for name, value in response["headers"].items()}
        rule("nosniff: " + path, "nosniff" in headers.get("x-content-type-options", ""))
        rule("Referrer-Policy: " + path, headers.get("referrer-policy") == "strict-origin-when-cross-origin")
        if not path.startswith("/api/"):
            csp = headers.get("content-security-policy", "")
            rule("CSP собственные скрипты: " + path, "script-src 'self'" in csp and "object-src 'none'" in csp)
            rule("Запрет встраивания: " + path, headers.get("x-frame-options") == "DENY" and "frame-ancestors 'none'" in csp)

    api.json("POST", route + "/submit", token=token, headers={"Idempotency-Key": "negative-" + app_id})
    api.wait(token, app_id, {"MANUAL_REVIEW"}, timeout=90)
    api.json("POST", f"/api/v1/underwriting/applications/{app_id}/approve", {"comment": "Синтетическая проверка права подписи: расхождение проверено"}, underwriter)
    accepted = api.json("POST", route + "/offer/accept", token=token)
    code = accepted["process"]["signing"]["code"]
    no_sign = rewritten(token, {"sign": False}, key)
    expect("Нельзя подписать без подписанного права sign", api.request("POST", route + "/sign", {"code": code}, no_sign, expected=None), 403)
    expect("Нельзя получить код без подписанного права sign", api.request("POST", route + "/signing/code", token=no_sign, expected=None), 403)
    rule("Сервер скрывает действие SIGN", "SIGN" not in api.json("GET", route, token=no_sign)["allowedActions"])
    expect("Уполномоченный владелец подписывает тем же кодом", api.request("POST", route + "/sign", {"code": code}, token, expected=None), 200)
    api.wait(token, app_id, {"DISBURSED"}, timeout=90)

    upload_app = api.create(token)
    created.append((upload_app["id"], token))
    upload_codes = []
    replicas = len(api.containers("credit"))
    for _ in range(30 * replicas + 5):
        response = api.upload(token, upload_app["id"], "OTHER", pdf, expected=None)
        upload_codes.append(response["status"])
        if response["status"] == 429:
            break
        assert response["status"] == 201, response["status"]
    rule("Ограничение загрузок с учётом числа экземпляров", upload_codes[-1] == 429)
    measurements["uploads"] = {"credit_replicas": replicas, "prior_valid_uploads": 1, "prior_invalid_attempts": 1,
                               "accepted_in_probe": upload_codes.count(201), "limit_response": upload_codes[-1]}

    assistant_codes = []
    for _ in range(25):
        response = api.request("POST", "/api/v1/assistant/messages", {"question": "Какой статус у заявки?", "applicationId": upload_app["id"]}, token, expected=None)
        assistant_codes.append(response["status"])
        if response["status"] == 429:
            break
        assert response["status"] == 200, response["status"]
    rule("Ограничение вопросов помощнику", assistant_codes.count(200) == 20 and assistant_codes[-1] == 429)
    measurements["assistant"] = {"accepted": assistant_codes.count(200), "limit_response": assistant_codes[-1]}

    login_codes = []
    for _ in range(6):
        response = api.service("identity", "POST", "/api/v1/auth/sign-in", {"email": owner["email"], "password": "Wrong2026safe"}, expected=None)
        login_codes.append(response["status"])
    rule("Пять неверных паролей блокируют шестую попытку", login_codes == [401]*5 + [429])
    measurements["login_account"] = {"statuses": login_codes}
    measurements["login_gateway"] = probe_gateway(api)
    rule("Шлюз ограничивает поток и возвращает русскую JSON-ошибку", measurements["login_gateway"]["limit_response"] == 429)
    completed = True
except Exception as error:
    failure = str(error)
    raise
finally:
    cleanup_errors = []
    for app_id, token in created:
        try:
            close(app_id, token)
        except Exception as error:
            cleanup_errors.append(str(error))
    summary = {"checked_at": datetime.now(timezone.utc).isoformat(), "checks": checks, "measurements": measurements,
               "failure": failure, "cleanup_errors": cleanup_errors, "passed": completed and not cleanup_errors and all(check["passed"] for check in checks)}
    (directory / "negative.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"checks": len(checks), "measurements": measurements, "passed": summary["passed"], "failure": failure}, ensure_ascii=False, indent=2))
    time.sleep(6)
    if completed and cleanup_errors:
        raise AssertionError("Не завершено восстановление стенда: " + "; ".join(cleanup_errors))
