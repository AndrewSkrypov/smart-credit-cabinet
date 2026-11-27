#!/usr/bin/env python3
"""after_script cleanup: discover and verify only this job's R3 container labels."""
import os
from pathlib import Path
import sys
import tempfile

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "services/servicing/acceptance"))
from postgres_backend import CiPostgres, POSTGRES_IMAGE, tool_environment

with tempfile.TemporaryDirectory(prefix="servicing-cleanup-") as directory:
    env = tool_environment(os.environ, Path(directory))
    CiPostgres(POSTGRES_IMAGE, Path(directory), env).cleanup_job()
