#!/usr/bin/env python3
"""Exercise GoAlert contacts using only the private test Mailpit."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import secrets
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from smoke import create_oncall, get_json, go_query, login, wait_for


def loopback_url(value):
    parsed = urlsplit(value)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}:
        raise argparse.ArgumentTypeError("Use a loopback port-forward URL")
    return f"{parsed.scheme}://{parsed.netloc}"


def message_for(mailpit, email, predicate):
    for item in get_json(f"{mailpit}/api/v1/messages")["messages"]:
        if any(recipient["Address"] == email for recipient in item["To"]):
            message = get_json(f"{mailpit}/api/v1/message/{item['ID']}")
            if predicate(message):
                return message
    return None


def create_contact(query, mailpit, username, email):
    user_id = query(
        """mutation($input: CreateUserInput!) { createUser(input: $input) { id } }""",
        input=dict(username=username, password="Qa!" + secrets.token_hex(20),
                   name=username, email=email, role="user"),
    )["createUser"]["id"]
    contact = query(
        """mutation($input: CreateUserContactMethodInput!) {
            createUserContactMethod(input: $input) { id disabled }
        }""",
        input=dict(userID=user_id, name="Private Mailpit",
                   dest=dict(type="builtin-smtp-email", args=dict(email_address=email)),
                   newUserNotificationRule=dict(delayMinutes=0)),
    )["createUserContactMethod"]
    if contact["disabled"]:
        contact_id = dict(contactMethodID=contact["id"])
        query(
            """mutation($input: SendContactMethodVerificationInput!) {
                sendContactMethodVerification(input: $input)
            }""",
            input=contact_id,
        )
        code_pattern = re.compile(r"\b\d{6}\b")
        message = wait_for(
            f"Verification captured for {email}",
            lambda: message_for(mailpit, email, lambda item: code_pattern.search(item["Text"])),
        )
        code = int(code_pattern.search(message["Text"]).group())
        query(
            """mutation($input: VerifyContactMethodInput!) { verifyContactMethod(input: $input) }""",
            input=dict(**contact_id, code=code),
        )
    return user_id


def webhook(url, label, status):
    labels = dict(alertname=label, environment="synthetic")
    annotations = dict(summary=label)
    alert = dict(status=status, labels=labels, annotations=annotations,
                 fingerprint=label, startsAt=datetime.now(timezone.utc).isoformat())
    payload = dict(version="4", status=status, receiver="synthetic", groupKey=label,
                   groupLabels=labels, commonLabels=labels, commonAnnotations=annotations,
                   alerts=[alert])
    request = Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    with urlopen(request, timeout=5) as response:
        response.read()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--goalert", type=loopback_url, default="http://127.0.0.1:8081")
    parser.add_argument("--mailpit", type=loopback_url, default="http://127.0.0.1:8025")
    parser.add_argument("--admin-user", required=True)
    parser.add_argument("--admin-password-file", type=Path, required=True)
    args = parser.parse_args()
    if args.admin_password_file.stat().st_mode & 0o077:
        parser.error("Protect the administrator password file with mode 0600")

    get_json(f"{args.mailpit}/api/v1/messages")
    token = login(args.goalert, args.admin_user, args.admin_password_file.read_text().strip())

    def query(statement, **variables):
        return go_query(args.goalert, token, statement, **variables)

    smtp = {
        "SMTP.Enable": "true",
        "SMTP.Address": "cabinet-goalert-mailpit:1025",
        "SMTP.From": "goalert@example.invalid",
        "SMTP.DisableTLS": "true",
        "SMTP.Username": "",
        "SMTP.Password": "",
    }
    current = {item["id"]: item["value"] for item in query("{ config(all: true) { id value } }")["config"]}
    if current.get("SMTP.Enable") == "true" and current.get("SMTP.Address") != smtp["SMTP.Address"]:
        parser.error("This GoAlert has a different active SMTP relay. Use the isolated test release.")
    query(
        """mutation($input: [ConfigValueInput!]) { setConfig(input: $input) }""",
        input=[dict(id=name, value=value) for name, value in smtp.items()],
    )
    suffix = secrets.token_hex(3)
    addresses = [f"pump-{role}-{suffix}@example.invalid" for role in ("primary", "backup")]
    primary, backup = [create_contact(query, args.mailpit, email.partition("@")[0], email) for email in addresses]
    label = f"CabinetSmoke-{suffix}"
    service = create_oncall(query, primary, backup, name=label)
    wait_for(
        "GoAlert rotation has its synthetic primary",
        lambda: any(user["userID"] == primary for user in query(
            "query($id: ID!) { service(id: $id) { onCallUsers { userID } } }",
            id=service["id"],
        )["service"]["onCallUsers"]),
    )
    key = urlsplit(service["integrationKeys"][0]["href"])
    endpoint = f"{args.goalert}{key.path}?{key.query}"
    webhook(endpoint, label, "firing")

    def incident():
        rows = query(
            "query($input: AlertSearchOptions) { alerts(input: $input) { nodes { alertID status } } }",
            input=dict(filterByServiceID=[service["id"]]),
        )["alerts"]["nodes"]
        return rows[0] if rows else None

    try:
        for email in addresses:
            wait_for(
                f"Synthetic alert captured for {email}",
                lambda: message_for(
                    args.mailpit, email, lambda msg: label in msg["Subject"] or label in msg["Text"]
                ),
                timeout=100,
            )
        current = wait_for("GoAlert incident created", incident)
        query(
            """mutation($input: UpdateAlertsInput!) { updateAlerts(input: $input) { status } }""",
            input=dict(alertIDs=[current["alertID"]], newStatus="StatusAcknowledged"),
        )
        wait_for("GoAlert acknowledged", lambda: incident()["status"] == "StatusAcknowledged")
    finally:
        webhook(endpoint, label, "resolved")
    wait_for("GoAlert resolved the incident", lambda: incident()["status"] == "StatusClosed")


if __name__ == "__main__":
    main()
