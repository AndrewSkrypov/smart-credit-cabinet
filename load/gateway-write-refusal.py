#!/usr/bin/env python3
"""Check the actual gateway policy with a refused primary and a healthy backup."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import secrets
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import Cabinet  # noqa: E402


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project", required=True)
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

    def containers(service):
        ids = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}",
                     "--filter", f"label=com.docker.compose.service={service}").split()
        assert ids, service
        return json.loads(docker("inspect", *ids))

    gateways, credits = containers("gateway"), containers("credit")
    assert len(gateways) == 1
    gateway = gateways[0]["Id"]
    healthy = next(item for item in credits if item["State"].get("Health", {}).get("Status") == "healthy")
    networks = set(gateways[0]["NetworkSettings"]["Networks"]) & set(healthy["NetworkSettings"]["Networks"])
    assert len(networks) == 1
    network = next(iter(networks))
    address = healthy["NetworkSettings"]["Networks"][network]["IPAddress"]
    assert re.fullmatch(r"[0-9.]+", address)
    assert json.loads(docker("exec", gateway, "wget", "-qO-", f"http://{address}:8080/actuator/health"))["status"] == "UP"
    config_path = "/etc/nginx/conf.d/default.conf"
    probe_path = "/etc/nginx/conf.d/zz-cabinet-refusal-probe.conf"
    log_path = "/tmp/cabinet-refusal-probe.log"
    # The stack mounts its gateway configuration read-only. The probe runs a separate Nginx
    # from the same image and the same configuration; only the credit upstream addresses differ.
    original = docker("exec", gateway, "cat", config_path)
    fixture, count = re.subn(r"server credit:8080 resolve max_fails=1 fail_timeout=5s;",
                            f"server 127.0.0.1:1 max_fails=0;\n    server {address}:8080 backup;", original)
    assert count == 1
    result = {"started_at": datetime.now(timezone.utc).isoformat(), "passed": False,
              "healthy_backup_verified": True, "requests": [], "probe_removed": False, "gateway_unchanged": False}
    probe_name = f"{args.project}-refusal-probe-{secrets.token_hex(4)}"
    created = False
    with tempfile.TemporaryDirectory() as temporary:
        replacement, probe = [Path(temporary) / name for name in ("fixture.conf", "probe.conf")]
        replacement.write_text(fixture, encoding="utf-8", newline="\n")
        probe.write_text('''log_format cabinet_refusal escape=json '{"id":"$http_x_request_id","method":"$request_method","status":$status,"upstream":"$upstream_addr","upstream_status":"$upstream_status"}';
access_log /tmp/cabinet-refusal-probe.log cabinet_refusal;
''', encoding="utf-8")
        try:
            docker("create", "--name", probe_name, "--network", network, "--publish", "127.0.0.1::80",
                   "--label", "cabinet.probe=gateway-write-refusal", gateways[0]["Image"])
            created = True
            docker("cp", str(replacement), probe_name + ":" + config_path)
            docker("cp", str(probe), probe_name + ":" + probe_path)
            docker("start", probe_name)
            docker("exec", probe_name, "nginx", "-t")
            port = docker("port", probe_name, "80/tcp").split()[0].rsplit(":", 1)[1]
            api = Cabinet(f"http://127.0.0.1:{port}")
            for _ in range(50):
                try:
                    if api.request("GET", "/", expected=None)["status"] < 500:
                        break
                except OSError:
                    pass
                time.sleep(0.2)
            for method in ("POST", "PUT", "PATCH", "DELETE"):
                response = api.request(method, "/api/v1/applications", body={}, expected=None,
                                       headers={"X-Request-Id": f"refusal-{args.label}-{method}"})
                result["requests"].append({"method": method, "status": response["status"]})
            traces = [json.loads(line) for line in docker("exec", probe_name, "cat", log_path).splitlines()
                      if f"refusal-{args.label}-" in line]
            result["attempts"] = traces
            assert len(traces) == 4 and {x["method"] for x in traces} == {"POST", "PUT", "PATCH", "DELETE"}
            assert all(x["status"] == 502 and x["upstream"] == "127.0.0.1:1" for x in traces), traces
            assert all(x["status"] == 502 for x in result["requests"]), result["requests"]
            result["passed"] = True
        finally:
            try:
                if created:
                    docker("rm", "-f", probe_name)
                result["probe_removed"] = not docker("ps", "-aq", "--filter", f"name=^{probe_name}$").strip()
                result["gateway_unchanged"] = docker("exec", gateway, "cat", config_path) == original
            finally:
                result["passed"] = result["passed"] and result["probe_removed"] and result["gateway_unchanged"]
                result["finished_at"] = datetime.now(timezone.utc).isoformat()
                (directory / "gateway-refusal.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    if not result["passed"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
