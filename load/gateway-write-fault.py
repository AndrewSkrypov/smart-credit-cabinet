#!/usr/bin/env python3
"""Observe ambiguous real writes, download bytes and assistant routing after a credit stall."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import Cabinet, local_request  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--label", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"cabinet-(?:submission|reliability|e2e|release)(?:-[a-z0-9]+)?", args.project):
        parser.error("Нужен отдельный синтетический проект")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.label):
        parser.error("Некорректная метка")
    directory = ROOT / "load/reports" / args.label
    directory.mkdir(parents=True, exist_ok=False)

    def docker(*arguments):
        return subprocess.run(["docker", *arguments], capture_output=True, text=True,
                              encoding="utf-8", check=True, timeout=60).stdout

    replicas = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                      "--filter", "label=com.docker.compose.service=credit").split()
    assert len(replicas) == 2
    victim = replicas[0]
    gateways = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                      "--filter", "label=com.docker.compose.service=gateway").split()
    assert len(gateways) == 1
    gateway = gateways[0]
    api = Cabinet(args.base)
    clients = []
    for _ in range(8):
        clients.append(api.register("gateway-writes"))
        time.sleep(2.1)
    token = clients[0]["token"]
    result = {"started_at": datetime.now(timezone.utc).isoformat(), "replicas": replicas, "passed": False}
    paused = installed = False
    conf_path = "/etc/nginx/conf.d/zz-cabinet-write-probe.conf"
    log_path = "/tmp/cabinet-write-probe.log"
    try:
        assert not docker("exec", gateway, "sh", "-c", f"test -e {conf_path} && echo exists || true").strip()
        with tempfile.TemporaryDirectory() as temporary:
            config = Path(temporary) / "probe.conf"
            config.write_text('''log_format cabinet_write_probe escape=json '{"id":"$http_x_request_id","method":"$request_method","status":$status,"upstream":"$upstream_addr","upstream_status":"$upstream_status"}';
access_log /tmp/cabinet-write-probe.log cabinet_write_probe;
''', encoding="utf-8")
            docker("cp", str(config), gateway + ":" + conf_path)
        installed = True
        docker("exec", gateway, "nginx", "-t")
        docker("exec", gateway, "nginx", "-s", "reload")
        assert api.json("GET", "/api/v1/applications", token=token) == []
        time.sleep(7)  # expire any peer failure marking left by the preceding drill
        docker("pause", victim)
        paused = True

        def create(index):
            amount = 1_000_000 + index * 1000
            response = api.request("POST", "/api/v1/applications", {
                "productCode": "WORKING_CAPITAL", "purpose": "WORKING_CAPITAL", "amount": amount,
                "termMonths": 12, "declaredRevenue12m": 24_000_000,
                "declaredMonthlyDebtPayments": 0, "bureauConsent": True,
            }, token=clients[index]["token"], expected=(201, 502, 504), timeout=40,
                headers={"X-Request-Id": f"write-probe-{args.label}-POST-{index}"})
            return {"amount": amount, "status": response["status"], "seconds": response["duration_ms"] / 1000}

        def other_method(method):
            response = api.request(method, "/api/v1/applications", body={}, expected=(401, 502, 504), timeout=40,
                                   headers={"X-Request-Id": f"write-probe-{args.label}-{method}"})
            return {"method": method, "status": response["status"]}

        with ThreadPoolExecutor(max_workers=11) as pool:
            pending = [pool.submit(create, index) for index in range(8)]
            methods = [pool.submit(other_method, method) for method in ("PUT", "PATCH", "DELETE")]
            writes = [future.result() for future in pending]
            result["other_methods"] = [future.result() for future in methods]
        result["writes"] = writes
        assert any(row["status"] == 504 for row in writes), "No actual stalled write observed"
        docker("unpause", victim)
        paused = False
        time.sleep(7)
        traces = [json.loads(line) for line in docker("exec", gateway, "cat", log_path).splitlines()
                  if f"write-probe-{args.label}-" in line]
        result["gateway_attempts"] = traces
        assert len(traces) == 11
        assert all(len(row["upstream"].split(", ")) == 1 for row in traces), traces
        applications = []
        for index, client in enumerate(clients):
            own = api.json("GET", "/api/v1/applications?status=DRAFT", token=client["token"])
            assert len(own) <= 1
            for app in own:
                assert app["amount"] == 1_000_000 + index * 1000
                events = api.json("GET", f"/api/v1/applications/{app['id']}/events", token=client["token"])
                assert len(events) == 1 and events[0]["event"] == "CREATED"
                applications.append(app)
            if own:
                token = client["token"]
                download_application = own[0]
        counts = Counter(app["amount"] for app in applications)
        assert all(count == 1 for count in counts.values()) and len(applications) <= 8, counts
        assert set(counts) <= {row["amount"] for row in writes}
        for row in writes:
            if row["status"] == 201:
                assert counts[row["amount"]] == 1
        result["persisted"] = {"drafts": len(applications), "duplicate_amounts": 0,
                               "created_events": len(applications), "query_string_preserved": True,
                               "timed_out_but_committed": sum(row["status"] == 504 and counts[row["amount"]] == 1 for row in writes)}
        # A timeout is ambiguous: do not repeat a creation automatically, even when its first
        # response is missing. Inspect application history before deciding on any manual retry.
        assert applications
        content = b"%PDF-1.4\n% synthetic gateway transfer\n" + b" " * (12 * 1024 * 1024) + b"\n%%EOF\n"
        started = time.monotonic()
        document = api.upload(token, download_application["id"], "OTHER", content)["body"]
        with urlopen(local_request(args.base + f"/api/v1/applications/{download_application['id']}/documents/{document['id']}",
                                   headers={"Authorization": "Bearer " + token}), timeout=30) as response:
            downloaded = response.read()
            assert response.status == 200 and response.headers["X-Content-Type-Options"] == "nosniff"
        assert downloaded == content
        result["large_file"] = {"bytes": len(content), "sha256": hashlib.sha256(downloaded).hexdigest(),
                                "upload_download_seconds": time.monotonic() - started, "passed": True}
        assistant = api.request("POST", "/api/v1/assistant/messages", {"question": "Что такое оборотный кредит?"}, token=token)
        result["assistant"] = {"status": assistant["status"], "seconds": assistant["duration_ms"] / 1000}
        result["passed"] = True
    finally:
        if paused:
            docker("unpause", victim)
        if installed:
            docker("exec", gateway, "rm", "-f", conf_path, log_path)
            docker("exec", gateway, "nginx", "-s", "reload")
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        (directory / "gateway-writes.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
