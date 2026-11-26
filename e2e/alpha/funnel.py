#!/usr/bin/env python3
"""Capture and verify the synthetic Alpha funnel, using the Python standard library."""

import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import sys
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


FUNNEL = "/api/v1/analytics/funnel"
ROLES = {
    "anonymous": 401, "invalid": 401, "client": 403, "unrelated_client": 403, "agent": 403,
    "manager": 200, "underwriter": 200, "risk_admin": 200,
}
FEB = "2026-02-01T00:00:00Z"
MAR = "2026-03-01T00:00:00Z"
APR = "2026-04-01T00:00:00Z"
PERIODS = {
    "primary": (FEB, MAR), "next": (MAR, APR), "default_from": (None, MAR),
    "equal": (FEB, FEB), "reversed": (APR, MAR), "malformed": ("not-an-instant", MAR),
}
FIELDS = {
    "from", "to", "funnel", "stageTimes", "automaticOutcomes", "manualOutcomes",
    "manualOverrides", "disbursedAmount", "currentStatuses",
}
FIXTURE = {
    3: (1, "WITHDRAWN"), 4: (3, "WITHDRAWN"), 5: (1, "SUBMITTED"), 6: (5, "MANUAL_REVIEW"),
    7: (6, "REJECTED"), 8: (9, "DISBURSED"), 9: (8, "CLOSED"), 10: (4, "SUBMITTED"),
}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fetch(opener, url, token=None):
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    try:
        response = opener.open(Request(url, headers=headers), timeout=10)
    except HTTPError as error:
        response = error
    with response:
        return {"status": response.status, "body": json.load(response)}


def capture(args):
    url = urlsplit(args.url)
    loopback = url.hostname in {"127.0.0.1", "::1"} and url.port is not None
    origin_only = not (url.username or url.password or url.path.rstrip("/") or url.query or url.fragment)
    require(url.scheme in {"http", "https"} and loopback and origin_only,
            "Use the isolated stack's explicit loopback host and port")
    require(re.fullmatch(r"[0-9a-f]{40}", args.sha), "Supply the full Git SHA")
    base = args.url.rstrip("/")
    opener = build_opener(ProxyHandler({}), NoRedirect())
    tokens = {"anonymous": None, "invalid": "invalid-alpha-qa-token"}
    for role in ROLES.keys() - tokens.keys():
        name = f"ALPHA_QA_{role.upper()}_TOKEN"
        require(os.environ.get(name), f"Missing {name}")
        tokens[role] = os.environ[name]

    def probe(role, period):
        query = {key: value for key, value in zip(("from", "to"), PERIODS[period]) if value is not None}
        return fetch(opener, f"{base}{FUNNEL}?{urlencode(query)}", tokens[role])

    roles = {role: probe(role, "primary") for role in ROLES}
    periods = {period: probe("manager", period) for period in PERIODS if period != "primary"}
    openapi = fetch(opener, base + "/v3/api-docs/credit")
    require(openapi["status"] == 200, "Credit OpenAPI returned a non-200 response")
    return {"sha": args.sha, "mode": args.mode, "roles": roles, "periods": periods, "openapi": openapi["body"]}


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def assert_map(value, expected, label):
    require(isinstance(value, dict) and all(type(count) is int and count > 0 for count in value.values()),
            f"{label}: expected a status map with positive integer counts")
    require(value == expected, f"{label}: status map {value} differs from {dict(expected)}")


def read_rows(snapshot, label):
    rows = {}
    for row in snapshot["rows"]:
        application_id, version = row["id"], row["version"]
        require(application_id not in rows, f"{label}: repeated application {application_id}")
        require(type(version) is int and version >= 0, f"{label}: invalid version")
        rows[application_id] = (version, row["status"])
    counts = Counter(status for _, status in rows.values())
    assert_map(snapshot["currentStatuses"], counts, label)
    return rows, counts


def validate_period(body, counts, period, label):
    require(set(body) == FIELDS, f"{label}: different funnel response fields")
    start, end = PERIODS[period]
    require((body["from"], body["to"]) == (start or "2026-01-30T00:00:00Z", end),
            f"{label}: unexpected period")
    assert_map(body["currentStatuses"], counts, label)
    submitted = next(item["applications"] for item in body["funnel"] if item["code"] == "SUBMITTED")
    require(submitted == (1 if period == "next" else 2), f"{label}: incorrect [from, to) cohort")
    require(body["disbursedAmount"] == (0 if period == "next" else 1000000), f"{label}: incorrect disbursements")
    outcomes = [{"outcome": "REJECT", "decisions": 1}] if period == "next" else []
    require(body["automaticOutcomes"] == outcomes, f"{label}: incorrect automatic outcomes")


