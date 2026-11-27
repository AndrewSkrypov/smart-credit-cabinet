#!/usr/bin/env python3
"""
Measures the agent channel end to end against a running cabinet (docker compose up):
agent draft -> client confirmation -> bank pipeline -> offer -> signature -> disbursement.

    python3 scripts/agent-channel-metrics.py                 # 5 runs, prints a summary
    python3 scripts/agent-channel-metrics.py --runs 10 --output docs/final-results/agent-channel.json

Every run also tries the actions outside the agent's authority and counts how many were refused.
Only the Python standard library is used; the data are synthetic demo accounts.
"""
import argparse
import json
import os
import statistics
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cabinet_api import local_request  # noqa: E402

import urllib.error  # noqa: E402
import urllib.request  # noqa: E402

BASE = os.environ.get("CABINET_URL", "http://localhost:8080")
API = BASE + "/api/v1"
PASSWORD = "Demo2026"
CLIENT_INN = "000100000004"
FINISHED = {"DISBURSED", "REJECTED", "WITHDRAWN", "CLOSED"}


def call(method, path, token=None, body=None, raw=None, content_type="application/json", base=API, headers=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    request = local_request(base + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", content_type)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        try:
            return error.code, (json.loads(text) if text else None)
        except json.JSONDecodeError:
            return error.code, text


def sign_in(account):
    status, body = call("POST", "/auth/sign-in", body={"email": account + "@demo.test", "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"Вход {account}: HTTP {status} {body}")
    return body["token"]


def wait(token, application, statuses, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status, body = call("GET", f"/applications/{application}", token)
        if status == 200 and body["status"] in statuses:
            return body
        time.sleep(0.5)
    raise TimeoutError(f"Заявка {application} не дошла до {statuses}")


def clear_client(token):
    status, applications = call("GET", "/applications", token)
    for app in applications if status == 200 else []:
        if app["status"] not in FINISHED and app["status"] not in {"SIGNING", "DISBURSING"}:
            call("POST", f"/applications/{app['id']}/withdraw", token, {"comment": "Подготовка замера агентского канала"})


def upload_as_agent(token, application):
    boundary = uuid.uuid4().hex
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"tax-demo.pdf\"\r\n"
        "Content-Type: application/pdf\r\n\r\n"
    ).encode() + b"%PDF-1.7\nSynthetic tax fixture\n%%EOF\n" + f"\r\n--{boundary}--\r\n".encode()
    return call("POST", f"/agent/applications/{application}/documents?type=TAX_RETURN", token, raw=body,
                content_type=f"multipart/form-data; boundary={boundary}")


def expect(checks, name, status, wanted):
    checks.append({"check": name, "status": status, "expected": wanted, "ok": status == wanted})


def run_once(agent, other, client, company_id, request_body):
    actions = {"AGENT": 0, "CLIENT": 0, "BANK": 0}
    checks = []
    marks = {}
    start = time.monotonic()

    status, draft = call("POST", "/agent/applications", agent, {**request_body, "companyId": company_id})
    actions["AGENT"] += 1
    if status != 201:
        return {"ok": False, "error": f"create {status} {draft}"}
    app = draft["id"]
    agent_path, client_path = f"/agent/applications/{app}", f"/applications/{app}"
    expect(checks, "другой агент читает чужую заявку", call("GET", agent_path, other)[0], 404)
    expect(checks, "клиент не видит черновик агента", call("GET", client_path, client)[0], 404)

    status, _ = upload_as_agent(agent, app)
    actions["AGENT"] += 1
    if status != 201:
        return {"ok": False, "error": f"upload {status}"}
    status, _ = call("POST", f"{agent_path}/request-confirmation", agent)
    actions["AGENT"] += 1
    marks["agent_ready_s"] = time.monotonic() - start
    if status != 200:
        return {"ok": False, "error": f"request-confirmation {status}"}

    expect(checks, "агент подтверждает за клиента", call("POST", f"{client_path}/confirm", agent, {"bureauConsent": True})[0], 403)
    expect(checks, "клиент отправляет агентскую заявку в обход подтверждения",
           call("POST", f"{client_path}/submit", client, headers={"Idempotency-Key": str(uuid.uuid4())})[0], 409)
    status, _ = call("POST", f"{client_path}/confirm", client, {"bureauConsent": True})
    actions["CLIENT"] += 1
    confirmed_at = time.monotonic()
    marks["client_confirmed_s"] = confirmed_at - start
    if status != 200:
        return {"ok": False, "error": f"confirm {status}"}

    offered = wait(client, app, {"OFFER_ISSUED", "MANUAL_REVIEW", "REJECTED", "INFO_REQUESTED"})
    marks["bank_decision_s"] = time.monotonic() - confirmed_at
    if offered["status"] != "OFFER_ISSUED":
        return {"ok": False, "error": f"decision {offered['status']}", "marks": marks}

    expect(checks, "агент принимает предложение", call("POST", f"{client_path}/offer/accept", agent)[0], 403)
    status, _ = call("POST", f"{client_path}/offer/accept", client)
    actions["CLIENT"] += 1
    status, signing = call("POST", f"{client_path}/signing/code", client)
    actions["CLIENT"] += 1
    code = signing["process"]["signing"]["code"]
    expect(checks, "агент подписывает договор", call("POST", f"{client_path}/sign", agent, {"code": code})[0], 403)
    status, _ = call("POST", f"{client_path}/sign", client, {"code": code})
    actions["CLIENT"] += 1
    signed_at = time.monotonic()
    wait(client, app, {"DISBURSED"})
    marks["disbursement_s"] = time.monotonic() - signed_at
    marks["total_s"] = time.monotonic() - start

    expect(checks, "агент читает кредит по клиентскому адресу", call("GET", f"{client_path}/loan", agent)[0], 403)
    expect(checks, "другой агент читает кредит", call("GET", f"{agent_path}/loan", other)[0], 404)
    status, loan = call("GET", f"{agent_path}/loan", agent)
    expect(checks, "агент видит кредит только для чтения", status, 200)
    if status == 200:
        expect(checks, "агент вносит платёж за клиента", call(
            "POST", f"/loans/{loan['id']}/payments/next", agent,
            {"paymentId": loan["payments"][0]["id"]},
            headers={"Idempotency-Key": str(uuid.uuid4())},
        )[0], 403)

    status, portfolio = call("GET", "/agent/portfolio", agent)
    return {"ok": True, "application": app, "marks": marks, "actions": actions, "checks": checks,
            "portfolio_status": status}


def summary(values):
    values = sorted(values)
    if not values:
        return None
    p95 = values[min(len(values) - 1, max(0, round(0.95 * len(values)) - 1))]
    return {"min": round(values[0], 2), "median": round(statistics.median(values), 2), "p95": round(p95, 2), "max": round(values[-1], 2)}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--output")
    args = parser.parse_args()

    agent, other, client = sign_in("agent"), sign_in("agent2"), sign_in("client")
    status, companies = call("GET", "/agent/clients", agent)
    company = next(c for c in companies if c["inn"] == CLIENT_INN)
    status, control = call("GET", "/control", base=BASE + "/sim")
    request_body = next(c for c in control["companies"] if c["inn"] == CLIENT_INN)["scenario"]["request"]

    runs = []
    for number in range(1, args.runs + 1):
        clear_client(client)
        try:
            result = run_once(agent, other, client, company["companyId"], request_body)
        except Exception as error:  # a failed run is a measurement too
            result = {"ok": False, "error": repr(error)}
        runs.append(result)
        print(f"Прогон {number}: {'успех' if result['ok'] else 'ошибка ' + result.get('error', '')}"
              + (f", всего {result['marks']['total_s']:.1f} с" if result.get("ok") else ""))

    ok = [r for r in runs if r["ok"]]
    checks = [c for r in ok for c in r["checks"]]
    try:
        commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    except OSError:
        commit = None
    report = {
        "measuredAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "commit": commit,
        "baseUrl": BASE,
        "runs": len(runs),
        "succeeded": len(ok),
        "successRate": round(len(ok) / len(runs), 3) if runs else 0,
        "timingsSeconds": {name: summary([r["marks"][name] for r in ok]) for name in
                           ["agent_ready_s", "client_confirmed_s", "bank_decision_s", "disbursement_s", "total_s"]},
        "manualActionsPerApplication": ok[0]["actions"] if ok else None,
        "authorityChecks": {"total": len(checks), "asExpected": sum(c["ok"] for c in checks),
                            "failed": [c for c in checks if not c["ok"]]},
        "errors": [r.get("error") for r in runs if not r["ok"]],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        os.makedirs(os.path.dirname(args.output), exist_ok=True)
        with open(args.output, "w", encoding="utf-8") as file:
            json.dump(report, file, ensure_ascii=False, indent=2)
            file.write("\n")
    return 0 if len(ok) == len(runs) and not report["authorityChecks"]["failed"] else 1


if __name__ == "__main__":
    sys.exit(main())
