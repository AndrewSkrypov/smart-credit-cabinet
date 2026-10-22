#!/usr/bin/env python3
"""Require the gateway's rate response to follow the Russian JSON error contract."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
from cabinet_api import DockerCabinet  # noqa: E402


def probe(api):
    codes = []
    for _ in range(50):
        response = api.request("GET", "/api/v1/auth/options", expected=None)
        codes.append(response["status"])
        if response["status"] == 429:
            break
        assert response["status"] == 200, response["status"]
    assert codes[-1] == 429, "Не получен 429 шлюза"
    body = response["body"]
    assert isinstance(body, dict), "Шлюз вернул HTML вместо JSON ошибки"
    headers = {key.lower(): value for key, value in response["headers"].items()}
    assert headers.get("content-type", "").startswith("application/json"), "Неверный тип ошибки шлюза"
    assert body["status"] == 429 and body["message"] == "Слишком много запросов. Попробуйте через минуту."
    assert body["path"] == "/api/v1/auth/" and body["requestId"] == headers["x-request-id"]
    assert int(headers["retry-after"]) > 0
    datetime.fromisoformat(body["timestamp"].replace("Z", "+00:00"))
    return {"accepted_in_burst": codes.count(200), "limit_response": 429, "body_format": "json",
            "message_language": "ru", "request_id_matches_header": True, "retry_after_seconds": int(headers["retry-after"])}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Регрессия JSON-ошибки ограничения частоты шлюза")
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--project", default="cabinet-submission")
    args = parser.parse_args()
    report = {"checked_at": datetime.now(timezone.utc).isoformat(), "passed": False}
    try:
        report.update(probe(DockerCabinet(args.base, args.project)), passed=True)
    except Exception as error:
        report["failure"] = str(error)
        raise
    finally:
        directory = ROOT / "security/reports"
        directory.mkdir(exist_ok=True)
        (directory / "gateway-rate.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False))
        time.sleep(6)
