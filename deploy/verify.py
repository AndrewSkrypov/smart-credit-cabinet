#!/usr/bin/env python3
"""Verify the production boundary locally with an explicitly scoped CA trust context."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from common import Deployment, ROOT

parser = argparse.ArgumentParser(description="Проверить локальный prod-стенд с внутренним CA без изменения доверия ОС")
parser.add_argument("--project", default="cabinet-prod-check")
parser.add_argument("--env-file", default="deploy/runtime/prod-test.env")
args = parser.parse_args()
deployment = Deployment(args.project, args.env_file, local=True)
directory = ROOT / "deploy/reports"
directory.mkdir(parents=True, exist_ok=True)
ca = deployment.gateway_config.parent / "local-ca.crt"
ca.parent.mkdir(parents=True, exist_ok=True)
deadline = time.monotonic() + 30
while True:
    copied = subprocess.run(["docker", "cp", deployment.container("caddy") + ":/data/caddy/pki/authorities/local/root.crt", str(ca)], capture_output=True)
    if copied.returncode == 0:
        break
    assert time.monotonic() < deadline, "Caddy не создал локальный CA за 30 с"
    time.sleep(0.25)
context = ssl.create_default_context(cafile=str(ca))
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import Cabinet

api = Cabinet("https://localhost:" + deployment.values.get("HTTPS_PORT", "18443"), ssl_context=context)
report = {"checked_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "checks": [], "passed": False}


def check(name, condition):
    assert condition, name
    report["checks"].append(name)


def first_response():
    # The root CA appears before Caddy has issued the site certificate: the first handshakes
    # may be reset, so wait for one complete HTTPS answer before checking anything.
    deadline = time.monotonic() + 30
    while True:
        try:
            return api.request("GET", "/")
        except (urllib.error.URLError, ConnectionError, ssl.SSLError):
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.5)


try:
    root = first_response()
    check("HTTPS с проверкой сертификата собственным CA клиента", root["status"] == 200)
    headers = {key.lower(): value for key, value in root["headers"].items()}
    check("HSTS и запрет MIME sniffing", "max-age=" in headers.get("strict-transport-security", "") and headers.get("x-content-type-options") == "nosniff")
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, response, code, message, headers, new_url):
            return None
    try:
        redirect = urllib.request.build_opener(NoRedirect()).open("http://localhost:" + deployment.values.get("HTTP_PORT", "18080") + "/", timeout=10)
    except urllib.error.HTTPError as error:
        redirect = error
    with redirect:
        check("HTTP перенаправляет на HTTPS", redirect.status == 308 and redirect.headers["Location"].startswith("https://localhost"))
    check("Демо-кнопки выключены", api.json("GET", "/api/v1/auth/options")["demoAccounts"] is False)
    check("Знакомый демо-пароль не даёт вход", api.request("POST", "/api/v1/auth/sign-in", {"email": "underwriter@demo.test", "password": "Demo2026"}, expected=None)["status"] == 403)
    for path in ["/sim/", "/sim/control", "/ext/bureau/v1/reports/000100000004", "/internal/notifications", "/actuator/prometheus", "/swagger/", "/v3/api-docs/credit"]:
        check("Закрыт внешний маршрут " + path, api.request("GET", path, expected=None)["status"] == 404)
    active_demo = int(deployment.sql("identity", "select count(*) from users where lower(email) like '%@demo.test' and status='ACTIVE'"))
    check("В БД нет активных демо-учёток", active_demo == 0)
    direct = subprocess.run(["docker", "exec", deployment.container("external-sim"), "curl", "-s", "-o", "/dev/null", "-w", "%{http_code}",
                             "-X", "PUT", "-H", "Content-Type: application/json", "--data", '{"mode":"DOWN"}',
                             "http://localhost:8080/sim/control/sources/BUREAU"], capture_output=True, text=True, check=True)
    check("Даже внутреннее управление сбоями запрещено", direct.stdout == "403")
    for service in deployment.active_services():
        if service in {"caddy", "db-init"}:
            continue
        info = json.loads(subprocess.check_output(["docker", "inspect", deployment.container(service)], text=True))[0]
        check("Не опубликован порт " + service, not any(info["NetworkSettings"]["Ports"].values()))
    response = api.request("GET", "/api/v1/products", headers={"X-Forwarded-For": "203.0.113.77", "X-Forwarded-Proto": "http"})
    request_id = next(value for key, value in response["headers"].items() if key.lower() == "x-request-id")
    logs = subprocess.check_output(["docker", "logs", "--since", "30s", deployment.container("gateway")], text=True, encoding="utf-8", stderr=subprocess.DEVNULL)
    line = next(line for line in logs.splitlines() if "id=" + request_id in line)
    check("Gateway видит HTTPS и доверяет только Caddy", "proto=https" in line and "peer=" + deployment.values.get("CADDY_EDGE_IP", "172.30.40.2") in line)
    check("Внешний X-Forwarded-For не подменяет адрес лимитера", not line.startswith("203.0.113.77 "))
    report["gateway_observation"] = line
    limits = {name: int(service["mem_limit"]) for name, service in deployment.active_services().items()}
    check("Лимиты оставляют не менее 2 GiB из 8 GiB для ОС", sum(limits.values()) <= 6 * 1024**3)
    report["memory_limits_bytes"] = limits
    report["passed"] = True
finally:
    (directory / f"{args.project}-boundary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
