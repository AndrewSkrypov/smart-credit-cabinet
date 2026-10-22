#!/usr/bin/env python3
"""Summarize native coverage counters without changing their inclusion rules."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description="Сводка фактического покрытия JaCoCo и Vitest")
parser.add_argument("--output", default="build/reports/coverage-summary.json")
args = parser.parse_args()
backend = ET.parse(ROOT / "build/reports/jacoco/jacocoAggregateReport/jacocoAggregateReport.xml").getroot()


def counters(element):
    result = {}
    for counter in element.findall("counter"):
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        result[counter.get("type").lower()] = {"covered": covered, "missed": missed,
                                                "percent": round(100 * covered / (covered + missed), 2) if covered + missed else None}
    return result


report = {"backend": counters(backend), "domain_packages": {},
          "frontend": json.loads((ROOT / "frontend/coverage/coverage-summary.json").read_text(encoding="utf-8"))["total"]}
for package in backend.findall("package"):
    name = package.get("name")
    if "/domain/" in name or name.endswith("/domain"):
        report["domain_packages"][name.replace("/", ".")] = counters(package)
assert report["domain_packages"], "Не найдены пакеты domain; пустой отчёт не является проверкой"
for name, values in report["domain_packages"].items():
    assert values["line"]["percent"] >= 75, "Нарушен порог строк domain: " + name
output = ROOT / args.output
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False, indent=2))
