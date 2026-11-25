#!/usr/bin/env python3
"""Measure cold and preloaded Ollama chat on one machine and a fixed reference prompt."""

import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


PROMPT = [
    {"role": "system", "content": "Перескажи справку по-русски в трёх предложениях. Используй только факты справки."},
    {"role": "user", "content": "Справка: Овердрафт — лимит на расчётном счёте для кассовых разрывов. Проценты начисляются на использованный долг. После погашения лимит возобновляется. Вопрос: Что такое овердрафт?"},
]


def percentile(values, fraction):
    return sorted(values)[math.ceil(fraction * len(values)) - 1]


def main():
    parser = argparse.ArgumentParser(description="Сравнить холодный старт и прогретую модель Ollama")
    parser.add_argument("--base", default="http://127.0.0.1:11434")
    parser.add_argument("--model", action="append", choices=["qwen2.5:3b", "qwen2.5:1.5b"], required=True)
    parser.add_argument("--runs", type=int, default=8)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if urlsplit(args.base).hostname not in {"127.0.0.1", "localhost", "::1"} or not 2 <= args.runs <= 30:
        parser.error("Нужен локальный Ollama и 2–30 повторений")

    def api(path, body=None):
        request = Request(args.base.rstrip("/") + path,
                          data=json.dumps(body).encode() if body is not None else None,
                          headers={"Content-Type": "application/json"})
        with urlopen(request, timeout=120) as response:
            return json.load(response)

    report = {"at": datetime.now(timezone.utc).isoformat(), "ollama": api("/api/version")["version"],
              "samples_per_mode": args.runs, "models": {}, "scope": "Ollama chat API, direct loopback"}
    available = {row["name"]: row["digest"] for row in api("/api/tags")["models"]}
    for model in args.model:
        assert model in available, f"Скачайте локальные веса {model} до прогона"
        common = {"model": model, "stream": False, "think": False, "options": {"temperature": 0.2, "num_predict": 150}}
        model_report = {"digest": available[model]}
        try:
            for mode in ("cold", "preloaded"):
                if mode == "preloaded":
                    api("/api/chat", {**common, "messages": [], "keep_alive": "15m"})
                samples = []
                for _ in range(args.runs):
                    keep_alive = 0 if mode == "cold" else "15m"
                    if mode == "cold":
                        api("/api/chat", {**common, "messages": [], "keep_alive": 0})
                    started = time.perf_counter()
                    answer = api("/api/chat", {**common, "messages": PROMPT, "keep_alive": keep_alive})
                    text = answer["message"]["content"]
                    assert answer["done"] and text.strip(), "Ollama не вернул завершённый текст"
                    normalized = text.lower().replace("ё", "е")
                    samples.append({"elapsed_ms": round((time.perf_counter() - started) * 1000, 3),
                                    "load_ms": round(answer.get("load_duration", 0) / 1e6, 3),
                                    "tokens": answer.get("eval_count"),
                                    "reference_facts": ["кассов" in normalized, "процент" in normalized,
                                                        "возобнов" in normalized or "снова" in normalized]})
                elapsed = [sample["elapsed_ms"] for sample in samples]
                model_report[mode] = {"p50_ms": percentile(elapsed, .5), "p95_ms": percentile(elapsed, .95), "samples": samples}
        finally:
            api("/api/chat", {**common, "messages": [], "keep_alive": 0})
        report["models"][model] = model_report
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for model, modes in report["models"].items():
        print(model, {key: value["p95_ms"] for key, value in modes.items() if isinstance(value, dict)})


if __name__ == "__main__":
    main()
