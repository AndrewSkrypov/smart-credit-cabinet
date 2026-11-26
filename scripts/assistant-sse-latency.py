#!/usr/bin/env python3
"""Measure complete, grounded answers and reference preview events on a synthetic stand."""

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
import json
import math
import re
from pathlib import Path
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from cabinet_api import Cabinet, DockerCabinet


QUESTION = "Что такое овердрафт и для чего он нужен?"


def sample(base, token):
    # An uncached question keeps the reference preview and exercises fresh generation.
    request = Request(
        base + "/api/v1/assistant/messages/stream",
        data=json.dumps({"question": QUESTION}).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json",
                 "Accept": "text/event-stream"},
    )
    row = {}
    started = time.perf_counter()
    with urlopen(request, timeout=45) as response:
        event = None
        data = []
        for raw in response:
            line = raw.decode().rstrip("\r\n")
            if line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                data.append(line[6:])
            elif not line and event and data:
                elapsed = round((time.perf_counter() - started) * 1000, 3)
                payload = json.loads("\n".join(data))
                row[event + "_ms"] = elapsed
                if event == "answer":
                    row["mode"] = payload["mode"]
                if event == "failure":
                    raise RuntimeError(f"SSE failure HTTP {payload['status']}")
                event, data = None, []
    if "mode" not in row:
        raise RuntimeError("Missing complete answer event")
    return row


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--project")
    target.add_argument("--native-manifest", type=Path)
    parser.add_argument("--label", required=True)
    parser.add_argument("--runs", type=int, default=8)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--cold-model", help="Unload this model before every sample on a dedicated Ollama")
    parser.add_argument("--ollama-base", default="http://127.0.0.1:11434")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.concurrency <= args.runs <= 16:
        parser.error("Require 1–16 runs and concurrency no greater than runs")
    if args.native_manifest:
        manifest = json.loads(args.native_manifest.read_text())
        address = urlsplit(args.base)
        if not (manifest.get("synthetic") is True and re.fullmatch(r"final_[a-z0-9_]{1,24}", manifest["db_prefix"])
                and address.scheme == "http" and address.hostname == "127.0.0.1" and address.port == manifest["gateway"]):
            parser.error("Native measurements require an isolated synthetic loopback stand")
        if args.cold_model and (args.ollama_base != manifest["ollama_url"] or args.cold_model != manifest["model"]):
            parser.error("Cold measurements must use the Ollama in the fixture manifest")
        api = Cabinet(args.base)
    else:
        api = DockerCabinet(args.base, args.project)
    token = api.register("assistant-sse")["token"]
    if args.cold_model:
        address = urlsplit(args.ollama_base)
        if args.concurrency != 1 or address.scheme != "http" or address.hostname != "127.0.0.1":
            parser.error("Cold runs require serial traffic and a dedicated loopback Ollama")
        status = api.json("GET", "/api/v1/assistant/status", token=token)
        if status != {"mode": "MODEL", "model": args.cold_model}:
            parser.error("Engagement must report the model being unloaded")

    def measure(_):
        if args.cold_model:
            request = Request(
                args.ollama_base.rstrip("/") + "/api/chat",
                data=json.dumps({"model": args.cold_model, "messages": [], "keep_alive": 0}).encode(),
                headers={"Content-Type": "application/json"},
            )
            with urlopen(request, timeout=15) as response:
                response.read()
        return sample(api.base, token)

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        rows = list(pool.map(measure, range(args.runs)))
    report = {
        "label": args.label, "concurrency": args.concurrency, "cold_model": args.cold_model,
        "modes": dict(Counter(row["mode"] for row in rows)), "samples": rows,
    }
    for event in ("reference", "answer"):
        timings = [row[event + "_ms"] for row in rows if event + "_ms" in row]
        report[event] = {"n": len(timings), "p95_ms": sorted(timings)[math.ceil(len(timings) * .95) - 1] if timings else None}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "samples"}))


if __name__ == "__main__":
    main()
