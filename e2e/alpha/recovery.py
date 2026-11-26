#!/usr/bin/env python3
"""Drive the synthetic restore transitions and verify Credit, Kafka and Analytics evidence."""

import argparse
import json
import os
from pathlib import Path
import sys

from funnel import FIXTURE, fetch, local_opener, loopback_origin, read_json, read_rows, require


def fixture_id(number):
    return f"10000000-0000-4000-8000-{number:012d}"


TARGET = fixture_id(5)
STUCK = fixture_id(10)
GHOST = fixture_id(99)
OLD_EVENTS = (
    {
        "eventId": "70000000-0000-4000-8000-000000000001",
        "applicationId": TARGET,
        "applicationVersion": 3,
        "status": "REJECTED",
        "occurredAt": "2026-03-01T00:00:00Z",
    },
    {
        "eventId": "70000000-0000-4000-8000-000000000002",
        "applicationId": GHOST,
        "applicationVersion": 8,
        "status": "CLOSED",
        "occurredAt": "2026-03-01T00:00:01Z",
    },
)
RECEIPT_KEYS = ("eventId", "applicationId", "applicationVersion", "status")


def withdraw(args):
    target, variable = {
        "stuck": (STUCK, "ALPHA_QA_STUCK_CLIENT_TOKEN"),
        "recovered": (TARGET, "ALPHA_QA_RECOVERY_CLIENT_TOKEN"),
    }[args.application]
    token = os.environ.get(variable)
    require(token, f"Missing {variable}")
    url = f"{loopback_origin(args.url)}/api/v1/applications/{target}/withdraw"
    response = fetch(local_opener(), url, token, method="POST")
    require(response["status"] == 200, f"Withdraw {target}: expected 200, got {response['status']}")
    require(response["body"]["id"] == target and response["body"]["status"] == "WITHDRAWN",
            f"Withdraw {target}: unexpected application or status")
    print(json.dumps({"applicationId": target, "httpStatus": 200, "status": "WITHDRAWN"}))


def exact_rows(directory, name, expected):
    rows, _ = read_rows(read_json(directory / name), name)
    difference = sorted(set(rows.items()) ^ set(expected.items()))
    require(not difference, f"{name}: unexpected applications, versions or statuses: {difference}")


def credit_event(snapshot, application_id, version, state):
    outbox = [row for row in snapshot["outbox"] if row["payload"]["applicationId"] == application_id]
    require(len(outbox) == 1, f"{application_id}: expected one outbox row")
    row = outbox[0]
    event = row["payload"]
    require(event["applicationVersion"] == version and event["status"] == "WITHDRAWN", "Unexpected withdrawal event")
    require(row["state"] == state and row["event_key"] == event["eventId"], "Unexpected outbox state or event key")
    journal = [item for item in snapshot["journal"] if item["application_id"] == application_id]
    require(journal == [{
        "id": event["eventId"], "application_id": application_id, "event": "WITHDRAW", "to_status": "WITHDRAWN",
    }], f"{application_id}: journal does not match outbox")
    return event


def verify_receipts(directory, name, events):
    receipts = read_json(directory / name)
    expected = [{key: event[key] for key in RECEIPT_KEYS} for event in events]
    order = lambda entry: entry["eventId"]
    require(sorted(receipts, key=order) == sorted(expected, key=order),
            f"{name}: unexpected receipts for the restore tripwires")


def verify_topic(directory, name, events):
    records = []
    for line in (directory / name).read_text(encoding="utf-8").splitlines():
        key, payload = line.split("\t", 1)
        event = json.loads(payload)
        require(key == event["applicationId"], f"{name}: key differs from applicationId")
        require(event in events, f"{name}: unexpected event {event['eventId']}")
        records.append(event)
    require(all(event in records for event in events), f"{name}: expected broker records are missing")


def verify(directory):
    backup = read_json(directory / "backup-credit.json")
    restored = read_json(directory / "restored-before-credit.json")
    final = read_json(directory / "recovered-credit.json")
    require(backup == restored, "Restored tripwires differ from the database before backup")
    backup_rows = {row["id"]: (row["version"], row["status"]) for row in backup["applications"]}
    require(backup_rows == {TARGET: (1, "SUBMITTED"), STUCK: (5, "WITHDRAWN")},
            "Backup should hold the lower target version, the stuck withdrawal and no ghost")
    stuck = credit_event(backup, STUCK, 5, "PENDING")
    require(len(backup["outbox"]) == 1 and backup["outbox"][0]["attempts"] == 0, "Backup needs one undelivered event")

    frozen = {fixture_id(number): pair for number, pair in FIXTURE.items()} | {STUCK: (5, "WITHDRAWN")}
    old = {fixture_id(number): pair for number, pair in FIXTURE.items()} | {
        TARGET: (3, "REJECTED"), GHOST: (8, "CLOSED"),
    }
    exact_rows(directory, "old-projection.json", old)
    verify_receipts(directory, "old-receipts.json", OLD_EVENTS)
    verify_topic(directory, "old-topic.tsv", OLD_EVENTS)
    exact_rows(directory, "new-initial-projection.json", frozen)
    verify_receipts(directory, "new-initial-receipts.json", ())

    new = frozen | {TARGET: (2, "WITHDRAWN")}
    legitimate = credit_event(final, TARGET, 2, "DELIVERED")
    require(legitimate["eventId"] != stuck["eventId"], "New transition reused the stuck event ID")
    exact_rows(directory, "recovered-sql.json", new)
    exact_rows(directory, "new-final-projection.json", new)
    verify_receipts(directory, "new-final-receipts.json", (legitimate,))
    verify_topic(directory, "new-topic.tsv", (legitimate,))
    print("Restore evidence agrees: old v3 and ghost observed, stuck event preserved in backup and absent from the new stream, new v2 applied")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("emit-old")
    writer = commands.add_parser("withdraw")
    writer.add_argument("--url", required=True)
    writer.add_argument("--application", choices=("stuck", "recovered"), required=True)
    check = commands.add_parser("verify")
    check.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "emit-old":
            for event in OLD_EVENTS:
                print(event["applicationId"], json.dumps(event, separators=(",", ":")), sep="\t")
        elif args.command == "withdraw":
            withdraw(args)
        else:
            verify(args.directory)
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f"Alpha QA recovery: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
