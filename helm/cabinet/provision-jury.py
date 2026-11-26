#!/usr/bin/env python3
"""Activate only the fixed synthetic client, agent and underwriter for jury acceptance."""

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess

import bcrypt


SYNTHETIC_ACCOUNTS = {
    "CLIENT": ("9b2f0c4a-6d1e-4f2a-8c3b-000000000001", "client@demo.test"),
    "AGENT": ("9b2f0c4a-6d1e-4f2a-8c3b-000000000201", "agent@demo.test"),
    "UNDERWRITER": ("9b2f0c4a-6d1e-4f2a-8c3b-000000000102", "underwriter@demo.test"),
}
COMPANY = "6f1d4c1e-0b8e-4c55-9a51-000000000001"


def read_credentials(path):
    with path.open() as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600:
            raise ValueError("credential file must be a regular file with mode 0600")
        accounts = json.load(source)
    if not isinstance(accounts, dict) or accounts.keys() != SYNTHETIC_ACCOUNTS.keys():
        raise ValueError("credential file must contain CLIENT, AGENT and UNDERWRITER")
    emails, passwords = set(), set()
    for account in accounts.values():
        if not isinstance(account, dict):
            raise ValueError("each role requires email and password")
        email, password = account.get("email"), account.get("password")
        if not isinstance(email, str) or not re.fullmatch(r"jury-[a-z0-9][a-z0-9-]{0,47}@demo[.]test", email):
            raise ValueError("each role requires a distinct jury-* email at demo.test")
        if not isinstance(password, str) or not 16 <= len(password.encode("utf-8")) <= 72:
            raise ValueError("each role requires a distinct 16–72-byte UTF-8 password")
        emails.add(email)
        passwords.add(password)
    if len(emails) != len(accounts) or len(passwords) != len(accounts):
        raise ValueError("email addresses and passwords must be unique")
    return accounts


def provision_sql(accounts):
    statements = [
        "begin;",
        "set local log_statement = 'none';",
        "set local log_min_error_statement = 'panic';",
        "select pg_advisory_xact_lock(17127, 2);",
        "do $jury$ begin",
    ]
    for role, (user_id, seed_email) in SYNTHETIC_ACCOUNTS.items():
        account = accounts[role]
        digest = bcrypt.hashpw(account["password"].encode(), bcrypt.gensalt(rounds=12)).decode()
        company_check = f"and company_id = '{COMPANY}' and can_sign" if role == "CLIENT" else "and company_id is null"
        statements.extend([
            f"update users set email = '{account['email']}', password_hash = '{digest}', status = 'ACTIVE'",
            f"where id = '{user_id}' and email = '{seed_email}' and role = '{role}' and status = 'BLOCKED' {company_check};",
            "if not found then raise exception 'Expected blocked synthetic seed account is unavailable'; end if;",
        ])
    agent_id = SYNTHETIC_ACCOUNTS["AGENT"][0]
    statements.extend([
        "if not exists (select 1 from agent_clients",
        f"where agent_user_id = '{agent_id}' and company_id = '{COMPANY}' and status = 'ACTIVE')",
        "then raise exception 'Synthetic jury mandate is unavailable'; end if;",
        "end $jury$;",
        "commit;",
    ])
    return "\n".join(statements)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--credentials", required=True, type=Path)
    args = parser.parse_args()
    try:
        accounts = read_credentials(args.credentials)
    except (OSError, ValueError) as error:
        parser.exit(2, f"Credential validation failed: {error}\n")
    result = subprocess.run(
        ["kubectl", "--context", args.context, "--namespace", args.namespace, "exec", "-i", "statefulset/postgres",
         "--", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-U", "cabinet", "-d", "identity"],
        input=provision_sql(accounts), text=True, capture_output=True, check=False,
    )
    if result.returncode:
        parser.exit(1, "Jury provisioning failed. Check Kubernetes exec access and the fixed blocked seed accounts before retrying.\n")
    print("Activated three synthetic jury accounts and verified the agent mandate.")


if __name__ == "__main__":
    main()
