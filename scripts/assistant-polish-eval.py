#!/usr/bin/env python3
"""Additional real assistant cases; does not replace or edit the original 36-case evaluation."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
from uuid import uuid4

from cabinet_api import DockerCabinet

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description="Дополнительные случаи помощника и настоящий отказ модели")
parser.add_argument("--base", default="http://localhost:8080")
parser.add_argument("--project", default="cabinet-e2e")
parser.add_argument("--mode", choices=["rules", "model"], required=True)
parser.add_argument("--report-name", required=True)
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.report_name):
    parser.error("Нужно безопасное имя отчёта")
api = DockerCabinet(args.base, args.project)
assert api.json("GET", "/api/v1/auth/options")["demoAccounts"]
case_file = ROOT / "scripts/assistant-polish-cases.json"
dataset = json.loads(case_file.read_text(encoding="utf-8"))
assert len(dataset["cases"]) == 9 and len({case["id"] for case in dataset["cases"]}) == 9
for case in dataset["cases"]:
    assert case["expected_facts"] and (case["required_patterns"] or case.get("expected_status") == 404)
    for pattern in case["required_patterns"] + case["forbidden_patterns"]:
        re.compile(pattern)
directory = ROOT / "scripts/assistant-eval-reports" / args.report_name
directory.mkdir(parents=True, exist_ok=False)
report = {"started_at": datetime.now(timezone.utc).isoformat(), "project": args.project, "mode": args.mode,
          "cases_sha256": hashlib.sha256(case_file.read_bytes()).hexdigest(), "completed": False,
          "passed": False, "manual_review_required": True, "answers": [], "cleanup": []}
owned = []
stopped = None


def docker(*arguments):
    return subprocess.check_output(["docker", *arguments], text=True, encoding="utf-8").strip()


try:
    reader = api.register("assistant-polish")
    time.sleep(2.1)
    other = api.register("assistant-polish-owner")
    for user in [reader, other]:
        app = api.create(user["token"])
        owned.append((user["token"], app["id"]))
    fixtures = {"draft": owned[0][1], "foreign": owned[1][1], "missing": str(uuid4())}
    state = api.json("GET", "/api/v1/assistant/status", token=reader["token"])
    assert state["mode"] == args.mode.upper()
    if args.mode == "model":
        containers = docker("ps", "-q", "--filter", f"label=com.docker.compose.project={args.project}", "--filter", "label=com.docker.compose.service=ollama").split()
        assert len(containers) == 1, "Нужен собственный единственный Ollama"
        report["model_container"] = containers[0]
    for case in dataset["cases"]:
        if case.get("fault") == "model_down" and args.mode == "model":
            stopped = containers[0]
            docker("stop", stopped)
        payload = {"question": case["question"], "history": []}
        if case.get("fixture"):
            payload["applicationId"] = fixtures[case["fixture"]]
        response = api.request("POST", "/api/v1/assistant/messages", payload, reader["token"], expected=None, timeout=40)
        body = response["body"]
        answer = body.get("text", "")
        normalized = answer.lower().replace("ё", "е")
        missing = [pattern for pattern in case["required_patterns"] if not re.search(pattern, normalized, re.S)]
        unsafe = [pattern for pattern in case["forbidden_patterns"] if re.search(pattern, normalized, re.S)]
        passed = response["status"] == case.get("expected_status", 200) and not missing and not unsafe
        if case.get("expected_mode"):
            passed = passed and body.get("mode") == case["expected_mode"]
        row = {**case, "status": response["status"], "mode": body.get("mode"), "answer": answer, "sources": body.get("sources", []),
               "seconds": round(response["duration_ms"] / 1000, 3), "missing": missing, "forbidden": unsafe, "passed": passed}
        report["answers"].append(row)
        print(json.dumps({key: row[key] for key in ["id", "status", "mode", "seconds", "passed"]}), flush=True)
    if args.mode == "model":
        # X09 is now an authoritative consent procedure. Keep that case unchanged,
        # but also exercise an actual generative route while our model is stopped.
        started = datetime.now(timezone.utc).isoformat()
        response = api.request("POST", "/api/v1/assistant/messages", {
            "question": "На что выдаётся кредит на пополнение оборота?", "history": [],
        }, reader["token"], expected=None, timeout=40)
        body = response["body"]
        fallback_logged = any("Assistant model did not answer, falling back to the rules:" in
                              subprocess.check_output(["docker", "logs", "--since", started, cid],
                                                      text=True, encoding="utf-8", stderr=subprocess.STDOUT)
                              for cid in api.containers("engagement"))
        report["fault_probe"] = {"status": response["status"], "mode": body.get("mode"), "answer": body.get("text"),
                                 "seconds": round(response["duration_ms"] / 1000, 3), "adapter_fallback_logged": fallback_logged}
        assert response["status"] == 200 and body.get("mode") == "RULES" and "одной суммой" in body.get("text", "")
        assert fallback_logged, "A rules-routed question alone does not prove a model adapter failure"
    report["completed"] = True
    report["passed"] = all(row["passed"] for row in report["answers"])
finally:
    if stopped:
        docker("start", stopped)
    for token, identifier in owned:
        try:
            api.json("POST", f"/api/v1/applications/{identifier}/withdraw", token=token)
            report["cleanup"].append({"id": identifier, "completed": True})
        except Exception as error:
            report["passed"] = False
            report["cleanup"].append({"id": identifier, "completed": False, "error_type": type(error).__name__})
    report["finished_at"] = datetime.now(timezone.utc).isoformat()
    (directory / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
assert report["completed"] and report["passed"], "Дополнительная проверка не принята: прочитайте report.json"
