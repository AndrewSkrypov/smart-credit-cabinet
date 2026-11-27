"""Acceptance of the joined Credit and Engagement implementation on an isolated Compose stack."""

import argparse
from datetime import datetime, timezone
import hmac
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import urlsplit
from uuid import UUID

from cabinet_api import DockerCabinet

ROOT = Path(__file__).resolve().parents[1]
CHANNEL = "assistant-manual-review"
SUMMARY = "/api/v1/assistant/underwriting-summary"
EVENTS = "/internal/v1/assistant/manual-review-events"
ADVICE = "/internal/v1/underwriting/applications"
TERMINAL = {"DISBURSED", "REJECTED", "WITHDRAWN", "CLOSED"}
BUREAU = "/sim/control/sources/BUREAU"


def options():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", required=True, help="Exact OCI revision of all application images")
    parser.add_argument("--project", required=True, help="Dedicated cabinet-e2e-review… project or the isolated CI project")
    parser.add_argument("--base", required=True, help="Loopback address of that project's gateway")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    address = urlsplit(args.base)
    github_project = (
        args.project == "cabinet-e2e"
        and os.environ.get("GITHUB_ACTIONS") == "true"
        and os.environ.get("COMPOSE_PROJECT_NAME") == args.project
    )
    gitlab_job = os.environ.get("CI_JOB_ID", "")
    gitlab_project = (
        os.environ.get("GITLAB_CI") == "true"
        and gitlab_job.isascii() and gitlab_job.isdecimal()
        and os.environ.get("CI_PROJECT_ID", "").isascii()
        and os.environ.get("CI_PROJECT_ID", "").isdecimal()
        and args.project == os.environ.get("COMPOSE_PROJECT_NAME") == f"cabinet-e2e-{gitlab_job}"
        and os.environ.get("CI_COMMIT_SHA") == args.revision
        and Path(os.environ.get("CI_PROJECT_DIR", "")).resolve() == ROOT
    )
    if not (github_project or gitlab_project) and not re.fullmatch(r"cabinet-e2e-review[a-z0-9]{1,20}", args.project):
        parser.error("Use a dedicated cabinet-e2e-review… fixture project")
    if address.scheme != "http" or address.hostname != "127.0.0.1" or args.base != f"http://127.0.0.1:{address.port}":
        parser.error("Use an explicit loopback port without a path")
    if not re.fullmatch(r"[a-f0-9]{40}", args.revision):
        parser.error("Use a full commit SHA")
    return args


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], text=True, encoding="utf-8").strip()


def fixture_container(project, service):
    matches = docker(
        "ps", "-q", "--filter", f"label=com.docker.compose.project={project}",
        "--filter", f"label=com.docker.compose.service={service}",
    ).splitlines()
    assert len(matches) == 1, f"Expected exactly one fixture service {service}"
    return matches[0]


def check_fixture(api, revision, port):
    for service in ("identity", "credit", "engagement", "external-sim"):
        for container in api.containers(service):
            image = docker("inspect", "--format", "{{.Image}}", container)
            image_revision = docker(
                "image", "inspect", "--format", '{{index .Config.Labels "org.opencontainers.image.revision"}}', image
            )
            assert image_revision == revision, f"Unexpected OCI revision of {service}"
    gateway = fixture_container(api.project, "gateway")
    bindings = json.loads(docker("inspect", "--format", '{{json (index .NetworkSettings.Ports "80/tcp")}}', gateway))
    assert any(b["HostPort"] == str(port) and b["HostIp"] in {"0.0.0.0", "127.0.0.1"} for b in bindings)
    assert api.json("GET", "/api/v1/auth/options")["demoAccounts"], "Use a synthetic cabinet"


def cache_key(secret, scope, subject):
    data = f"cabinet-v1\0{scope}\0{subject}".encode()
    return f"cabinet:v1:{scope}:" + hmac.digest(secret.encode(), data, "sha256").hex()


def wait_for(probe, explanation, timeout=90):
    start = time.monotonic()
    while time.monotonic() - start < timeout:
        value = probe()
        if value:
            return
        time.sleep(.25)
    raise AssertionError(explanation)


