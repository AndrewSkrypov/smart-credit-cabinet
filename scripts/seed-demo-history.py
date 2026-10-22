#!/usr/bin/env python3
"""Create synthetic analytical history through the real client and bank APIs only."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
from urllib.parse import urlparse
from cabinet_api import Cabinet
from demo_environment import DemoEnvironment, ROOT

parser = argparse.ArgumentParser(description="Синтетическая история: реальные заявки, решения, отзывы и истечения сроков")
parser.add_argument("--count", type=int, default=120, help="100–200 для полного наполнения; 12 для короткой проверки всех типов")
parser.add_argument("--base")
parser.add_argument("--project", default="cabinet-demo-history")
parser.add_argument("--port", type=int, default=18082)
parser.add_argument("--start", action="store_true", help="Запустить отдельный демо-стенд, временно сократить TTL и затем вернуть его")
parser.add_argument("--no-build", action="store_true")
parser.add_argument("--image-prefix", help="Использовать пять уже проверенных локальных образов")
parser.add_argument("--report-name", default="history")
args = parser.parse_args()
assert 12 <= args.count <= 200 and args.count == int(args.count)
assert __import__("re").fullmatch(r"[A-Za-z0-9_-]{1,64}", args.report_name)
base = args.base or f"http://127.0.0.1:{args.port}"
assert urlparse(base).hostname in {"localhost", "127.0.0.1", "::1"} and urlparse(base).scheme == "http", "Наполнение разрешено только на локальном демо-стенде"
if args.start:
    assert (urlparse(base).port or 80) == args.port, "Порт запуска и API должны совпадать"
environment = DemoEnvironment(args.project, args.port, ttl="PT60S")
report = {"started_at": datetime.now(timezone.utc).isoformat(), "requested": args.count, "full_history": args.count >= 100,
          "project": args.project, "applications": [], "passed": False}
directory = ROOT / "scripts/demo-reports"
directory.mkdir(exist_ok=True)
created = []
last_auth = 0.0


class PacedCabinet(Cabinet):
    def request(self, method, path, *values, **kwargs):
        global last_auth
        if path.startswith("/api/v1/auth/") and method == "POST":
            time.sleep(max(0, 2.1 - (time.monotonic() - last_auth)))
            last_auth = time.monotonic()
        return super().request(method, path, *values, **kwargs)


api = PacedCabinet(base)
terminal = {"DISBURSED", "REJECTED", "WITHDRAWN", "CLOSED"}
tokens = {}
pending = []
configured = False
pdf = (ROOT / "contracts/postman/fixtures/synthetic.pdf").read_bytes()
cases = ["Автоматическое одобрение", "Контрпредложение правил", "Отказ со стоп-фактором", "Отказ по рейтингу",
         "Одобрение андеррайтера", "Отказ андеррайтера", "Контрпредложение андеррайтера", "Новая компания",
         "Отзыв после отправки", "Истечение оффера", "Истечение запроса сведений", "Документы через API"]
accounts = {"000100000004": "client", "0001001014": "mebel", "0001001021": "logistic", "0001001039": "romashka",
            "000100020145": "gromov", "0001001046": "technopark"}


def stock(name):
    if name not in tokens:
        tokens[name] = api.sign_in(name + "@demo.test")["token"]
    return tokens[name]


def state(client):
    return api.json("GET", "/api/v1/applications/" + client["id"], token=client["token"])


def complete_information(client, app):
    rounds = 0
    while app["status"] == "INFO_REQUESTED":
        rounds += 1
        assert rounds <= 4, "Не завершились повторные запросы сведений"
        requested_at = datetime.fromisoformat(app["process"]["infoRequest"]["requestedAt"].replace("Z", "+00:00"))
        for item in app["process"]["infoRequest"]["items"]:
            if item.get("documentType"):
                for attempt in range(5):
                    uploaded = api.upload(client["token"], client["id"], item["documentType"], pdf)["body"]
                    uploaded_at = datetime.fromisoformat(uploaded["uploadedAt"].replace("Z", "+00:00"))
                    if uploaded_at >= requested_at:
                        break
                    # Server timestamps must prove freshness even when wall time moves backward.
                    client["freshness_reuploads"] = client.get("freshness_reuploads", 0) + 1
                    time.sleep(min(10, max(0.5, (requested_at - uploaded_at).total_seconds() + 0.25)))
                else:
                    raise AssertionError("Часы сервера не позволяют подтвердить свежесть версии документа")
        api.json("POST", f"/api/v1/applications/{client['id']}/info-provided", {"answer": "Синтетические сведения и документы предоставлены"}, client["token"])
        app = api.wait(client["token"], client["id"], {"OFFER_ISSUED", "MANUAL_REVIEW", "REJECTED", "INFO_REQUESTED"}, timeout=120)
    return app


def issue(client, app):
    app = complete_information(client, app)
    if app["status"] == "MANUAL_REVIEW":
        api.json("POST", f"/api/v1/underwriting/applications/{client['id']}/approve",
                 {"comment": "Синтетическая демонстрация: сведения рассмотрены, кредит одобрен"}, bank)
        app = state(client)
    assert app["status"] == "OFFER_ISSUED", "Ожидался реальный оффер здоровой синтетической компании"
    return app


def disburse(client, offer):
    client["offer_kind"] = offer["process"]["offer"]["kind"]
    accepted = api.json("POST", f"/api/v1/applications/{client['id']}/offer/accept", token=client["token"])
    api.json("POST", f"/api/v1/applications/{client['id']}/sign", {"code": accepted["process"]["signing"]["code"]}, client["token"])
    api.wait(client["token"], client["id"], {"DISBURSED"}, timeout=120)


def record(client):
    app = state(client)
    assert app["status"] in terminal, "Заявка осталась незавершённой"
    events = api.json("GET", f"/api/v1/applications/{client['id']}/events", token=client["token"])
    assert sum(event["event"] == "SUBMIT" for event in events) == 1, "Не подтверждена единственная отправка"
    if client["kind"] == 11:
        assert any(event["event"] == "INFO_PROVIDED" for event in events), "Не подтверждено предоставление документов"
    entry = {"id": client["id"], "case": client["case"], "status": app["status"], "close_reason": app.get("closeReason"),
             "company_source": client["company_source"], "offer_kind": client.get("offer_kind"),
             "freshness_reuploads": client.get("freshness_reuploads", 0),
             "events": [{key: event.get(key) for key in ["event", "fromStatus", "toStatus", "occurredAt", "reasonCode"]} for event in events]}
    report["applications"].append(entry)


def prepare(index):
    kind = index % len(cases)
    if kind in {5, 6, 7, 8, 9, 10} or (kind == 11 and index >= len(cases)):
        registration = api.register("demo-history")
        token = registration["token"]
        body = {"productCode": "WORKING_CAPITAL", "purpose": "WORKING_CAPITAL", "amount": 4_000_000 if kind == 11 else 1_000_000, "termMonths": 12,
                "declaredRevenue12m": 999_000_000 if kind in {5, 6, 10} else 24_000_000,
                "declaredMonthlyDebtPayments": 0, "bureauConsent": True}
        origin = "Сгенерированная компания с ИНН 00"
    else:
        account = {0: "client", 1: "mebel", 2: "romashka", 3: "gromov", 4: "logistic", 11: "technopark"}[kind]
        token = stock(account)
        body = {**scenarios[account], "bureauConsent": True}
        active = api.json("GET", "/api/v1/applications", token=token)
        assert not any(app["productCode"] == body["productCode"] and app["status"] not in terminal for app in active), "Есть активная чужая демо-заявка; используйте отдельный стенд или явный reset"
        origin = "Сценарная компания " + account
    app = api.json("POST", "/api/v1/applications", body, token, expected=(201,))
    client = {"id": app["id"], "token": token, "case": cases[kind], "company_source": origin, "kind": kind, "body": body, "index": index}
    created.append(client)
    return client


def process(client):
    kind, token, body = client["kind"], client["token"], client["body"]
    api.json("POST", f"/api/v1/applications/{client['id']}/submit", token=token, headers={"Idempotency-Key": "history-" + client["id"]})
    if kind == 8:
        deadline = time.monotonic() + 30
        while True:
            response = api.request("POST", f"/api/v1/applications/{client['id']}/withdraw", token=token, expected=(200, 409))
            if response["status"] == 200:
                break
            assert time.monotonic() < deadline, "Не завершился короткий шаг, запрещающий отзыв"
            time.sleep(0.1)
    else:
        app = api.wait(token, client["id"], {"OFFER_ISSUED", "MANUAL_REVIEW", "REJECTED", "INFO_REQUESTED"}, timeout=180)
        if kind in {2, 3}:
            assert app["status"] == "REJECTED", "Сценарный отказ не воспроизведён — проверьте активную политику"
        elif kind in {4, 5, 6, 10}:
            assert app["status"] == "MANUAL_REVIEW", "Сценарий должен попасть к андеррайтеру"
            route = f"/api/v1/underwriting/applications/{client['id']}"
            if kind == 5:
                api.json("POST", route + "/reject", {"comment": "Синтетическая демонстрация: риск подтверждён андеррайтером"}, bank)
            elif kind == 10:
                api.json("POST", route + "/request-info", {"items": [{"question": "Поясните синтетическую выручку"}],
                         "comment": "Синтетическая демонстрация: ожидается уточнение"}, bank)
                app = state(client)
                remaining = (datetime.fromisoformat(app["process"]["infoRequest"]["deadline"].replace("Z", "+00:00")) - datetime.now(timezone.utc)).total_seconds()
                assert remaining < 120, "Нужен короткий TTL демо-профиля; даты и SQL не подменяются"
                pending.append((client, "INFO_EXPIRED"))
                return
            else:
                if kind == 6:
                    api.json("POST", route + "/counter-offer", {"amount": int(body["amount"] * 0.9), "termMonths": body["termMonths"],
                             "comment": "Синтетическая демонстрация: сумма снижена по оценке андеррайтера"}, bank)
                    app = state(client)
                else:
                    app = issue(client, app)
                disburse(client, app)
        else:
            app = issue(client, app)
            if kind == 9:
                remaining = (datetime.fromisoformat(app["process"]["offer"]["validUntil"].replace("Z", "+00:00")) - datetime.now(timezone.utc)).total_seconds()
                assert remaining < 120, "Нужен короткий TTL демо-профиля; даты и SQL не подменяются"
                pending.append((client, "OFFER_EXPIRED"))
                return
            disburse(client, app)
    record(client)
    print(f"Пройдено {client['index'] + 1}/{args.count}: {cases[kind]}", flush=True)


try:
    if args.start:
        environment.assert_demo()
        if args.image_prefix:
            environment.images_from(args.image_prefix)
        configured = True
        environment.compose("up", "-d", "--wait", "--wait-timeout", "300", "--no-build" if args.no_build or args.image_prefix else "--build")
    assert api.json("GET", "/api/v1/auth/options")["demoAccounts"] is True, "Демо-учётки выключены; запись в такой стенд запрещена"
    controls = api.json("GET", "/sim/control")
    assert all(source["mode"] == "OK" for source in controls["sources"]), "Перед наполнением восстановите источники; скрипт не меняет чужие fault-настройки"
    scenarios = {accounts[item["inn"]]: item["scenario"]["request"] for item in controls["companies"] if item["inn"] in accounts}
    bank = stock("underwriter")
    risk = stock("risk")
    report["analytics_before"] = api.json("GET", "/api/v1/analytics/funnel", token=risk)
    # One active product per company prevents overlapping rounds; generated manual fixtures keep each round independent.
    for offset in range(0, args.count, len(cases)):
        batch = [prepare(index) for index in range(offset, min(offset + len(cases), args.count))]
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(process, batch))
    for client, event in pending:
        api.wait(client["token"], client["id"], {"CLOSED"}, timeout=150)
        record(client)
        assert any(item["event"] == event for item in report["applications"][-1]["events"]), "Не подтверждено настоящее истечение срока"
    assert len(report["applications"]) == args.count
    report["status_counts"] = dict(Counter(item["status"] for item in report["applications"]))
    report["case_counts"] = dict(Counter(item["case"] for item in report["applications"]))
    report["event_counts"] = dict(Counter(event["event"] for item in report["applications"] for event in item["events"]))
    for event in ["AUTO_OFFER", "AUTO_REJECT", "APPROVE", "REJECT", "COUNTER_OFFER", "WITHDRAW", "INFO_EXPIRED", "OFFER_EXPIRED", "INFO_PROVIDED", "DISBURSED"]:
        assert report["event_counts"].get(event, 0) > 0, "Не воспроизведён тип истории " + event
    report["analytics_after"] = api.json("GET", "/api/v1/analytics/funnel", token=risk)
    before_funnel = {row["code"]: row["applications"] for row in report["analytics_before"]["funnel"]}
    after_funnel = {row["code"]: row["applications"] for row in report["analytics_after"]["funnel"]}
    assert after_funnel["SUBMITTED"] - before_funnel["SUBMITTED"] == args.count, "Воронка не подтверждает число новых отправок"
    assert after_funnel["DISBURSED"] - before_funnel["DISBURSED"] == report["status_counts"].get("DISBURSED", 0), "Выдачи воронки не совпадают с заявками"
    for status, count in report["status_counts"].items():
        assert report["analytics_after"]["currentStatuses"].get(status, 0) - report["analytics_before"]["currentStatuses"].get(status, 0) == count, "Статусы аналитики не совпадают с заявками"
    report["analytics_verified"] = True
    report["passed"] = True
finally:
    if configured:
        try:
            DemoEnvironment(args.project, args.port, ttl=os.environ.get("DEMO_HISTORY_TTL", "PT30M")).compose("up", "-d", "--no-build", "--no-deps", "--wait", "--wait-timeout", "180", "credit")
            report["normal_timings_restored"] = True
        except Exception:
            report["normal_timings_restored"] = False
            report["passed"] = False
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    (directory / f"{args.report_name}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key not in {"applications", "analytics_before", "analytics_after"}}, ensure_ascii=False, indent=2))
raise SystemExit(0 if report["passed"] else 1)
