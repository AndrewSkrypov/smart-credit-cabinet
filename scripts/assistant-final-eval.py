#!/usr/bin/env python3
"""Run the fixed agent-channel assistant rubric against an isolated, synthetic cabinet."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import time
from urllib.parse import urlsplit

from cabinet_api import Cabinet


ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description="Проверить факты, подсказки и права агентского помощника")
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    if urlsplit(args.base).hostname not in {"127.0.0.1", "localhost", "::1"} or not 1 <= args.repeats <= 6:
        parser.error("Нужен локальный синтетический стенд и от одного до шести повторов")
    api = Cabinet(args.base)
    results = []
    rubric_path = ROOT / "scripts/assistant-eval-cases.json"
    cases = json.loads(rubric_path.read_text(encoding="utf-8"))["final_cases"]
    assert len(set(case["id"] for case in cases)) == len(cases)
    tokens = {}
    for account in ("agent", "agent2", "client"):
        time.sleep(2.1)
        tokens[account] = api.sign_in(f"{account}@demo.test")["token"]

    companies = api.json("GET", "/api/v1/agent/clients", token=tokens["agent"])
    company_id = next(row["companyId"] for row in companies if row["inn"] == "000100000004")
    applications = {}
    try:
        applications["agent_draft"] = api.json("POST", "/api/v1/agent/applications", {
            "companyId": company_id, "productCode": "INVESTMENT", "purpose": "EQUIPMENT", "amount": 1_000_000, "termMonths": 12,
        }, tokens["agent"], expected=(201,))["id"]
        applications["client_draft"] = api.create(tokens["client"], amount=3_100_000)["id"]
        for _ in range(args.repeats):
            for case in cases:
                application = applications[case["fixture"]]
                token = tokens[case["actor"]]
                if case["route"] == "next_steps":
                    response = api.request("GET", f"/api/v1/assistant/next-steps?applicationId={application}", token=token, expected=None)
                elif case["route"] == "reminder":
                    response = api.request("POST", "/api/v1/assistant/reminders", {"applicationId": application}, token, expected=None)
                else:
                    response = api.request("POST", "/api/v1/assistant/messages", {
                        "question": "Что сейчас с моей заявкой?", "applicationId": application,
                    }, token, expected=None)
                body = response["body"]
                passed = response["status"] == case["status"]
                if "hint" in case:
                    passed = passed and any(hint["type"] == case["hint"] for hint in body)
                if "mode" in case:
                    passed = passed and body["mode"] == case["mode"] and all(word in body["text"].lower() for word in case["required"])
                results.append({"id": case["id"], "status": response["status"], "expected_status": case["status"],
                                "ms": round(response["duration_ms"], 3), "fast_path": case.get("mode") == "RULES", "passed": passed})
    finally:
        if identifier := applications.get("agent_draft"):
            api.json("POST", f"/api/v1/agent/applications/{identifier}/cancel", token=tokens["agent"])
        if identifier := applications.get("client_draft"):
            api.json("POST", f"/api/v1/applications/{identifier}/withdraw", token=tokens["client"])

    fast = sorted(row["ms"] for row in results if row["fast_path"])
    passed = len(results) == args.repeats * len(cases) and all(row["passed"] for row in results)
    report = {"checked_at": datetime.now(timezone.utc).isoformat(), "rubric_sha256": hashlib.sha256(rubric_path.read_bytes()).hexdigest(),
              "checks": results, "passed": passed, "passed_fraction": sum(row["passed"] for row in results) / len(results),
              "fast_path": {"n": len(fast), "p50_ms": fast[math.ceil(len(fast) * .5) - 1],
                            "p95_ms": fast[math.ceil(len(fast) * .95) - 1], "under_200_ms": sum(row < 200 for row in fast)}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"passed": passed, "checks": len(results), "fast_path": report["fast_path"]}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