def warm_entry(api, database, valkey, secret, application_id, client, transition_name, status_timeout=180):
    route = f"/api/v1/applications/{application_id}"
    api.wait(client, application_id, {"MANUAL_REVIEW"}, timeout=status_timeout)
    observed_at = time.monotonic()
    transitions = [evt for evt in api.json("GET", route + "/events", token=client) if evt["toStatus"] == "MANUAL_REVIEW"]
    transition = transitions[-1]
    event_id = str(UUID(transition["id"]))
    assert transition["event"] == transition_name, f"Expected {transition_name}, got {transition['event']}"
    entry = {"event_id": event_id, "transition": transition_name}
    if transition_name == "SOURCE_TIMED_OUT":
        sources = transition["payload"].get("sources")
        assert sources == ["BUREAU"], f"Expected an isolated BUREAU timeout, got {sources}"
        entry["timed_out_sources"] = sources
    advice_route = f"{ADVICE}/{application_id}/advice?eventId={event_id}"
    advice = api.service("credit", "GET", advice_route)["body"]
    revision = advice["adviceRevision"].lower()
    assert advice["applicationId"] == application_id and re.fullmatch(r"[a-f0-9]{64}", revision)
    expected = "\n".join([advice["verdict"]] + [f"— {r['title']}. {r['text']}" for r in advice["items"][:3]])
    payload = {"schemaVersion": 1, "eventId": event_id, "applicationId": application_id}

    def probe():
        for service in ("credit", "engagement"):
            row = delivery_rows(database, event_id, service)
            if not row or row[0]["state"] != "DELIVERED":
                return False
            assert len(row) == 1 and row[0]["payload"] == payload
        current_event = valkey_get(valkey, cache_key(secret, "assistant-review-event", application_id))
        warmed = valkey_get(valkey, cache_key(secret, "assistant-review-summary", f"1:{application_id}:{revision}"))
        return current_event == event_id and warmed == expected

    wait_for(probe, "Credit event did not produce a current cached summary")
    entry.update(
        advice_revision=revision,
        warm_confirmation_ms_after_status=round((time.monotonic() - observed_at) * 1000, 3),
        credit_outbox_delivered=True,
        engagement_outbox_delivered=True,
        cache_verified_before_summary_request=True,
    )
    return payload, expected, entry


def delivery_rows(database, event_id, service="engagement"):
    assert str(UUID(event_id)) == event_id
    assert service in {"credit", "engagement"}
    statement = (
        "select coalesce(json_agg(row_to_json(event)), '[]') from "
        "(select state, attempts, updated_at, payload from delivery_outbox "
        f"where channel = '{CHANNEL}' and event_key = '{event_id}') event"
    )
    return json.loads(docker("exec", database, "psql", "-X", "-U", "cabinet", "-d", service, "-tAc", statement))


def valkey_get(container, key):
    return subprocess.check_output(
        ["docker", "exec", container, "sh", "-c", 'VALKEYCLI_AUTH="$VALKEY_PASSWORD" exec valkey-cli --raw GET "$1"', "sh", key],
        text=True,
        encoding="utf-8",
    ).removesuffix("\n")


def record_summary(api, staff, application_id, expected, entry):
    response = api.request("POST", SUMMARY, {"applicationId": application_id}, staff)
    assert response["body"]["mode"] == "RULES" and response["body"]["text"] == expected
    entry["first_summary_http_ms"] = round(response["duration_ms"], 3)


def bureau_mode(api):
    return next(source["mode"] for source in api.json("GET", "/sim/control")["sources"] if source["source"] == "BUREAU")


def open_application(api, client, scenario):
    created = api.json("POST", "/api/v1/applications", {**scenario, "bureauConsent": True}, client, expected=(201,))
    return str(UUID(created["id"]))


def withdraw(api, client, application_id):
    return api.request("POST", f"/api/v1/applications/{application_id}/withdraw", token=client, expected=None)["status"]


