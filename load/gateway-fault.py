#!/usr/bin/env python3
"""Measure the same real two-replica gateway workload before/after ingress changes."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import Cabinet  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description="Отказы двух credit на отдельном синтетическом стенде")
    parser.add_argument("--project", required=True)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--label", required=True)
    parser.add_argument("--expect-hardened", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"cabinet-(?:submission|reliability|e2e|release)(?:-[a-z0-9]+)?", args.project):
        parser.error("Разрешён только отдельный синтетический проект")
    if not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", args.label):
        parser.error("Некорректная метка")
    destination = ROOT / "load/reports" / args.label
    destination.mkdir(parents=True, exist_ok=False)

    def docker(*arguments):
        return subprocess.run(["docker", *arguments], capture_output=True, text=True,
                              encoding="utf-8", check=True, timeout=60).stdout

    def containers(service):
        return docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                      "--filter", f"label=com.docker.compose.service={service}").split()

    replicas, gateways = containers("credit"), containers("gateway")
    assert len(replicas) == 2 and len(gateways) == 1, "Нужны два credit и один gateway"
    gateway, victim = gateways[0], replicas[0]
    config = docker("exec", gateway, "cat", "/etc/nginx/conf.d/default.conf")
    api = Cabinet(args.base)
    token = api.register("gateway-probe")["token"]
    started = time.monotonic()
    result = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project,
              "label": args.label, "gateway_config_sha256": hashlib.sha256(config.encode()).hexdigest(),
              "replicas": [{"id": item["Id"], "image": item["Image"], "networks": {
                  key: value["IPAddress"] for key, value in item["NetworkSettings"]["Networks"].items()}}
                  for item in json.loads(docker("inspect", *replicas))],
              "client_timeout_seconds": 15, "concurrency": 8, "phases": {}, "passed": False}
    paused = stopped = installed = False
    log_path = "/tmp/cabinet-gateway-probe.log"
    conf_path = "/etc/nginx/conf.d/zz-cabinet-gateway-probe.conf"
    probe_config = '''log_format cabinet_probe escape=json '{"id":"$http_x_request_id","method":"$request_method","status":$status,"upstream":"$upstream_addr","upstream_status":"$upstream_status","seconds":"$request_time"}';
access_log /tmp/cabinet-gateway-probe.log cabinet_probe;
'''

    def phase(name, count=32):
        phase_started = time.monotonic()

        def probe(index):
            method = "HEAD" if index % 2 else "GET"
            request_id = f"gateway-{args.label}-{name}-{index}"
            start = time.monotonic()
            try:
                response = api.request(method, "/api/v1/products", timeout=15, expected=None,
                                       headers={"X-Request-Id": request_id}, token=token)
                return {"id": request_id, "method": method, "status": response["status"],
                        "seconds": time.monotonic() - start, "completed_after": time.monotonic() - phase_started}
            except (TimeoutError, OSError) as error:
                return {"id": request_id, "method": method, "error": type(error).__name__,
                        "seconds": time.monotonic() - start, "completed_after": time.monotonic() - phase_started}

        with ThreadPoolExecutor(max_workers=8) as pool:
            rows = list(pool.map(probe, range(count)))
        durations = sorted(row["seconds"] for row in rows)
        successes = [row["completed_after"] for row in rows if row.get("status") == 200]
        summary = {"n": count, "statuses": dict(Counter(str(row.get("status", "network_error")) for row in rows)),
                   "errors": dict(Counter(row["error"] for row in rows if "error" in row)),
                   "percentile_method": "nearest_rank",
                   "p50_seconds": durations[math.ceil(count * .5) - 1],
                   "p95_seconds": durations[math.ceil(count * .95) - 1],
                   "p99_seconds": durations[math.ceil(count * .99) - 1],
                   "first_success_seconds": min(successes) if successes else None,
                   "all_requests_completed_seconds": time.monotonic() - phase_started, "requests": rows}
        result["phases"][name] = summary
        print(json.dumps({"phase": name, **{k: v for k, v in summary.items() if k != "requests"}}), flush=True)
        return summary

    try:
        assert not docker("exec", gateway, "sh", "-c", f"test -e {conf_path} && echo exists || true").strip()
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / "probe.conf"
            local.write_text(probe_config, encoding="utf-8")
            docker("cp", str(local), gateway + ":" + conf_path)
        installed = True
        docker("exec", gateway, "nginx", "-t")
        docker("exec", gateway, "nginx", "-s", "reload")
        time.sleep(2)
        assert phase("healthy")["statuses"] == {"200": 32}
        paused = True
        docker("pause", victim)
        phase("paused")
        docker("unpause", victim)
        paused = False
        time.sleep(7)
        assert phase("resumed", 16)["statuses"] == {"200": 16}
        # Warm both DNS/upstream peers immediately before SIGKILL. No DNS-cache-expiry wait.
        stopped = True
        docker("stop", "--time", "0", victim)
        state = json.loads(docker("inspect", victim))[0]["State"]
        assert not state["Running"] and state["ExitCode"] == 137
        result["stopped_state"] = {key: state[key] for key in ("Running", "ExitCode", "OOMKilled")}
        phase("killed")
        # Unauthenticated mutations cannot alter business data, but still reach the credit upstream.
        mutations = []
        for method in ("POST", "PUT", "PATCH", "DELETE"):
            request_id = f"gateway-{args.label}-write-{method}"
            response = api.request(method, "/api/v1/applications", expected=None, timeout=35,
                                   headers={"X-Request-Id": request_id}, body={})
            mutations.append({"id": request_id, "method": method, "status": response["status"]})
        result["unauthenticated_mutations"] = mutations
        time.sleep(1)
        lines = docker("exec", gateway, "cat", log_path).splitlines()
        traces = [json.loads(line) for line in lines if f"gateway-{args.label}-" in line]
        result["gateway_attempts"] = traces
        assert len(traces) == 116, f"Expected116 measured traces, got{len(traces)}"
        healthy_ips = {line["upstream"].split(":")[0] for line in traces if "-healthy-" in line["id"]}
        replica_ips = {ip for replica in result["replicas"] for ip in replica["networks"].values()}
        assert len(healthy_ips) == 2 and healthy_ips <= replica_ips, healthy_ips
        if args.expect_hardened:
            assert all(p["statuses"] == {"200": p["n"]} for p in result["phases"].values())
            for line in traces:
                attempts = line["upstream"].split(", ")
                assert len(attempts) <= 2
                if line["method"] not in {"GET", "HEAD"}:
                    assert len(attempts) == 1, line
            assert any(", " in line["upstream"] and "-paused-" in line["id"] for line in traces), "No actual retry observed"
        result["passed"] = True
    finally:
        if paused:
            docker("unpause", victim)
        if stopped:
            docker("start", victim)
        if paused or stopped:
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                state = json.loads(docker("inspect", victim))[0]["State"]
                if state.get("Health", {}).get("Status") == "healthy":
                    break
                time.sleep(2)
            result["victim_restored_healthy"] = state.get("Health", {}).get("Status") == "healthy"
        if installed:
            docker("exec", gateway, "rm", "-f", conf_path, log_path)
            docker("exec", gateway, "nginx", "-s", "reload")
        result["elapsed_seconds"] = time.monotonic() - started
        result["finished_at"] = datetime.now(timezone.utc).isoformat()
        (destination / "gateway-fault.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    assert result.get("victim_restored_healthy")


if __name__ == "__main__":
    main()
