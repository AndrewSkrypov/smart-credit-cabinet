#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${CI_COMMIT_SHA:?}" "${CABINET_DEV_SSH_HOST:?}" "${CABINET_DEV_SSH_KEY:?}" "${CABINET_DEV_KNOWN_HOSTS:?}"
[[ "${CABINET_DEV_DIAGNOSTICS:-}" == 1 && "${CI_COMMIT_REF_PROTECTED:-}" == true ]]
[[ "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ && "$(git rev-parse HEAD)" == "$CI_COMMIT_SHA" ]]
[[ "$CABINET_DEV_SSH_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]]

ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$CABINET_DEV_KNOWN_HOSTS"
          -o IdentitiesOnly=yes -i "$CABINET_DEV_SSH_KEY" -o ConnectTimeout=15)
remote="cabinet-deploy@$CABINET_DEV_SSH_HOST"
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
python3 -IB - "$CI_COMMIT_SHA" > "$scratch/request.json" <<'PY'
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path("deploy").resolve()))
from dev_diagnostics import OPERATION, TARGET_MANIFEST_SHA256, TARGET_REVISION

print(json.dumps({"version": 1, "operation": OPERATION, "revision": sys.argv[1],
                  "target_revision": TARGET_REVISION, "target_manifest_sha256": TARGET_MANIFEST_SHA256}))
PY

git archive "$CI_COMMIT_SHA" | ssh "${ssh_args[@]}" "$remote" "stage $CI_COMMIT_SHA" >/dev/null
ssh "${ssh_args[@]}" "$remote" "deploy $CI_COMMIT_SHA" < "$scratch/request.json" |
python3 -IB -c '
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path("deploy").resolve()))
from dev_diagnostics import MAX_REPORT

request = json.loads(Path(sys.argv[1]).read_bytes())
raw = sys.stdin.buffer.read(MAX_REPORT + 2)
if len(raw) > MAX_REPORT + 1:
    raise SystemExit("Diagnostic report exceeded its size limit")
try:
    report = json.loads(raw)
except (ValueError, RecursionError):
    raise SystemExit("Invalid diagnostic report") from None
fields = {"current", "pending", "containers", "networks", "error_counts"}
if (not isinstance(report, dict) or set(report) != set(request) | fields
        or any(report[key] != value for key, value in request.items())):
    raise SystemExit("Diagnostic report does not match its request")
Path("dev-diagnostics.json").write_bytes(raw)
' "$scratch/request.json"
