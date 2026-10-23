#!/usr/bin/env python3
"""Real two-replica limits, immediate revocation, Valkey persistence and failure recovery."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import math
from ipaddress import IPv6Address
from pathlib import Path
import re
import subprocess
import sys
import time
from urllib.parse import urlencode, urlparse, parse_qs
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet  # noqa: E402


def docker(*args, data=None):
    response = subprocess.run(["docker", *args], input=data, capture_output=True, timeout=180)
    if response.returncode:
        raise AssertionError(f"Docker {args[0]} failed ({response.returncode}); arguments and payload omitted")
    return response.stdout.decode("utf-8").strip()


def main():
    parser = argparse.ArgumentParser(description="Общие лимиты и отзыв сессий на отдельном синтетическом стенде")
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--project", default="cabinet-e2e")
    parser.add_argument("--report-name", default="shared-state")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.report_name):
        parser.error("Нужно безопасное имя отчёта")
    api = DockerCabinet(args.base, args.project)
    instances = {service: api.containers(service) for service in ("identity", "credit", "engagement")}
    assert all(len(ids) == 2 for ids in instances.values()), "Нужны два экземпляра каждого из трёх сервисов"
    for service, ids in instances.items():
        for cid in ids:
            env = json.loads(docker("inspect", cid))[0]["Config"]["Env"]
            assert "CABINET_VALKEY_ENABLED=true" in env
            if service == "identity":
                assert "DEMO_ACCOUNTS=true" in env, "Разрешён только синтетический демо-стенд"
    valkeys = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                     "--filter", "label=com.docker.compose.service=valkey").split()
    assert len(valkeys) == 1
    valkey = valkeys[0]
    directory = ROOT / "load/reports" / args.report_name
    directory.mkdir(parents=True, exist_ok=False)
    report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "passed": False,
              "limits": [], "revocations": [], "faults": [], "actions": [],
              "images": {service: list({json.loads(docker("inspect", cid))[0]["Image"] for cid in ids}) for service, ids in instances.items()}}
    stopped = set()
    fault_context = None
    recovery_ips = [str(IPv6Address(int(IPv6Address("2001:db8::")) | (uuid4().int & ((1 << 96) - 1)))) for _ in range(3)]

    def request(cid, method, path, body=None, token=None, headers=None, raw=None, host="http://localhost:8080"):
        command = ["exec", "-i", cid, "curl", "-sS", "--connect-timeout", "2", "--max-time", "15", "-X", method, "-D", "-"]
        hdr = dict(headers or {})
        if token:
            hdr["Authorization"] = "Bearer " + token
        if body is not None:
            raw = json.dumps(body, ensure_ascii=False).encode()
            hdr["Content-Type"] = "application/json"
        for key, value in hdr.items():
            command.extend(["-H", f"{key}: {value}"])
        if raw is not None:
            command.extend(["--data-binary", "@-"])
        command.extend(["-w", "\n%{http_code}", host + path])
        start = time.monotonic()
        reply = docker(*command, data=raw)
        response, code = reply.rsplit("\n", 1)
        head, text = response.split("\r\n\r\n", 1)
        return {"status": int(code), "body": json.loads(text) if text else None,
                "headers": {k.lower(): v.strip() for k, v in (line.split(":", 1) for line in head.splitlines()[1:] if ":" in line)},
                "seconds": round(time.monotonic() - start, 3), "replica": cid[:12], "operation": method + " " + path}

    def checked(reply, status):
        assert reply["status"] == status, f"HTTP {reply['status']}, expected {status}; body omitted"
        if fault_context and status == 503:
            report["faults"].append({"fault": fault_context, **{key: reply[key] for key in ("replica", "operation", "status", "seconds")}})
        return reply["body"]

    def action(name, cid):
        if name == "stop":
            stopped.add(cid)
        docker(name, cid)
        report["actions"].append({"at": datetime.now(timezone.utc).isoformat(), "action": name, "container": cid,
                                  "authorization": "isolated synthetic shared-state verification"})
        if name in {"start", "restart"}:
            deadline = time.monotonic() + 180
            while time.monotonic() < deadline:
                status = json.loads(docker("inspect", cid))[0]["State"]
                if status.get("Health", {}).get("Status") == "healthy":
                    stopped.discard(cid)
                    return
                time.sleep(1)
            raise AssertionError("Container failed to become healthy")

    def valkey_command(*parts):
        # Use the container's existing password environment; never echo the secret or put it in argv.
        return docker("exec", valkey, "sh", "-c", 'VALKEYCLI_AUTH="$VALKEY_PASSWORD" valkey-cli --raw "$@"', "valkey-check", *parts)

    def quota(name, count, call, expected):
        start = time.monotonic()
        with ThreadPoolExecutor(max_workers=8) as pool:
            replies = list(pool.map(call, range(count)))
        actual = dict(Counter(reply["status"] for reply in replies))
        report["limits"].append({"operation": name, "requests": count, "statuses": actual,
                                 "replicas": sorted({r["replica"] for r in replies}), "seconds": round(time.monotonic() - start, 3),
                                 "p95_seconds": sorted(r["seconds"] for r in replies)[math.ceil(len(replies) * .95) - 1],
                                 "max_request_seconds": max(r["seconds"] for r in replies)})
        assert actual == expected, f"{name}: {actual}, expected {expected}"
        assert all(int(r["headers"].get("retry-after", "0")) > 0 for r in replies if r["status"] == 429)
        print(json.dumps(report["limits"][-1], ensure_ascii=False), flush=True)

    def sign_in(email, password="Test2026safe"):
        return checked(request(instances["identity"][0], "POST", "/api/v1/auth/sign-in", {"email": email, "password": password}), 200)["token"]

    def mail_token(email, path, previous=None):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            messages = checked(request(instances["identity"][0], "GET", "/api/v1/search?" + urlencode({"query": "to:" + email}),
                                       host="http://mailpit:8025"), 200)["messages"]
            if messages:
                content = checked(request(instances["identity"][0], "GET", "/api/v1/message/" + messages[0]["ID"], host="http://mailpit:8025"), 200)["Text"]
                match = re.search(r"https?://\S+", content)
                assert match is not None
                link = urlparse(match.group())
                assert link.path == path and not link.query
                candidate = parse_qs(link.fragment)["token"][0]
                if candidate != previous:
                    return candidate
            time.sleep(0.5)
        raise AssertionError("Synthetic account mail was not delivered")

    def upload(cid, token, application):
        boundary = "shared-" + uuid4().hex
        raw = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.pdf"\r\n'
               'Content-Type: application/pdf\r\n\r\n%PDF-1.7\nShared limits fixture\n%%EOF\n'
               f'\r\n--{boundary}--\r\n').encode()
        return request(cid, "POST", f"/api/v1/applications/{application}/documents?type=OTHER", token=token, raw=raw,
                       headers={"Content-Type": "multipart/form-data; boundary=" + boundary})

    def revoked(name, token, expected=401):
        observations = []
        start = time.monotonic()
        for service, path in (("identity", "/api/v1/auth/session"), ("credit", "/api/v1/applications"), ("engagement", "/api/v1/notifications")):
            for cid in instances[service]:
                reply = request(cid, "GET", path, token=token)
                checked(reply, expected)
                observations.append({key: reply[key] for key in ("replica", "status", "seconds")})
        report["revocations"].append({"action": name, "observations": observations,
                                      "all_observed_seconds_after_ack": round(time.monotonic() - start, 3)})

    try:
        assert checked(request(instances["identity"][0], "GET", "/api/v1/auth/options"), 200)["mailAvailable"]
        owner = api.register("shared-limits")
        account = api.register("shared-revocation")
        app = api.create(owner["token"], revenue=999_000_000)
        app_id = app["id"]
        route = f"/api/v1/applications/{app_id}"
        unknown = "shared-unknown-" + uuid4().hex + "@example.test"
        quota("signin", 12, lambda i: request(instances["identity"][i % 2], "POST", "/api/v1/auth/sign-in",
                                             {"email": unknown, "password": "Wrong2026safe"}, headers={"X-Real-IP": recovery_ips[0]}), {401: 5, 429: 7})
        quota("signin-ip", 38, lambda i: request(instances["identity"][i % 2], "POST", "/api/v1/auth/sign-in",
                                                 {"email": f"shared-{uuid4().hex}@example.test", "password": "Wrong2026safe"},
                                                 headers={"X-Real-IP": recovery_ips[1]}), {401: 30, 429: 8})
        quota("recovery-email", 8, lambda i: request(instances["identity"][i % 2], "POST", "/api/v1/auth/password-recovery",
                                                     {"email": unknown}, headers={"X-Real-IP": recovery_ips[0]}), {200: 3, 429: 5})
        quota("recovery-ip", 24, lambda i: request(instances["identity"][i % 2], "POST", "/api/v1/auth/password-recovery",
                                                  {"email": f"shared-{uuid4().hex}@example.test"}, headers={"X-Real-IP": recovery_ips[1]}), {200: 20, 429: 4})
        quota("upload", 40, lambda i: upload(instances["credit"][i % 2], owner["token"], app_id), {201: 30, 429: 10})
        assert len(api.json("GET", route, token=owner["token"])["documents"]) == 30
        api.json("POST", route + "/submit", token=owner["token"])
        api.wait(owner["token"], app_id, {"MANUAL_REVIEW", "INFO_REQUESTED"})
        quota("chat", 40, lambda i: request(instances["credit"][i % 2], "POST", route + "/messages",
                                            {"text": f"Проверка общего лимита {i}"}, owner["token"], {"Idempotency-Key": str(uuid4())}), {200: 30, 429: 10})
        quota("assistant", 26, lambda i: request(instances["engagement"][i % 2], "POST", "/api/v1/assistant/messages",
                                                 {"question": "Что такое оборотный кредит?"}, owner["token"]), {200: 20, 429: 6})
        action("restart", valkey)
        checked(request(instances["identity"][1], "POST", "/api/v1/auth/sign-in", {"email": unknown, "password": "Wrong2026safe"}), 429)
        report["aof_restart_preserved_limit"] = True
        checked(request(instances["identity"][0], "POST", "/api/v1/auth/password", {"currentPassword": "Test2026safe", "password": "Changed2026safe"}, account["token"]), 200)
        revoked("password-change", account["token"])
        current = sign_in(account["email"], "Changed2026safe")
        checked(request(instances["identity"][1], "POST", "/api/v1/auth/logout-all", token=current), 200)
        revoked("logout-all", current)
        current = sign_in(account["email"], "Changed2026safe")

        checked(request(instances["identity"][0], "POST", "/api/v1/auth/password-recovery", {"email": account["email"]},
                        headers={"X-Real-IP": recovery_ips[2]}), 200)
        reset_token = mail_token(account["email"], "/reset-password")
        checked(request(instances["identity"][1], "POST", "/api/v1/auth/password-reset", {"token": reset_token, "password": "Reset2026safe"}), 200)
        revoked("password-reset", current)
        current = sign_in(account["email"], "Reset2026safe")

        employee_email = "shared-employee-" + uuid4().hex + "@example.test"
        checked(request(instances["identity"][0], "POST", "/api/v1/companies/me/invitations",
                        {"email": employee_email, "canSign": False}, current, {"Idempotency-Key": str(uuid4())}), 200)
        invite_token = mail_token(employee_email, "/accept-invitation")
        employee = checked(request(instances["identity"][1], "POST", "/api/v1/auth/invitations/accept",
                                   {"token": invite_token, "fullName": "Проверка отзыва сотрудника", "password": "Employee2026safe", "personalDataConsent": True}), 200)
        checked(request(instances["identity"][0], "DELETE", f"/api/v1/companies/me/employees/{employee['user']['id']}/access",
                        token=current, headers={"Idempotency-Key": str(uuid4())}), 200)
        revoked("employee-access-revoked", employee["token"])

        checked(request(instances["identity"][0], "POST", "/api/v1/companies/me/invitations",
                        {"email": employee_email, "canSign": False}, current, {"Idempotency-Key": str(uuid4())}), 200)
        replacement = mail_token(employee_email, "/accept-invitation", previous=invite_token)
        reactivated = checked(request(instances["identity"][1], "POST", "/api/v1/auth/invitations/accept",
                                      {"token": replacement, "fullName": "Повторно приглашённый сотрудник", "password": "Employee2026new", "personalDataConsent": True}), 200)
        revoked("old-employee-token-after-reactivation", employee["token"])
        revoked("new-employee-token-after-reactivation", reactivated["token"], expected=200)

        # Faults use a separate unsaturated account and application, so a previous 429 cannot
        # hide failure-open behavior. Only the owned synthetic stack is stopped/restored.
        fresh = api.register("shared-outage")
        draft = api.create(fresh["token"])
        action("stop", valkey)
        fault_context = "valkey-down"
        for cid in instances["identity"]:
            checked(request(cid, "POST", "/api/v1/auth/sign-in", {"email": fresh["email"], "password": "Test2026safe"}), 503)
            checked(request(cid, "POST", "/api/v1/auth/password-recovery", {"email": fresh["email"]}), 503)
        for cid in instances["credit"]:
            checked(upload(cid, fresh["token"], draft["id"]), 503)
            checked(request(cid, "POST", route + "/messages", {"text": "Не должно сохраниться"}, owner["token"],
                            {"Idempotency-Key": str(uuid4())}), 503)
            checked(request(cid, "GET", "/api/v1/applications", token=fresh["token"]), 200)
        for cid in instances["engagement"]:
            checked(request(cid, "POST", "/api/v1/assistant/messages", {"question": "Что такое кредит?"}, fresh["token"]), 503)
        revoked("valkey-down-authority-still-denies", account["token"])
        checked(request(instances["identity"][0], "POST", "/api/v1/auth/logout-all", token=current), 200)
        revoked("revocation-commits-even-when-cache-publication-fails", current)
        for cid in instances["identity"]:
            action("stop", cid)
        fault_context = "valkey-and-authority-down"
        for service in ("credit", "engagement"):
            for cid in instances[service]:
                path = "/api/v1/applications" if service == "credit" else "/api/v1/notifications"
                reply = request(cid, "GET", path, token=current)
                checked(reply, 503)
        for cid in instances["identity"]:
            action("start", cid)
        action("start", valkey)
        fault_context = None
        revoked("after-valkey-restart", account["token"])
        assert valkey_command("FLUSHDB") == "OK"
        report["actions"].append({"at": datetime.now(timezone.utc).isoformat(), "action": "FLUSHDB", "container": valkey,
                                  "authorization": "owned synthetic cache-loss test; business databases untouched"})
        revoked("after-cache-loss", account["token"])
        checked(upload(instances["credit"][1], fresh["token"], draft["id"]), 201)
        assert len(api.json("GET", f"/api/v1/applications/{draft['id']}", token=fresh["token"])["documents"]) == 1
        assert api.json("GET", route + "/messages", token=owner["token"])["lastSeq"] == 30
        report["failed_uploads_and_messages_not_persisted"] = True
        sign_in(fresh["email"])
        api.json("POST", route + "/withdraw", token=owner["token"])
        report["fault_p95_seconds"] = sorted(r["seconds"] for r in report["faults"])[math.ceil(len(report["faults"]) * .95) - 1]
        report["passed"] = True
    finally:
        for cid in list(stopped):
            action("start", cid)
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        (directory / "verified.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