def validate_capture(data, counts):
    label = f'{data["sha"]} {data["mode"]}'
    roles = data["roles"]
    require(set(roles) == ROLES.keys(), f"{label}: incomplete role matrix")
    for role, status in ROLES.items():
        response = roles[role]
        require(response["status"] == status, f"{label} {role}: expected HTTP {status}")
        if status == 200:
            validate_period(response["body"], counts, "primary", f"{label} {role}")
            require(response["body"] == roles["manager"]["body"], f"{label} {role}: results differ from MANAGER")
    periods = data["periods"]
    require(set(periods) == PERIODS.keys() - {"primary"}, f"{label}: incomplete period matrix")
    for name in ("next", "default_from"):
        require(periods[name]["status"] == 200, f"{label} {name}: expected HTTP 200")
        validate_period(periods[name]["body"], counts, name, f"{label} {name}")
    for name in ("equal", "reversed", "malformed"):
        require(periods[name]["status"] == 400, f"{label} {name}: expected HTTP 400")


def openapi_contract(document):
    operation = document["paths"][FUNNEL]["get"]
    schemas = document["components"]["schemas"]
    response_schemas = {name: schemas[name] for name in
                        ("Analytics", "FunnelStep", "StageTime", "OutcomeCount", "ErrorResponse")}
    bearer = document["components"]["securitySchemes"]["bearerAuth"]
    return operation, response_schemas, bearer


def stable_response(response):
    if response["status"] == 200:
        return response
    body = {key: value for key, value in response["body"].items() if key not in {"requestId", "timestamp"}}
    return {"status": response["status"], "body": body}


def verify(args):
    rows, counts = read_rows(read_json(args.sql), "credit SQL")
    expected = {f"10000000-0000-4000-8000-{n:012d}": pair for n, pair in FIXTURE.items()}
    require(rows == expected, f"Credit fixture rows differ at {sorted(set(rows.items()) ^ set(expected.items()))}")
    print(f"credit SQL: {len(rows)} visible fixture rows and exact status map")
    require(not args.snapshot_versions or args.projection, "--snapshot-versions requires --projection")
    if args.projection:
        projection, _ = read_rows(read_json(args.projection), "analytics projection")
        sql_statuses = {key: status for key, (_, status) in rows.items()}
        projected_statuses = {key: status for key, (_, status) in projection.items()}
        difference = sorted(set(projected_statuses.items()) ^ set(sql_statuses.items()))
        require(not difference, f"Projection statuses differ from credit SQL at {difference}")
        print("analytics projection: exact status and count parity")
        if args.snapshot_versions:
            difference = sorted(set(projection.items()) ^ set(rows.items()))
            require(not difference, f"Projection versions differ from the frozen source snapshot at {difference}")
            print("analytics projection: versions match the frozen source snapshot")
    if not args.baseline:
        require(not args.candidate, "Candidate captures require a baseline capture")
        return
    baseline = read_json(args.baseline)
    require(baseline["mode"] == "sql", "Baseline capture must use SQL mode")
    validate_capture(baseline, counts)
    baseline_contract = openapi_contract(baseline["openapi"])
    print(f'{baseline["sha"]} SQL baseline: role and period probes agree with SQL')
    for path in args.candidate:
        candidate = read_json(path)
        validate_capture(candidate, counts)
        require(openapi_contract(candidate["openapi"]) == baseline_contract, f"{path}: OpenAPI operation or schemas changed")
        for group in ("roles", "periods"):
            for key in baseline[group]:
                require(stable_response(candidate[group][key]) == stable_response(baseline[group][key]),
                        f"{path}: changed {group} response for {key}")
        print(f'{candidate["sha"]} {candidate["mode"]}: HTTP and OpenAPI match SQL baseline')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    capture_parser = commands.add_parser("capture")
    capture_parser.add_argument("--url", required=True)
    capture_parser.add_argument("--sha", required=True)
    capture_parser.add_argument("--mode", choices=("sql", "shadow", "projected", "off"), required=True)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--sql", required=True)
    verify_parser.add_argument("--projection")
    verify_parser.add_argument("--snapshot-versions", action="store_true", help="compare an unchanged bootstrap snapshot")
    verify_parser.add_argument("--baseline")
    verify_parser.add_argument("--candidate", action="append", default=[])
    args = parser.parse_args()
    try:
        if args.command == "capture":
            json.dump(capture(args), sys.stdout, ensure_ascii=False, indent=2, sort_keys=True)
            print()
        else:
            verify(args)
    except (ValueError, KeyError, StopIteration, TypeError, OSError) as error:
        print(f"Alpha QA: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
