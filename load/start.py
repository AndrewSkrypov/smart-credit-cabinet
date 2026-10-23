#!/usr/bin/env python3
"""Start only a named synthetic reliability project with bounded lease timings."""
import argparse
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description="Отдельный стенд проверки надёжности")
parser.add_argument("--project", default="cabinet-submission")
parser.add_argument("--replicas", type=int, choices=[1, 2], default=2)
parser.add_argument("--build", action="store_true")
args = parser.parse_args()
if not re.fullmatch(r"cabinet-(?:submission|reliability|e2e|release)(?:-[a-z0-9]+)?", args.project):
    parser.error("Разрешён только отдельный тестовый проект cabinet-submission/cabinet-reliability/cabinet-e2e")
subprocess.run([sys.executable, "load/prepare-config.py"], cwd=ROOT, check=True)
subprocess.run([
    "docker", "compose", "-p", args.project, "-f", "docker-compose.yml", "-f", "load/compose.yaml",
    "up", "-d", "--build" if args.build else "--no-build", "--scale", f"credit={args.replicas}",
    "--wait", "--wait-timeout", "240",
], cwd=ROOT, check=True)
