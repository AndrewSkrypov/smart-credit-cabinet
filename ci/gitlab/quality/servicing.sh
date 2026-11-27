#!/usr/bin/env bash
set -euo pipefail
source ci/gitlab/quality/runner.sh
: "${CI_JOB_ID:?}" "${CI_PROJECT_ID:?}" "${JAVA_HOME:?}" "${RUNNER_TEMP:?}"
[[ "$CI_JOB_ID" =~ ^[0-9]+$ && "$CI_PROJECT_ID" =~ ^[0-9]+$ ]]
python3 -m unittest discover -s services/servicing/acceptance -p 'test_*.py'
python3 services/servicing/acceptance/run.py \
    --ci-postgres-image 'postgres:16.15-alpine@sha256:deb28ee471183cb0c303113dac9b2433c51eeeeb678def7af9a9ec5820367815' \
    --pg-bin "$(pg_config --bindir)" --java-home "$JAVA_HOME" \
    --gradle "$CI_PROJECT_DIR/gradlew" --gradle-user-home "$RUNNER_TEMP/servicing-gradle" \
    --inherit-proxy-env --artifacts "$CI_PROJECT_DIR/ci-artifacts/servicing" \
    --lock-timeout 60