def check_bureau_timeout(api, database, valkey, secret, client, staff, scenario, report):
    previous = bureau_mode(api)
    application_id = None
    try:
        api.json("PUT", BUREAU, {"mode": "DOWN"})
        assert bureau_mode(api) == "DOWN"
        application_id = open_application(api, client, scenario)
        report.update(source_timeout_application_id=application_id, source_timeout_cleanup_pending=True)
        api.json("POST", f"/api/v1/applications/{application_id}/submit", token=client)
        _, expected, entry = warm_entry(api, database, valkey, secret, application_id, client, "SOURCE_TIMED_OUT", status_timeout=300)
        record_summary(api, staff, application_id, expected, entry)
        report["source_timeout_entry"] = entry
    finally:
        try:
            if application_id:
                report["source_timeout_cleanup_http"] = withdraw(api, client, application_id)
                report["source_timeout_cleanup_pending"] = report["source_timeout_cleanup_http"] != 200
        finally:
            api.json("PUT", BUREAU, {"mode": previous})
            report["bureau_mode_restored"] = bureau_mode(api) == previous
    assert report["source_timeout_cleanup_http"] == 200 and report["bureau_mode_restored"]


def main():
    args = options()
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    assert actual == args.revision, "Checkout and requested OCI revision differ"

    api = DockerCabinet(args.base, args.project)
    check_fixture(api, args.revision, urlsplit(args.base).port)
    database = fixture_container(args.project, "postgres")
    valkey = fixture_container(args.project, "valkey")
    secret = api.test_secret("engagement", "JWT_SECRET")
    client = api.sign_in("logistic@demo.test")["token"]
    time.sleep(2.1)
    staff = api.sign_in("underwriter@demo.test")["token"]
    existing = api.json("GET", "/api/v1/applications", token=client)
    assert all(app["status"] in TERMINAL for app in existing), "Use a clean fixture"
    assert bureau_mode(api) == "OK", "Use a fixture with the bureau source in its normal mode"
    scenario = next(row["scenario"]["request"] for row in api.json("GET", "/sim/control")["companies"] if row["inn"] == "0001001021")

    report = {"revision": args.revision, "project": args.project, "started_at": datetime.now(timezone.utc).isoformat(), "entries": [], "passed": False}
    with args.output.open("x", encoding="utf-8") as output:
        application_id = None
        try:
            application_id = open_application(api, client, scenario)
            report["application_id"] = application_id
            route = f"/api/v1/applications/{application_id}"
            api.json("POST", route + "/submit", token=client)
            first, expected, entry = warm_entry(api, database, valkey, secret, application_id, client, "TO_MANUAL_REVIEW")
            record_summary(api, staff, application_id, expected, entry)
            report["entries"].append(entry)

            review_route = f"/api/v1/underwriting/applications/{application_id}/request-info"
            api.json("POST", review_route, {"items": [{"question": "Уточните синтетические сведения"}]}, staff)
            api.wait(client, application_id, {"INFO_REQUESTED"})
            stale_path = f"{ADVICE}/{application_id}/advice?eventId={first['eventId']}"
            api.service("credit", "GET", stale_path, expected=(409,))
            before_replay = delivery_rows(database, first["eventId"])
            assert len(before_replay) == 1 and before_replay[0]["state"] == "DELIVERED"
            api.service("engagement", "POST", EVENTS, first, expected=(204,))
            assert delivery_rows(database, first["eventId"]) == before_replay

            api.json("POST", route + "/info-provided", {"answer": "Дополнение синтетической заявки"}, client)
            second, expected, entry = warm_entry(api, database, valkey, secret, application_id, client, "TO_MANUAL_REVIEW")
            assert second["eventId"] != first["eventId"]
            assert entry["advice_revision"] != report["entries"][0]["advice_revision"]
            # The worker has delivered another real job after the replay.
            assert delivery_rows(database, first["eventId"]) == before_replay
            record_summary(api, staff, application_id, expected, entry)
            report["entries"].append(entry)

            api.service("credit", "GET", stale_path, expected=(409,))
            assert valkey_get(valkey, cache_key(secret, "assistant-review-event", application_id)) == second["eventId"]
            report.update(stale_event_status=409, replay_receipt_unchanged_after_next_delivery=True)

            report["cleanup_http"] = withdraw(api, client, application_id)
            assert report["cleanup_http"] == 200
            application_id = None
            check_bureau_timeout(api, database, valkey, secret, client, staff, scenario, report)
            report["passed"] = True
        finally:
            try:
                if application_id:
                    report["cleanup_http"] = withdraw(api, client, application_id)
            finally:
                report["finished_at"] = datetime.now(timezone.utc).isoformat()
                json.dump(report, output, ensure_ascii=False, indent=2)
                output.write("\n")
    if not report["passed"]:
        raise AssertionError("Integration check or fixture cleanup did not pass")


if __name__ == "__main__":
    main()
