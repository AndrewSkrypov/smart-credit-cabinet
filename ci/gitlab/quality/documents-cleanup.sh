#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
# after_script starts a fresh shell and must revalidate the isolated daemon.
source ci/gitlab/quality/runner.sh
[[ "${CI_JOB_ID:?}" =~ ^[0-9]+$ && "${CI_PROJECT_ID:?}" =~ ^[0-9]+$ && "${CI_COMMIT_SHA:?}" =~ ^[a-f0-9]{40}$ ]]
for profile in local s3; do
    scope="cabinet-documents-$CI_PROJECT_ID-$CI_JOB_ID-$profile"
    for resource in postgres s3; do
        name="$scope-$resource"
        metadata=$(docker inspect --format '{{.Id}} {{index .Config.Labels "cabinet.fixture"}} {{index .Config.Labels "cabinet.ci.project"}} {{index .Config.Labels "cabinet.ci.job"}} {{index .Config.Labels "cabinet.source"}} {{index .Config.Labels "cabinet.profile"}} {{index .Config.Labels "cabinet.resource"}}' "$name" 2>/dev/null) || continue
        read -r id fixture project job source actual_profile actual_resource extra <<< "$metadata"
        [[ "$id" =~ ^[a-f0-9]{64}$ && "$fixture" == documents-r3 && "$project" == "$CI_PROJECT_ID" && "$job" == "$CI_JOB_ID" && "$source" == "$CI_COMMIT_SHA" && "$actual_profile" == "$profile" && "$actual_resource" == "$resource" && -z "$extra" ]] || exit 2
        docker rm -fv "$id" >/dev/null
    done
    reports=ci-artifacts/documents-r3
    if [[ "$profile" == s3 ]]; then reports+=-s3; fi
    python3 scripts/documents-fixture-cleanup.py "$reports/native-processes.json" --scope "$scope" --source "$CI_COMMIT_SHA"
done
