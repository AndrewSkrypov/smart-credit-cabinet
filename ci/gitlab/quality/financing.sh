#!/usr/bin/env bash
set -euo pipefail

job_id="${CI_JOB_ID:?}"
[[ "$job_id" =~ ^[0-9]+$ ]]
expected_project="cabinet-e2e-$job_id"
test "${COMPOSE_PROJECT_NAME:?}" = "$expected_project"
test "${CABINET_URL:?}" = "http://127.0.0.1:${GATEWAY_PORT:?}"
source ci/gitlab/quality/runner.sh
postgres="$(docker compose ps -q postgres)"
test -n "$postgres"
test "$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$postgres")" = "$expected_project"

# The first suite can leave application, mandate and loan state. The final
# financing fixtures expect a fresh database and are consumed by one execution.
docker compose --profile monitoring down --volumes --remove-orphans
docker compose --profile monitoring up -d --no-build --wait --wait-timeout 300
npm run wait --prefix e2e
docker compose exec -T postgres sh -ec '
    exec psql -X -U "$POSTGRES_USER" -d credit -v ON_ERROR_STOP=1 -v synthetic_fixture=isolated
' < e2e/fixtures/final-agent-financing-reserve.sql
CABINET_FINAL_FINANCING_E2E=1 CABINET_EXPECTED_INVOICE_MODE=MANUAL \
    npm run test:desktop --prefix e2e -- --trace off
credit_runtime="$(docker inspect --format '{{.RestartCount}} {{.State.OOMKilled}}' "$(docker compose ps -q credit)")"
printf 'Final financing Credit restart count and OOMKilled: %s\n' "$credit_runtime"
test "$credit_runtime" = '0 false'
