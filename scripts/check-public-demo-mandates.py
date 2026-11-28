#!/usr/bin/env python3
"""Read-only contract smoke for agent mandates on the public synthetic demo."""

import argparse
import json
import sys
from urllib.error import URLError
from urllib.request import HTTPRedirectHandler, build_opener

from cabinet_api import Cabinet

PUBLIC_DEMO_URLS = (
    "https://dev.credit-cabinet.ru/demo",
    "https://demo.dev.credit-cabinet.ru",
)
MANDATES_ROUTE = "/api/v1/companies/me/agent-mandates"
MANDATE_FIELDS = {"id", "agentUserId", "agentName", "status", "assignedAt", "expiresAt", "revokedAt"}
MANDATE_STATUSES = ("ACTIVE", "EXPIRED", "REVOKED")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def check_mandates(cabinet):
    checks = {}

    def get(name, route, expected=200, token=None):
        response = cabinet.request("GET", route, token=token, expected=None, timeout=10)
        status = response["status"]
        if status != expected:
            raise RuntimeError(f"{name}: HTTP {status} вместо {expected}")
        checks[name] = status
        return response["body"]

    def sign_in(email, role):
        try:
            session = cabinet.sign_in(email)
        except AssertionError:
            raise RuntimeError(f"Синтетический вход {role} завершился ошибкой") from None
        if (not isinstance(session, dict) or not isinstance(session.get("user"), dict)
                or session["user"].get("role") != role
                or not isinstance(session.get("token"), str) or not session["token"]):
            raise RuntimeError(f"Синтетическая сессия {role} не подтверждена")
        return session["token"]

    options = get("demo-options", "/api/v1/auth/options")
    if not isinstance(options, dict) or options.get("demoAccounts") is not True:
        raise RuntimeError("Маршрут не подтвердил публичный синтетический режим")
    get("guest-mandates", MANDATES_ROUTE, expected=401)
    client = sign_in("client@demo.test", "CLIENT")
    company = get("client-company", "/api/v1/companies/me", token=client)
    if not isinstance(company, dict) or not str(company.get("inn", "")).startswith("00"):
        raise RuntimeError("Сессия клиента не подтвердила синтетическую компанию")
    mandates = get("client-mandates", MANDATES_ROUTE, token=client)
    if (not isinstance(mandates, list)
            or any(not isinstance(item, dict) or not MANDATE_FIELDS <= item.keys()
                   or item["status"] not in MANDATE_STATUSES for item in mandates)):
        raise RuntimeError("Ответ по мандатам не соответствует контракту")
    agent = sign_in("agent@demo.test", "AGENT")
    get("agent-mandates", MANDATES_ROUTE, expected=403, token=agent)
    return {"checks": checks, "mandate_count": len(mandates)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", choices=PUBLIC_DEMO_URLS, default=PUBLIC_DEMO_URLS[0])
    options = parser.parse_args()
    try:
        result = check_mandates(Cabinet(options.origin, opener=build_opener(NoRedirect())))
    except RuntimeError as error:
        sys.exit(str(error))
    except (URLError, TimeoutError):
        sys.exit("Публичное демо недоступно по HTTPS")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
