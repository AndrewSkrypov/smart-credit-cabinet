#!/usr/bin/env python3
"""Evaluate the unchanged assistant against a manually authored, versioned rubric."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import time
from urllib.parse import urlsplit
from urllib.request import urlopen
from uuid import uuid4

from cabinet_api import Cabinet, DockerCabinet

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = {
    "client": ("000100000004", "OFFER_ISSUED", None),
    "romashka": ("0001001039", "REJECTED", "SF_STATUS"),
    "gromov": ("000100020145", "REJECTED", "SF_OVERDUE_90"),
    "technopark": ("0001001046", "INFO_REQUESTED", None),
    "logistic": ("0001001021", "MANUAL_REVIEW", None),
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def score(case, response, forbidden):
    text = response.get("text", "").lower().replace("ё", "е")
    # The rubric is independent of Knowledge/RuleAnswers; do not import their matchers.
    missing = [pattern for pattern in case["required_patterns"] if not re.search(pattern, text, re.S)]
    unsafe = [pattern for pattern in forbidden + case.get("forbidden_patterns", []) if re.search(pattern, text, re.S)]
    return missing, unsafe


def main():
    parser = argparse.ArgumentParser(description="36 вопросов к реальному помощнику на отдельном синтетическом стенде")
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--project", default="cabinet-e2e")
    parser.add_argument("--mode", choices=["rules", "model"], required=True)
    parser.add_argument("--model", choices=["qwen2.5:7b-instruct-q4_K_M", "qwen2.5:1.5b", "qwen3:1.7b", "qwen3:4b-instruct-2507-q4_K_M"], default="qwen2.5:1.5b",
                        help="Exact separately verified Apache-2.0 model tag; does not change the rubric")
    parser.add_argument("--report-name", required=True)
    parser.add_argument("--native-manifest", type=Path, help="Манифест изолированного синтетического стенда на loopback")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", args.report_name):
        parser.error("Нужно безопасное имя отчёта")
    native = json.loads(args.native_manifest.read_text(encoding="utf-8")) if args.native_manifest else None
    if native:
        address = urlsplit(args.base)
        model_url = urlsplit(native["ollama_url"])
        assert native["synthetic"] and re.fullmatch(r"final_[a-z0-9_]{1,24}", native["db_prefix"])
        assert address.scheme == "http" and address.hostname == "127.0.0.1" and address.port == native["gateway"]
        if args.mode == "model":
            assert model_url.scheme == "http" and model_url.hostname == "127.0.0.1" and native["model"] == args.model
        else:
            assert not native["ollama_url"]
    api = Cabinet(args.base) if native else DockerCabinet(args.base, args.project)
    directory = ROOT / "scripts/assistant-eval-reports" / args.report_name
    directory.mkdir(parents=True, exist_ok=False)
    dataset_path = ROOT / "scripts/assistant-eval-cases.json"
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    cases = dataset["cases"]
    assert len(cases) == 36 and len({case["id"] for case in cases}) == 36
    for case in cases:
        assert case["expected_facts"] and (case["required_patterns"] or case["expected_status"] == 404)
        for pattern in case["required_patterns"] + case.get("forbidden_patterns", []) + dataset["global_forbidden_patterns"]:
            re.compile(pattern)

    def docker(*arguments):
        return subprocess.check_output(["docker", *arguments], text=True, encoding="utf-8", stderr=subprocess.DEVNULL)

    report = {
        "started_at": datetime.now(timezone.utc).isoformat(), "project": args.project if not native else None,
        "requested_mode": args.mode, "completed": False, "automated_gate_passed": False,
        "manual_review_required": True,
        "dataset_sha256": digest(dataset_path), "rubric_version": dataset["rubric_version"],
        "evaluator_script_sha256": digest(Path(__file__)),
        "evaluator_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "evaluator_tree_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
        "assistant_source_hashes": {p.name: digest(p) for p in sorted((ROOT / "services/engagement/src/main/kotlin/ru/creditcabinet/assistant").glob("*.kt"))},
        "fixtures": [], "answers": [], "images": [],
        "guard_rejections": {"count": None, "fraction_of_questions": None, "fraction_of_model_requests": None},
        "limitations": ["Positive fact checks are a manual regex rubric, not a complete semantic review.",
                        "RULES responses alone cannot distinguish deliberate rules, model failure and rejected model output.",
                        "Fact omissions are reported as quality failures; CI fails on privacy/status/forbidden-claim violations, not on undocumented score thresholds."],
    }
    owned = []
    try:
        if native:
            report["native_fixture"] = {"revision": native["sha"], "database_prefix": native["db_prefix"],
                                        "gateway_port": native["gateway"], "images_verified": False}
        else:
            engagement_ids = api.containers("engagement")
            for service in ["identity", "credit", "engagement"]:
                for cid in api.containers(service):
                    info = json.loads(docker("inspect", cid))[0]
                    env = dict(item.split("=", 1) for item in info["Config"]["Env"] if "=" in item)
                    if service == "identity":
                        assert env.get("DEMO_ACCOUNTS") == "true", "Evaluation is restricted to synthetic demo installations"
                    if service == "engagement" and args.mode == "rules":
                        assert env.get("OLLAMA_URL") == "", "Rules CI requires explicitly empty OLLAMA_URL: no model probe/chat is possible"
                    if service == "engagement" and args.mode == "model":
                        assert env.get("ASSISTANT_MODEL") == args.model, "Use the separately verified Apache-2.0 evaluation model"
                        model_url = urlsplit(env.get("OLLAMA_URL", ""))
                        assert model_url.scheme == "http" and model_url.hostname in {"ollama", "host.docker.internal", "127.0.0.1"}
                        assert not (model_url.username or model_url.password or model_url.query or model_url.fragment)
                        ollama_tags_url = model_url.geturl().rstrip("/") + "/api/tags"
                    report["images"].append({"service": service, "container": cid, "image": info["Image"],
                                             "revision": (info["Config"].get("Labels") or {}).get("org.opencontainers.image.revision")})
        readers = [api.register("assistant-eval") for _ in range(3)]
        state = api.json("GET", "/api/v1/assistant/status", token=readers[0]["token"])
        assert state["mode"] == args.mode.upper(), "Requested assistant mode is not actually available"
        report["assistant_status"] = state
        if args.mode == "model":
            if native:
                with urlopen(native["ollama_url"] + "/api/tags", timeout=10) as response:
                    raw = response.read()
            else:
                raw = docker("exec", engagement_ids[0], "curl", "-fsS", "--max-time", "10", ollama_tags_url)
            tag = next(item for item in json.loads(raw)["models"] if item["name"] == args.model)
            report["model"] = {key: tag[key] for key in ("name", "digest", "size", "details") if key in tag}
        fixtures = {}
        companies = {row["inn"]: row for row in api.json("GET", "/sim/control")["companies"]}
        for account, (inn, expected_status, expected_reason) in FIXTURES.items():
            token = api.sign_in(account + "@demo.test")["token"]
            for app in api.json("GET", "/api/v1/applications", token=token):
                if app["status"] not in {"DISBURSED", "REJECTED", "WITHDRAWN", "CLOSED"}:
                    api.json("POST", f"/api/v1/applications/{app['id']}/withdraw", token=token)
            app = api.json("POST", "/api/v1/applications", {**companies[inn]["scenario"]["request"], "bureauConsent": True}, token, expected=(201,))
            owned.append((token, app["id"]))
            api.json("POST", f"/api/v1/applications/{app['id']}/submit", token=token)
            app = api.wait(token, app["id"], {expected_status})
            if expected_reason:
                assert app["process"]["outcome"]["reasonCode"] == expected_reason
            if account == "client":
                assert app["process"]["offer"]["amount"] == 1_000_000 and app["process"]["offer"]["termMonths"] == 12
            fixtures[account] = (token, app["id"])
            report["fixtures"].append({"account": account, "application": app["id"], "expected_status": expected_status,
                                       "observed_status": app["status"], "expected_reason": expected_reason})
        draft = api.create(readers[0]["token"])
        owned.append((readers[0]["token"], draft["id"]))
        assert draft["status"] == "DRAFT"
        report["fixtures"].append({"account": "synthetic-reader", "fixture": "draft", "application": draft["id"],
                                   "expected_status": "DRAFT", "observed_status": draft["status"]})
        fixtures["draft"] = (readers[0]["token"], draft["id"])
        fixtures["foreign"] = (readers[1]["token"], fixtures["romashka"][1])
        fixtures["missing"] = (readers[2]["token"], str(uuid4()))
        report["privacy_fixtures"] = {name: fixtures[name][1] for name in ["foreign", "missing"]}
        report["questions_started_at"] = datetime.now(timezone.utc).isoformat()
        if native:
            log_path = args.native_manifest.parent / "engagement.log"
            log_offset = log_path.stat().st_size if log_path.is_file() else None
        for index, case in enumerate(cases):
            token, application = fixtures.get(case.get("fixture"), (readers[index % len(readers)]["token"], None))
            payload = {"question": case["question"], "history": case.get("history", [])}
            if application:
                payload["applicationId"] = application
            start = time.monotonic()
            response = api.request("POST", "/api/v1/assistant/messages", payload, token, expected=None, timeout=125)
            body = response["body"]
            assert isinstance(body, dict)
            missing, unsafe = score(case, body, dataset["global_forbidden_patterns"])
            correct_status = response["status"] == case["expected_status"]
            valid_answer = case["expected_status"] != 200 or (isinstance(body.get("text"), str) and body.get("mode") in {"RULES", "MODEL"} and isinstance(body.get("sources"), list))
            if args.mode == "rules" and response["status"] == 200:
                assert body.get("mode") == "RULES", "Unexpected model answer in rules-only evaluation"
            row = {**case, "status": response["status"], "seconds": round(time.monotonic() - start, 3),
                   "answer": body.get("text"), "mode": body.get("mode"), "sources": body.get("sources"),
                   "missing_fact_patterns": missing, "forbidden_claim_patterns": unsafe,
                   "http_and_regex_gate_passed": correct_status and valid_answer and not unsafe,
                   "correct": correct_status and valid_answer and not missing and not unsafe}
            report["answers"].append(row)
            (directory / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({key: row[key] for key in ("id", "status", "mode", "correct", "seconds")}), flush=True)
        durations = sorted(row["seconds"] for row in report["answers"])
        modes = Counter(row["mode"] or "HTTP_ERROR" for row in report["answers"])
        report["summary"] = {"questions": len(cases), "correct": sum(row["correct"] for row in report["answers"]),
                             "correct_fraction": sum(row["correct"] for row in report["answers"]) / len(cases),
                             "answer_modes": dict(modes), "p50_seconds": durations[math.ceil(len(durations) * .5) - 1],
                             "p95_seconds": durations[math.ceil(len(durations) * .95) - 1]}
        report["summary"]["latency_by_mode"] = {}
        for mode in modes:
            timings = sorted(row["seconds"] for row in report["answers"] if (row["mode"] or "HTTP_ERROR") == mode)
            report["summary"]["latency_by_mode"][mode] = {
                "count": len(timings), "p50_seconds": timings[math.ceil(len(timings) * .5) - 1],
                "p95_seconds": timings[math.ceil(len(timings) * .95) - 1],
            }
        try:
            if native:
                if log_offset is None:
                    raise FileNotFoundError(log_path)
                with log_path.open("rb") as stream:
                    stream.seek(log_offset)
                    logs = stream.read().decode("utf-8")
            else:
                logs = "\n".join(subprocess.check_output(
                    ["docker", "logs", "--since", report["questions_started_at"], cid],
                    text=True, encoding="utf-8", stderr=subprocess.STDOUT,
                ) for cid in engagement_ids)
            dropped = sum("Assistant answer dropped:" in line for line in logs.splitlines())
            source = "native engagement structured log" if native else "engagement logs in isolated evaluation interval"
            report["guard_rejections"] = {"count": dropped, "fraction_of_questions": dropped / len(cases),
                                          "fraction_of_model_requests": None, "source": source,
                                          "model_request_denominator": "API does not expose model attempts"}
        except (OSError, UnicodeError, subprocess.CalledProcessError):
            report["guard_rejections"]["unavailable_reason"] = "Container logs unavailable; no value inferred from RULES answers"
        if args.mode == "model":
            assert modes["MODEL"] > 0 or (report["guard_rejections"]["count"] or 0) > 0, "No actual model output observed; status alone is insufficient"
        report["automated_gate_passed"] = all(row["http_and_regex_gate_passed"] for row in report["answers"])
        report["completed"] = True
    finally:
        cleanup = []
        for token, identifier in owned:
            try:
                app = api.json("GET", f"/api/v1/applications/{identifier}", token=token)
                if "WITHDRAW" in app.get("allowedActions", []):
                    api.json("POST", f"/api/v1/applications/{identifier}/withdraw", token=token)
                cleanup.append({"application": identifier, "completed": True})
            except Exception as error:
                cleanup.append({"application": identifier, "completed": False, "error_type": type(error).__name__})
        report["cleanup"] = cleanup
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        (directory / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report.get("summary", {})), flush=True)
    assert report["completed"] and report["automated_gate_passed"], "Evaluation incomplete or privacy/status/forbidden-claim gate failed; inspect report"
    assert all(row["completed"] for row in report["cleanup"]), "Synthetic fixture cleanup failed"


if __name__ == "__main__":
    main()
