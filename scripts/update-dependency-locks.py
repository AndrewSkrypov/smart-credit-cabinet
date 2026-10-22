#!/usr/bin/env python3
"""Resolve and lock both the included build and all service/library projects."""
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
wrapper = ["cmd.exe", "/d", "/c", "gradlew.bat"] if os.name == "nt" else ["./gradlew"]
for arguments in (
    ["-p", "build-logic", "resolveAndLockAll", "--write-locks"],
    ["updateDependencyLocks", "--write-locks"],
):
    subprocess.run(wrapper + arguments + ["--no-daemon", "--console=plain"], cwd=ROOT, check=True)
