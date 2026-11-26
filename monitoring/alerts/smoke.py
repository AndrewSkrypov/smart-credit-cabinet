#!/usr/bin/env python3
"""Run disposable Prometheus, Alertmanager and GoAlert smoke against a synthetic endpoint."""

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import time
from urllib.error import URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen
from uuid import uuid4


COMPOSE = """\
services:
  goalert:
    image: goalert/demo:v0.35.0
    environment:
      SKIP_SEED: "1"
    tmpfs: ["/var/lib/postgresql/data:mode=0777"]
    ports: ["127.0.0.1::8081"]
  source:
    image: python:3.13-alpine
    command: ["python", "-m", "http.server", "8000", "--directory", "/fixture"]
    volumes: ["./fixture:/fixture:ro"]
  alertmanager:
    image: prom/alertmanager:v0.33.0
    tmpfs: ["/alertmanager:uid=65534,gid=65534"]
    volumes: ["./alertmanager:/etc/alertmanager:ro"]
  prometheus:
    image: prom/prometheus:v3.15.1
    tmpfs: ["/prometheus:uid=65534,gid=65534"]
    volumes: ["./prometheus:/etc/prometheus:ro"]
    ports: ["127.0.0.1::9090"]
"""

PROMETHEUS = """\
global:
  scrape_interval: 1s
  evaluation_interval: 1s
  external_labels:
    environment: dev
rule_files: [/etc/prometheus/rule.yml]
alerting:
  alertmanagers:
    - static_configs:
        - targets: [alertmanager:9093]
scrape_configs:
  - job_name: smoke
    fallback_scrape_protocol: PrometheusText0.0.4
    static_configs:
      - targets: [source:8000]
"""

RULE = """\
groups:
  - name: smoke
    rules:
      - alert: CabinetSmoke
        expr: cabinet_smoke_fault == 1
        for: 5s
        labels:
          severity: critical
        annotations:
          summary: Cabinet synthetic incident
"""

ALERTMANAGER = """\
route:
  receiver: discard
  group_by: [environment, alertname, service]
  group_wait: 1s
  group_interval: 2s
  repeat_interval: 1h
  routes:
    - receiver: goalert
      matchers: ['environment="dev"', 'severity="critical"', 'alertname="CabinetSmoke"']
receivers:
  - name: discard
  - name: goalert
    webhook_configs:
      - url_file: /etc/alertmanager/goalert-url
        send_resolved: true
"""


