"""Private dev-only credentials and a synthetic company mandate."""
import json
import os
import secrets
import ssl
from urllib.error import HTTPError, URLError
from urllib.request import HTTPSHandler, HTTPRedirectHandler, Request, build_opener

from https_readiness import first_response

USERS = {
    "CLIENT": ("client@jury.test", "00000000-00f0-4000-8000-000000000001", "Клиент жюри"),
    "AGENT": ("agent@jury.test", "00000000-00f0-4000-8000-000000000002", "Агент жюри"),
    "UNDERWRITER": ("underwriter@jury.test", "00000000-00f0-4000-8000-000000000003", "Андеррайтер жюри"),
}
COMPANY = "6f1d4c1e-0b8e-4c55-9a51-000000000001"
MANDATE = "00000000-00f0-4000-8000-000000000004"


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def read_credentials(path, create=False, allow_custom_emails=False):
    if create and not path.exists():
        credentials = {role: {"email": email, "password": secrets.token_urlsafe(32)}
                       for role, (email, _, _) in USERS.items()}
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(credentials, output)
    if path.stat().st_mode & 0o077:
        raise ValueError("Файл данных для входа жюри должен иметь права 0600")
    credentials = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(credentials, dict) or set(credentials) != set(USERS):
        raise ValueError("Нужны три синтетические учётки жюри")
    passwords, emails = [], []
    for role, (email, _, _) in USERS.items():
        record = credentials[role]
        if not isinstance(record, dict) or set(record) != {"email", "password"}:
            raise ValueError("Для роли жюри ожидаются поля email и password")
        actual_email = record["email"]
        if not isinstance(actual_email, str) or "@" not in actual_email or not actual_email.strip():
            raise ValueError("Ожидается отдельная почта для каждой роли жюри")
        if not allow_custom_emails and actual_email != email:
            raise ValueError("Ожидаются отдельные синтетические адреса @jury.test")
        emails.append(actual_email.lower())
        password = record["password"]
        if not isinstance(password, str) or not 16 <= len(password.encode("utf-8")) <= 72:
            raise ValueError("Синтетический пароль должен содержать от 16 до 72 байт")
        passwords.append(password)
    if len(set(emails)) != len(emails):
        raise ValueError("Для ролей жюри нужны разные адреса")
    if len(set(passwords)) != len(passwords):
        raise ValueError("Для ролей жюри нужны разные пароли")
    return credentials


def provision(deployment, credentials):
    from bcrypt import gensalt, hashpw

    if deployment.project != "cabinet-dev":
        raise ValueError("Учётки жюри разрешены только в cabinet-dev")
    statements = ["begin;"]
    for role, (email, identifier, name) in USERS.items():
        password_hash = hashpw(credentials[role]["password"].encode(), gensalt(rounds=12)).decode()
        company = f"'{COMPANY}'" if role == "CLIENT" else "null"
        can_sign = "true" if role == "CLIENT" else "false"
        statements.append(f"""
            insert into users (id, full_name, email, role, status, company_id, can_sign, password_hash)
            values ('{identifier}', '{name}', '{email}', '{role}', 'ACTIVE', {company}, {can_sign}, '{password_hash}')
            on conflict (id) do nothing;
        """)
    # A revoked mandate is retained. Redeployment must not confer revoked access.
    statements.append(f"""
        insert into agent_clients (id, agent_user_id, company_id)
        values ('{MANDATE}', '{USERS["AGENT"][1]}', '{COMPANY}')
        on conflict (id) do nothing;
    """)
    deployment.sql("identity", "\n".join([*statements, "commit;"]))


def verify(origin, credentials, ca=None, expected_company=None):
    opener = build_opener(NoRedirect(), HTTPSHandler(context=ssl.create_default_context(cafile=ca)))
    tokens = {}
    checks = []

    def send(method, path, token=None, body=None, timeout=15):
        headers = {}
        if token:
            headers["Authorization"] = "Bearer " + token
        if body:
            headers["Content-Type"] = "application/json"
        request = Request(origin + path, method=method, headers=headers,
                          data=json.dumps(body).encode("utf-8") if body else None)
        try:
            response = opener.open(request, timeout=timeout)
        except HTTPError as error:
            response = error
        with response:
            if path == "/" and response.status in {502, 503, 504}:
                raise URLError("HTTPS gateway ещё запускается")
            if response.status != 200 or path == "/":
                return response.status, None
            return response.status, json.load(response)

    if first_response(send, timeout=120)[0] != 200:
        raise RuntimeError("HTTPS не отдаёт главную страницу")

    def request(name, path, status, token=None, body=None):
        actual, payload = send("POST" if body else "GET", path, token=token, body=body)
        if actual != status:
            raise RuntimeError(f"{name}: HTTP {actual} вместо {status}")
        checks.append({"check": name, "status": status})
        return payload

    for role in USERS:
        session = request(f"Вход {role}", "/api/v1/auth/sign-in", 200, body=credentials[role])
        if not isinstance(session, dict) or session.get("user", {}).get("role") != role or not session.get("token"):
            raise RuntimeError(f"Сессия {role} не подтверждена")
        tokens[role] = session["token"]
    company = request("Компания клиента", "/api/v1/companies/me", 200, tokens["CLIENT"])
    company_id = company.get("id")
    if not company_id or (expected_company and company_id != expected_company):
        raise RuntimeError("Клиент не привязан к синтетической компании")
    mandates = request("Мандат агента", "/api/v1/agent/clients", 200, tokens["AGENT"])
    accessible = {mandate["companyId"] for mandate in mandates}
    if company_id not in accessible or (expected_company and accessible != {expected_company}):
        raise RuntimeError("Не подтверждена граница синтетического мандата агента")
    bank_queue = "/api/v1/applications/page?size=1&group=review"
    request("Очередь андеррайтера", bank_queue, 200, tokens["UNDERWRITER"])
    request("Агент без доступа к очереди банка", bank_queue, 403, tokens["AGENT"])
    for role in ("CLIENT", "UNDERWRITER"):
        request(f"{role} без доступа к мандатам", "/api/v1/agent/clients", 403, tokens[role])
    request("Гость без доступа к мандатам", "/api/v1/agent/clients", 401)
    request("Внутренняя панель закрыта", "/sim/control", 404)
    return checks
