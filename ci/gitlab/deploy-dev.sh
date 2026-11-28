#!/usr/bin/env bash
set -euo pipefail

: "${CI_COMMIT_SHA:?}" "${CABINET_DEV_SSH_HOST:?}" "${CABINET_DEV_SSH_KEY:?}" "${CABINET_DEV_KNOWN_HOSTS:?}"
[[ "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ ]]
[[ "$CABINET_DEV_SSH_HOST" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]]
python3 ci/gitlab/release-manifest.py release.json "$CI_COMMIT_SHA" "$CI_REGISTRY_IMAGE"

if [[ "${CI_COMMIT_BRANCH:-}" == dev ]]; then
    bash ci/gitlab/assert-dev-tip.sh
fi

ssh_args=(-o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$CABINET_DEV_KNOWN_HOSTS" -o IdentitiesOnly=yes -i "$CABINET_DEV_SSH_KEY")
remote="cabinet-deploy@$CABINET_DEV_SSH_HOST"
git archive "$CI_COMMIT_SHA" | ssh "${ssh_args[@]}" "$remote" "stage $CI_COMMIT_SHA"
ssh "${ssh_args[@]}" "$remote" "deploy $CI_COMMIT_SHA" < release.json
