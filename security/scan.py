#!/usr/bin/env python3
"""Scan committed sources or local images with a pinned scanner and retained evidence."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
from scan_report import sanitize

ROOT = Path(__file__).resolve().parent.parent
SCANNER = "aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c"
parser = argparse.ArgumentParser(description="Trivy: снимок Git или локальные Docker-образы; реальные результаты сохраняются")
parser.add_argument("--image", action="append", default=[], help="Локальный образ; можно повторить. Без образов проверяется HEAD")
parser.add_argument("--remote", action="store_true", help="Проверять образ по digest в registry без локальной копии и Docker save")
parser.add_argument("--report-name", default="filesystem", help="Имя группы отчётов, буквы/цифры/_/-")
parser.add_argument("--cache-volume", default="cabinet-submission-trivy-cache")
parser.add_argument("--keep-archives", action="store_true", help="Сохранить входные tar; по умолчанию после отчёта они удаляются")
args = parser.parse_args()
if args.remote and (not args.image or any(not re.fullmatch(r"[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}", value) for value in args.image)):
    parser.error("Для удалённого сканирования каждый образ должен быть закреплён sha256 digest")
if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.report_name) or not re.fullmatch(r"cabinet-[a-z0-9-]{1,100}", args.cache_volume):
    parser.error("Неверное имя отчёта или отдельного тома кэша")
directory = ROOT / "security/reports" / args.report_name
directory.mkdir(parents=True, exist_ok=True)
revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
dirty = bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())
mounts = ["-v", f"{directory}:/reports", "-v", f"{args.cache_volume}:/root/.cache"]
summary = {"checked_at": datetime.now(timezone.utc).isoformat(), "source_commit": revision, "working_tree_dirty": dirty,
           "scanner": SCANNER, "inputs": [], "passed": False}


def execute(command):
    process = subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
    for line in process.stdout:
        print(line.rstrip(), flush=True)
    code = process.wait()
    if code:
        raise AssertionError(f"Сканирование не завершено: код процесса {code}")


def retain(raw_file, output_file, metadata):
    report = json.loads(raw_file.read_text(encoding="utf-8"))
    safe, gate = sanitize(report)
    metadata.update(gate)
    output_file.write_text(json.dumps(safe, ensure_ascii=False, indent=2), encoding="utf-8")
    assert raw_file.resolve().parent == directory.resolve()
    raw_file.unlink()
    return metadata


try:
    if not args.image:
        archive = directory / "source.tar"
        subprocess.run(["git", "archive", "--format=tar", "-o", str(archive), revision], cwd=ROOT, check=True)
        archive_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
        command = ["docker", "run", "--rm", *mounts, "--entrypoint", "sh", SCANNER, "-c",
                   'mkdir /scan && tar -xf /reports/source.tar -C /scan && exec "$@"', "scan", "trivy", "fs",
                   "--no-progress", "--timeout", "15m", "--scanners", "vuln,secret", "--include-dev-deps", "--show-suppressed",
                   "--ignorefile", "/scan/.trivyignore", "--format", "json",
                   "--output", "/reports/raw-filesystem.json", "/scan"]
        started = time.monotonic()
        execute(command)
        summary["inputs"].append(retain(directory / "raw-filesystem.json", directory / "trivy-filesystem.json",
                                        {"kind": "git-head", "archive_sha256": archive_hash, "seconds": time.monotonic()-started}))
        if not args.keep_archives:
            assert archive.resolve().parent == directory.resolve()
            archive.unlink()
    else:
        for index, reference in enumerate(args.image):
            if args.remote:
                started = time.monotonic()
                execute(["docker", "run", "--rm", *mounts, "-v", f"{ROOT / '.trivyignore'}:/policy/ignore:ro", SCANNER,
                         "image", "--image-src", "remote", "--parallel", "1", "--no-progress", "--timeout", "15m",
                         "--scanners", "vuln,secret", "--show-suppressed", "--ignorefile", "/policy/ignore",
                         "--format", "json", "--output", f"/reports/raw-image-{index}.json", reference])
                summary["inputs"].append(retain(directory / f"raw-image-{index}.json", directory / f"trivy-image-{index}.json",
                                                {"kind": "registry-digest", "reference": reference, "seconds": time.monotonic()-started}))
                continue
            image = json.loads(subprocess.check_output(["docker", "image", "inspect", reference], text=True, encoding="utf-8"))[0]
            archive = directory / f"image-{index}.tar"
            execute(["docker", "save", "-o", str(archive), image["Id"]])
            started = time.monotonic()
            execute(["docker", "run", "--rm", *mounts, "-v", f"{ROOT / '.trivyignore'}:/policy/ignore:ro", SCANNER,
                     "image", "--no-progress", "--timeout", "15m", "--scanners", "vuln,secret", "--show-suppressed",
                     "--ignorefile", "/policy/ignore", "--format", "json", "--output", f"/reports/raw-image-{index}.json",
                     "--input", f"/reports/image-{index}.tar"])
            summary["inputs"].append(retain(directory / f"raw-image-{index}.json", directory / f"trivy-image-{index}.json",
                                            {"kind": "image", "reference": reference, "image_id": image["Id"], "seconds": time.monotonic()-started}))
            if not args.keep_archives:
                assert archive.resolve().parent == directory.resolve()
                archive.unlink()
    summary["passed"] = all(item["passed"] for item in summary["inputs"])
finally:
    (directory / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))
raise SystemExit(0 if summary["passed"] else 1)
