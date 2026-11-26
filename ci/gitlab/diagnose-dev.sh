#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${CI_COMMIT_SHA:?}" "${CABINET_DEV_SSH_HOST:?}" "${CABINET_DEV_SSH_KEY:?}" "${CABINET_DEV_KNOWN_HOSTS:?}"
[[ "${CABINET_DEV_DIAGNOSTICS:-}" == 1 && "${CI_COMMIT_REF_PROTECTED:-}" == true ]]
[[ "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ && "$(git --no-replace-objects rev-parse --verify 'HEAD^{commit}')" == "$CI_COMMIT_SHA" ]]
[[ "$CABINET_DEV_SSH_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]]

refuse_source() {
    printf '%s\n' "$1" >&2
    exit 2
}

# Inspect the actual commit object: shallow history can hide its parents from rev-list.
parent_headers=$(git --no-replace-objects cat-file commit "$CI_COMMIT_SHA" | awk '
    BEGIN { headers = 1 }
    /^$/ { headers = 0 }
    headers && /^parent [0-9a-f]+$/ { print $2 }
')
mapfile -t parents <<< "$parent_headers"
if [[ ${#parents[@]} != 2 || ! "${parents[1]}" =~ ^[a-f0-9]{40}$ ]]; then
    refuse_source "Diagnostics requires a protected merge commit with exactly two parents"
fi
source_sha=${parents[1]}
[[ "$source_sha" != "$CI_COMMIT_SHA" ]] || refuse_source "The diagnostic source must have a separate SHA"
if ! git --no-replace-objects merge-base --is-ancestor "$source_sha" "$CI_COMMIT_SHA" 2>/dev/null; then
    [[ "$(git rev-parse --is-shallow-repository)" == true ]] ||
        refuse_source "Unable to prove diagnostic source ancestry"
    # Fetch only the already checked-out protected commit to make its direct parents visible.
    GIT_TERMINAL_PROMPT=0 GIT_TRACE=0 GIT_TRACE_PACKET=0 GIT_TRACE_CURL=0 GIT_CURL_VERBOSE=0 \
        GIT_TRACE2=0 GIT_TRACE2_EVENT=0 GIT_TRACE2_PERF=0 \
        git --no-replace-objects fetch --no-tags --no-recurse-submodules --depth=2 origin "$CI_COMMIT_SHA" \
        >/dev/null 2>&1 || refuse_source "Unable to fetch the protected merge parents"
fi
git --no-replace-objects merge-base --is-ancestor "$source_sha" "$CI_COMMIT_SHA" 2>/dev/null ||
    refuse_source "Unable to prove diagnostic source ancestry"
source_tree=$(git --no-replace-objects rev-parse --verify "$source_sha^{tree}")
merge_tree=$(git --no-replace-objects rev-parse --verify "$CI_COMMIT_SHA^{tree}")
[[ "$source_tree" == "$merge_tree" ]] || refuse_source "The diagnostic source tree differs from the protected merge"
[[ "$(git --no-replace-objects cat-file -t "$source_sha:deploy/dev_diagnostics.py" 2>/dev/null)" == blob ]] ||
    refuse_source "The diagnostic source tree lacks deploy/dev_diagnostics.py"
git --no-replace-objects diff --quiet "$CI_COMMIT_SHA" -- deploy/dev_diagnostics.py ||
    refuse_source "The checked-out diagnostics module differs from the protected merge"
printf 'Diagnostic source %s approved by protected merge %s\n' "$source_sha" "$CI_COMMIT_SHA"

ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$CABINET_DEV_KNOWN_HOSTS"
          -o IdentitiesOnly=yes -i "$CABINET_DEV_SSH_KEY" -o ConnectTimeout=15)
remote="cabinet-deploy@$CABINET_DEV_SSH_HOST"
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
python3 -IB - "$source_sha" > "$scratch/request.json" <<'PY'
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path("deploy").resolve()))
from dev_diagnostics import OPERATION, TARGET_MANIFEST_SHA256, TARGET_REVISION

print(json.dumps({"version": 1, "operation": OPERATION, "revision": sys.argv[1],
                  "target_revision": TARGET_REVISION, "target_manifest_sha256": TARGET_MANIFEST_SHA256}))
PY

git --no-replace-objects archive "$source_sha" | ssh "${ssh_args[@]}" "$remote" "stage $source_sha" >/dev/null
ssh "${ssh_args[@]}" "$remote" "deploy $source_sha" < "$scratch/request.json" |
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
