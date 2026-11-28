#!/usr/bin/env bash
set -euo pipefail

[[ "${CI_COMMIT_BRANCH:-}" == dev && "${CI_COMMIT_REF_PROTECTED:-}" == true ]]
[[ "${CI_PIPELINE_SOURCE:-}" =~ ^(api|web)$ ]]
[[ "$CI_COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]]
[[ "$(git --no-replace-objects rev-parse --verify 'HEAD^{commit}')" == "$CI_COMMIT_SHA" ]]

dev_ref=refs/heads/dev
if [[ "$(GIT_TERMINAL_PROMPT=0 timeout 30 git --no-replace-objects ls-remote --exit-code origin "$dev_ref")" != "$CI_COMMIT_SHA"$'\t'"$dev_ref" ]]; then
    echo "This candidate is no longer the tip of protected dev." >&2
    exit 2
fi