def wait_for(description, predicate, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                print(description, flush=True)
                return result
        except (URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(0.3)
    raise RuntimeError(f"Timed out waiting for {description}")


def get_json(url):
    with urlopen(url, timeout=3) as response:
        return json.load(response)


def login(origin, username, password):
    request = Request(
        f"{origin}/api/v2/identity/providers/basic?noRedirect=1",
        data=urlencode(dict(username=username, password=password)).encode(),
        headers={"Referer": origin},
    )
    with urlopen(request, timeout=3) as response:
        return response.read().decode().strip()


def go_query(origin, token, query, **variables):
    request = Request(
        f"{origin}/api/graphql",
        data=json.dumps({"query": query, "variables": variables}).encode(),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Referer": origin,
        },
    )
    with urlopen(request, timeout=3) as response:
        result = json.load(response)
    if result.get("errors"):
        raise RuntimeError("GoAlert GraphQL rejected the fixture operation: " + "; ".join(
            error["message"] for error in result["errors"]
        ))
    return result["data"]


def create_oncall(query, first_user, backup_user):
    schedule = query(
        """mutation($input: CreateScheduleInput!) {
            createSchedule(input: $input) { id }
        }""",
        input=dict(
            name="Cabinet Smoke Schedule",
            timeZone="UTC",
            targets=[dict(
                newRotation=dict(
                    name="Cabinet Smoke Rotation", timeZone="UTC",
                    start=(datetime.now(timezone.utc) - timedelta(days=1)).isoformat(),
                    type="weekly", userIDs=[first_user],
                ),
                rules=[{}],
            )],
        ),
    )["createSchedule"]
    return query(
        """mutation($input: CreateServiceInput!) {
            createService(input: $input) {
                id integrationKeys { href }
                escalationPolicy { steps { stepNumber delayMinutes targets { type } } }
            }
        }""",
        input=dict(
            name="Cabinet Smoke",
            newEscalationPolicy=dict(
                name="Cabinet Smoke Escalation", repeat=0, steps=[
                    dict(delayMinutes=1, targets=[dict(id=schedule["id"], type="schedule")]),
                    dict(delayMinutes=1, targets=[dict(id=backup_user, type="user")]),
                ],
            ),
            newIntegrationKeys=[dict(type="prometheusAlertmanager", name="Local Alertmanager")],
        ),
    )["createService"]


def exercise(work):
    project = f"cabinet-incident-{uuid4().hex[:10]}"

    def docker(*args, capture=False):
        return subprocess.run(
            ["docker", "compose", "-p", project, *args], cwd=work, check=True,
            text=True, capture_output=capture,
        ).stdout

    def local_url(service, port):
        return "http://" + docker("port", service, str(port), capture=True).strip()

    (work / "compose.yaml").write_text(COMPOSE)
    for directory in ("prometheus", "alertmanager", "fixture"):
        (work / directory).mkdir()
    (work / "prometheus/prometheus.yml").write_text(PROMETHEUS)
    (work / "prometheus/rule.yml").write_text(RULE)
    (work / "alertmanager/alertmanager.yml").write_text(ALERTMANAGER)
    fault = work / "fixture/metrics"
    fault.write_text("# TYPE cabinet_smoke_fault gauge\ncabinet_smoke_fault 0\n")

    try:
        docker("up", "-d", "goalert")
        goalert = local_url("goalert", 8081)
        admin = wait_for("Local GoAlert ready", lambda: login(goalert, "admin", "admin123"))

        def query(statement, **variables):
            return go_query(goalert, admin, statement, **variables)

        first_user = query("{ user { id } }")["user"]["id"]
        backup_token = login(goalert, "user", "user1234")
        backup_user = go_query(goalert, backup_token, "{ user { id } }")["user"]["id"]
        service = create_oncall(query, first_user, backup_user)
        steps = service["escalationPolicy"]["steps"]
        assert [(step["delayMinutes"], step["targets"][0]["type"]) for step in steps] == [
            (1, "schedule"), (1, "user"),
        ]
        wait_for(
            "GoAlert rotation is on call",
            lambda: any(
                user["userID"] == first_user and user["stepNumber"] == steps[0]["stepNumber"]
                for user in query(
                    "query($id: ID!) { service(id: $id) { onCallUsers { userID stepNumber } } }",
                    id=service["id"],
                )["service"]["onCallUsers"]
            ),
        )
        print("GoAlert policy has a scheduled primary and a 1-minute secondary escalation", flush=True)
        key = urlsplit(service["integrationKeys"][0]["href"])
        (work / "alertmanager/goalert-url").write_text(f"http://goalert:8081{key.path}?{key.query}\n")

        docker("up", "-d", "source", "alertmanager", "prometheus")
        prometheus = local_url("prometheus", 9090)
        wait_for(
            "Synthetic Prometheus target Up",
            lambda: any(target["health"] == "up" for target in
                        get_json(prometheus + "/api/v1/targets")["data"]["activeTargets"]),
        )
        fault.write_text("# TYPE cabinet_smoke_fault gauge\ncabinet_smoke_fault 1\n")

        def prometheus_state():
            alerts = get_json(prometheus + "/api/v1/alerts")["data"]["alerts"]
            return alerts[0]["state"] if alerts else "inactive"

        wait_for("Prometheus pending", lambda: prometheus_state() == "pending")
        wait_for("Prometheus firing", lambda: prometheus_state() == "firing")

        def incident():
            alerts = query(
                """query($input: AlertSearchOptions) {
                    alerts(input: $input) { nodes { alertID status serviceID } }
                }""",
                input=dict(filterByServiceID=[service["id"]]),
            )["alerts"]["nodes"]
            return alerts[0] if alerts else None

        alert = wait_for("Alertmanager webhook created GoAlert incident", incident)
        assert alert["status"] == "StatusUnacknowledged"
        assert alert["serviceID"] == service["id"]

        def tracked_incident():
            return query(
                "query($id: Int!) { alert(id: $id) { status state { stepNumber } } }",
                id=alert["alertID"],
            )["alert"]

        def escalated():
            current = tracked_incident()
            return (current["status"] == "StatusUnacknowledged" and current["state"]
                    and current["state"]["stepNumber"] == steps[1]["stepNumber"])

        wait_for("GoAlert advanced an unacknowledged incident to the backup step",
                 escalated, timeout=100)
        query(
            """mutation($input: UpdateAlertsInput!) { updateAlerts(input: $input) { status } }""",
            input=dict(alertIDs=[alert["alertID"]], newStatus="StatusAcknowledged"),
        )
        wait_for("GoAlert acknowledged", lambda: tracked_incident()["status"] == "StatusAcknowledged")
        fault.write_text("# TYPE cabinet_smoke_fault gauge\ncabinet_smoke_fault 0\n")
        wait_for("Prometheus inactive after recovery", lambda: prometheus_state() == "inactive")
        wait_for("Alertmanager resolved webhook closed GoAlert incident",
                 lambda: tracked_incident()["status"] == "StatusClosed")
    finally:
        docker("down", "-v")


if __name__ == "__main__":
    with TemporaryDirectory(prefix="cabinet-incident-") as tmp:
        exercise(Path(tmp))
