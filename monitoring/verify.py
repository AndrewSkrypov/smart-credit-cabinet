#!/usr/bin/env python3
"""Verify live discovery, instrumented activity and provisioned dashboard through public APIs."""
import argparse
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import time
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser(description="Проверка работающих Prometheus/Grafana и метрик конвейера")
parser.add_argument("--replicas", type=int, choices=[1, 2], default=1)
parser.add_argument("--require-activity", action="store_true")
parser.add_argument("--require-source-errors", action="store_true")
parser.add_argument("--require-source-timeouts", action="store_true")
parser.add_argument("--report-name", default="verification")
parser.add_argument("--wait", type=int, default=180)
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.report_name):
    parser.error("Неверное имя отчёта")
prometheus = f"http://127.0.0.1:{os.environ.get('PROMETHEUS_PORT', '9090')}"
grafana = f"http://127.0.0.1:{os.environ.get('GRAFANA_PORT', '3000')}"
credentials = f"{os.environ.get('GRAFANA_USER', 'admin')}:{os.environ.get('GRAFANA_PASSWORD', 'local-monitoring-change-me')}"
authorization = "Basic " + base64.b64encode(credentials.encode()).decode()
report = {"checked_at": datetime.now(timezone.utc).isoformat(), "passed": False}
root = Path(__file__).resolve().parent.parent
report["checkout"] = {"commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
                      "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).strip())}


def get(url, headers=None):
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=10) as response:
        return json.load(response)


def query(expression):
    response = get(prometheus + "/api/v1/query?" + urllib.parse.urlencode({"query": expression}))
    assert response["status"] == "success", "Ошибка выполнения PromQL"
    return response["data"]["result"]


def positive(expression):
    rows = query(expression)
    return bool(rows) and float(rows[0]["value"][1]) > 0


try:
    deadline = time.monotonic() + args.wait
    while True:
        try:
            targets = get(prometheus + "/api/v1/targets")["data"]["activeTargets"]
            credit = [target for target in targets if target["labels"].get("service") == "credit"]
            assert len(credit) == args.replicas, "DNS discovery пока не видит все реплики credit"
            assert len(targets) == args.replicas + 3 and all(target["health"] == "up" for target in targets), "Не все сервисы доступны для scrape"
            assert query("cabinet_pipeline_metrics_snapshot_age_seconds"), "Снимки метрик ещё не получены"
            if args.require_activity:
                assert positive("sum(cabinet_pipeline_step_seconds_count)"), "Нет исполненных шагов"
                assert positive('sum(cabinet_source_call_seconds_count{result="success"})'), "Нет успешных ответов источников"
                assert positive("sum(max by (outcome, actor) (cabinet_decisions))"), "Нет записанных решений"
            if args.require_source_errors:
                assert positive('sum(cabinet_source_call_seconds_count{result=~"timeout|error|circuit_open"})'), "Не измерены сбои источников"
            if args.require_source_timeouts:
                assert positive('sum(cabinet_source_call_seconds_count{result="timeout"})'), "Не измерены сетевые таймауты"
            dashboard = get(grafana + "/api/dashboards/uid/credit-pipeline", {"Authorization": authorization})["dashboard"]
            assert dashboard["title"] == "Кредитный конвейер" and len(dashboard["panels"]) == 14, "Дашборд не прошёл provisioning"
            assert {panel["datasource"]["uid"] for panel in dashboard["panels"]} == {"cabinet-prometheus"}, "Панели потеряли datasource"
            payload = {"from": str(int(time.time() * 1000) - 600000), "to": str(int(time.time() * 1000)),
                       "queries": [{"refId": str(index), "datasource": {"type": "prometheus", "uid": "cabinet-prometheus"},
                                    "expr": panel["targets"][0]["expr"], "instant": True, "range": False,
                                    "intervalMs": 15000, "maxDataPoints": 100} for index, panel in enumerate(dashboard["panels"])]}
            request = urllib.request.Request(grafana + "/api/ds/query", data=json.dumps(payload).encode(),
                                             headers={"Authorization": authorization, "Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=20) as response:
                queries = json.load(response)["results"]
            assert len(queries) == 14 and all(not value.get("error") for value in queries.values()), "Не все 14 запросов панелей исполняются через Grafana"
            assert queries["0"].get("frames"), "Grafana не получила ряды доступности"
            break
        except Exception as error:
            if time.monotonic() >= deadline:
                raise AssertionError(f"Мониторинг не готов: {error}") from error
            time.sleep(2)
    report.update(panel_queries_verified=len(queries), targets=[{"service": target["labels"]["service"], "instance": target["labels"]["instance"], "health": target["health"]}
                          for target in targets], dashboard={"uid": dashboard["uid"], "title": dashboard["title"], "panels": len(dashboard["panels"])})
    report["samples"] = {name: query(name) for name in ["cabinet_pipeline_jobs", "cabinet_pipeline_step_seconds_count",
                         "cabinet_source_call_seconds_count", "cabinet_source_breaker", "cabinet_decisions",
                         "cabinet_decision_time_average_seconds", "cabinet_decision_time_maximum_seconds",
                         "cabinet_pipeline_metrics_snapshot_age_seconds"]}
    report["passed"] = True
finally:
    directory = Path(__file__).resolve().parent / "reports"
    directory.mkdir(parents=True, exist_ok=True)
    (directory / f"{args.report_name}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "samples"}, ensure_ascii=False, indent=2))
