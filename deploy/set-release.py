#!/usr/bin/env python3
"""Change only the release reference while preserving private environment values."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from common import ROOT

parser = argparse.ArgumentParser(description="Выбрать Git-коммит выпуска без перегенерации секретов")
parser.add_argument("release")
parser.add_argument("--env-file", default="deploy/.env")
args = parser.parse_args()
revision = subprocess.check_output(["git", "rev-parse", "--verify", "--end-of-options", args.release + "^{commit}"], cwd=ROOT, text=True).strip()
path = Path(args.env_file).resolve()
lines = path.read_text(encoding="utf-8").splitlines()
assert sum(line.startswith("CABINET_RELEASE=") for line in lines) == 1, "Нужна единственная строка CABINET_RELEASE"
with tempfile.NamedTemporaryFile("w", dir=path.parent, encoding="utf-8", delete=False) as stream:
    temporary = Path(stream.name)
    stream.write("\n".join("CABINET_RELEASE=" + revision if line.startswith("CABINET_RELEASE=") else line for line in lines) + "\n")
os.replace(temporary, path)
print("Выбран выпуск " + revision + "; секреты сохранены")
