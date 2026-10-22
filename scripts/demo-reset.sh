#!/usr/bin/env sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
exec "${PYTHON:-python3}" scripts/demo-reset.py "$@"
