#!/usr/bin/env python3
"""
Runs the test scenarios of docs/integrations.md against a running cabinet (docker compose up)
and prints what happened to each application. Only the Python standard library is used.

    python3 scripts/run-scenarios.py                  # all ten companies
    python3 scripts/run-scenarios.py client logistic  # some of them, by account name
    CABINET_URL=http://host:8080 python3 scripts/run-scenarios.py

Each run withdraws the company's unfinished application for the same product first, so it can
be repeated. The first company also accepts the offer, signs it and waits for the money.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("CABINET_URL", "http://localhost:8080")
API = BASE + "/api/v1"
PASSWORD = "Demo2026"
FINISHED = {"DISBURSED", "REJECTED", "WITHDRAWN", "CLOSED"}
ACCOUNTS = {
    "000100000004": "client", "0001001014": "mebel", "0001001021": "logistic", "0001001039": "romashka",
    "000100020145": "gromov", "000100020219": "belova", "0001001046": "technopark", "0001001053": "agro",
    "000100020392": "novikov", "0001001060": "stroygrad",
}


def call(method, path, token=None, body=None, raw=None, content_type="application/json"):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(API + path, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", content_type)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        return error.code, (json.loads(text) if text else None)


def sign_in(account):
    return call("POST", "/auth/sign-in", body={"email": account + "@demo.test", "password": PASSWORD})[1]["token"]


def upload(token, application, document_type):
    boundary = uuid.uuid4().hex
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{document_type}.pdf\"\r\n"
        "Content-Type: application/pdf\r\n\r\n"
    ).encode() + b"%PDF-1.7 synthetic test document\r\n" + f"--{boundary}--\r\n".encode()
    return call("POST", f"/applications/{application}/documents?type={document_type}", token, raw=body,
                content_type=f"multipart/form-data; boundary={boundary}")[0]


def wait(token, application, statuses, timeout=30):
    deadline = time.time() + timeout
    while True:
        current = call("GET", f"/applications/{application}", token)[1]
        if current["status"] in statuses or time.time() > deadline:
            return current
        time.sleep(1)


def rub(amount):
    return f"{amount:,}".replace(",", " ") + " ₽"


def run(company, underwriter):
    inn, scenario = company["inn"], company["scenario"]
    account = ACCOUNTS[inn]
    token = sign_in(account)
    request = scenario["request"]
    for previous in call("GET", "/applications", token)[1]:
        if previous["productCode"] == request["productCode"] and previous["status"] not in FINISHED:
            call("POST", f"/applications/{previous['id']}/withdraw", token)
    status, created = call("POST", "/applications", token, {**request, "bureauConsent": True})
    if status != 201:
        return f"не удалось создать заявку: {status} {created}"
    application = created["id"]
    call("POST", f"/applications/{application}/submit", token, None)
    stop = {"OFFER_ISSUED", "MANUAL_REVIEW", "REJECTED", "INFO_REQUESTED"}
    current = wait(token, application, stop, timeout=12 if account == "agro" else 30)
    parts = [current["status"]]
    if current["status"] == "INFO_REQUESTED":
        requested = [item["documentType"] for item in current["process"]["infoRequest"]["items"]]
        for document_type in requested:
            upload(token, application, document_type)
        call("POST", f"/applications/{application}/info-provided", token)
        current = wait(token, application, stop - {"INFO_REQUESTED"})
        parts.append(f"загружены {', '.join(requested)} → {current['status']}")
    process = current["process"]
    if current["status"] == "OFFER_ISSUED":
        offer = process["offer"]
        parts.append(f"{offer['kind']}: {rub(offer['amount'])}, {offer['termMonths']} мес., {offer['rate']}%")
        parts += offer["changes"]
    elif current["status"] == "REJECTED":
        parts.append(f"{process['outcome']['reasonCode']} — {process['outcome']['reasonTitle']}")
    elif current["status"] == "MANUAL_REVIEW":
        card = call("GET", f"/underwriting/applications/{application}", underwriter)[1]["automaticDecision"]
        parts.append(f"{', '.join(reason['code'] for reason in card['manualReasons'])}; баллы {card['score']}, рейтинг {card['rating']}")
    elif current["status"] == "ENRICHMENT":
        events = call("GET", f"/applications/{application}/events", token)[1]
        parts.append(f"источник не отвечает, попыток: {sum(1 for e in events if e['event'] == 'SOURCE_FAILED')}; через SOURCE_TIMEOUT — андеррайтеру")
    if account == "client" and current["status"] == "OFFER_ISSUED":
        accepted = call("POST", f"/applications/{application}/offer/accept", token)[1]
        code = accepted["process"]["signing"]["code"]
        call("POST", f"/applications/{application}/sign", token, {"code": code})
        done = wait(token, application, {"DISBURSED"})
        parts.append(f"подписано кодом {code} → {done['status']}, договор {done['process']['loan']['contractNumber']}")
    return " | ".join(parts)


def main():
    selected = set(sys.argv[1:])
    with urllib.request.urlopen(BASE + "/sim/control") as response:
        companies = json.loads(response.read())["companies"]
    underwriter = sign_in("underwriter")
    for company in companies:
        if selected and ACCOUNTS[company["inn"]] not in selected:
            continue
        print(f"{company['name']}\n  ожидается: {company['scenario']['expected']}\n  получено:  {run(company, underwriter)}\n")


if __name__ == "__main__":
    main()
