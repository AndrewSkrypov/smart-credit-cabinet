#!/usr/bin/env bash
set -euo pipefail

: "${CI_COMMIT_SHA:?}" "${CABINET_DEV_SSH_HOST:?}" "${CABINET_DEV_SSH_KEY:?}" "${CABINET_DEV_KNOWN_HOSTS:?}"
[[ "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ ]]
[[ "$CABINET_DEV_SSH_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]]
python3 ci/gitlab/release-manifest.py release.json "$CI_COMMIT_SHA" "$CI_REGISTRY_IMAGE"

ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$CABINET_DEV_KNOWN_HOSTS" -o IdentitiesOnly=yes -i "$CABINET_DEV_SSH_KEY")
remote="cabinet-deploy@$CABINET_DEV_SSH_HOST"
if [[ -z "${CABINET_DEV_JURY_BATCH:-}" || "${CI_COMMIT_BRANCH:-}" != dev ]]; then
    git archive "$CI_COMMIT_SHA" | ssh "${ssh_args[@]}" "$remote" "stage $CI_COMMIT_SHA"
    ssh "${ssh_args[@]}" "$remote" "deploy $CI_COMMIT_SHA" < release.json
    exit
fi

[[ "${CI_COMMIT_REF_PROTECTED:-}" == true && "${CI_ENVIRONMENT_NAME:-}" == dev ]]
umask 077
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
jury_batch_id=$(python3 -B deploy/jury_atf.py "$CABINET_DEV_JURY_BATCH" "$work/.dev-jury-batch.json")
if git cat-file -e "$CI_COMMIT_SHA:.dev-jury-batch.json" 2>/dev/null; then
    echo "Закрытый пакет жюри не должен храниться в Git" >&2
    exit 1
fi
git archive --format=tar --output="$work/release.tar" "$CI_COMMIT_SHA"
tar --append --file="$work/release.tar" --directory="$work" .dev-jury-batch.json
ssh "${ssh_args[@]}" "$remote" "stage $CI_COMMIT_SHA" < "$work/release.tar"
ssh "${ssh_args[@]}" "$remote" "deploy $CI_COMMIT_SHA" < release.json | tee "$work/report"
python3 - "$work/report" "$jury_batch_id" <<'PY'
import json
from pathlib import Path
import sys

report = Path(sys.argv[1]).read_text()
last = report.rfind("\n{")
try:
    receipt = json.loads(report[last + 1:])["jury_atf"]
    if receipt["batch_id"] == sys.argv[2] and receipt["count"] == 8:
        sys.exit(0)
except (AttributeError, KeyError, TypeError, ValueError):
    pass
sys.exit("Контур Dev не подтвердил активацию загруженного пакета жюри")
PY
