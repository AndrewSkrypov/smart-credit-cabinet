#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
revision=$(git rev-parse HEAD)
if [[ -n "${CI_COMMIT_SHA:-}" && "$revision" != "$CI_COMMIT_SHA" ]]; then
    echo "Alpha checkout must match CI_COMMIT_SHA" >&2
    exit 2
fi
if [[ -n "$(git status --porcelain --untracked-files=all -- ci/alpha)" ]]; then
    echo "Commit ci/alpha changes before collecting revision-bound evidence" >&2
    exit 2
fi
if [[ ! "${ALPHA_PILOT_KAFKA_IMAGE:-}" =~ ^apache/kafka(:[a-zA-Z0-9._-]+)?@sha256:[a-f0-9]{64}$ ]]; then
    echo "Set ALPHA_PILOT_KAFKA_IMAGE to an apache/kafka JVM image pinned by SHA-256 digest" >&2
    exit 2
fi
if [[ ! "${CI_JOB_ID:-0}" =~ ^[0-9]+$ ]]; then
    echo "CI_JOB_ID must be numeric" >&2
    exit 2
fi

# Resolve the endpoint Docker will use. CI's runner.sh checks the daemon label.
docker_host=${DOCKER_HOST:-}
if [[ -n "${DOCKER_CONTEXT:-}" || -z "$docker_host" ]]; then
    docker_host=$(docker context inspect "$(docker context show)" --format '{{.Endpoints.docker.Host}}')
fi
if [[ "$docker_host" != unix:///* ]]; then
    echo "Use a local isolated Docker daemon for Alpha" >&2
    exit 2
fi
docker compose version >&2
docker info --format '{{.ServerVersion}}' >&2

read -r run_id ALPHA_PILOT_PG_ADMIN_PASSWORD ALPHA_PILOT_PG_OWNER_PASSWORD ALPHA_PILOT_PG_APP_PASSWORD < <(
    python3 - <<'PY'
import secrets

print(secrets.token_hex(8), *(secrets.token_hex(24) for _ in range(3)))
PY
)
export ALPHA_PILOT_PG_ADMIN_PASSWORD ALPHA_PILOT_PG_OWNER_PASSWORD ALPHA_PILOT_PG_APP_PASSWORD
project="cabinet-alpha-${CI_JOB_ID:-local}-$run_id"
compose=(docker compose --env-file /dev/null -f ci/alpha/compose.yaml -p "$project" --profile alpha-pilot)

cleanup() {
    local status=$?
    if ! "${compose[@]}" down --volumes >&2; then
        echo "Alpha cleanup failed for $project" >&2
        if [[ "$status" == 0 ]]; then
            status=1
        fi
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
echo "Starting synthetic dependencies in Compose project $project" >&2
"${compose[@]}" up --wait --wait-timeout 150 >&2

# Verify migration DDL through an owner session. Check grants and use TCP as runtime.
"${compose[@]}" exec -T analytics-db sh -eu -c '
    export PGPASSWORD="$ALPHA_PILOT_PG_OWNER_PASSWORD"
    exec psql -X -w -h 127.0.0.1 -U analytics_owner -d analytics -v ON_ERROR_STOP=1
' >&2 <<'SQL'
CREATE TABLE alpha_smoke (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, status text NOT NULL);
SQL

"${compose[@]}" exec -T analytics-db sh -eu -c '
    export PGPASSWORD="$ALPHA_PILOT_PG_APP_PASSWORD"
    exec psql -X -w -h 127.0.0.1 -U analytics_app -d analytics -v ON_ERROR_STOP=1
' >&2 <<'SQL'
DO $check$
BEGIN
    IF current_database() <> 'analytics' OR current_user <> 'analytics_app'
        OR has_database_privilege(current_user, 'postgres', 'CONNECT')
        OR has_database_privilege(current_user, current_database(), 'CREATE,TEMP')
        OR has_schema_privilege(current_user, 'public', 'CREATE')
        OR EXISTS (SELECT FROM pg_database WHERE datname = 'credit')
        OR EXISTS (
            SELECT FROM pg_roles
            WHERE rolname = current_user
              AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
        )
    THEN
        RAISE EXCEPTION 'Analytics database or runtime grants are not isolated';
    END IF;
    INSERT INTO alpha_smoke (status) VALUES ('synthetic');
    UPDATE alpha_smoke SET status = 'checked' WHERE status = 'synthetic';
    IF NOT EXISTS (SELECT FROM alpha_smoke WHERE status = 'checked') THEN
        RAISE EXCEPTION 'Analytics runtime did not persist the synthetic row';
    END IF;
    DELETE FROM alpha_smoke WHERE status = 'checked';
END;
$check$;
SQL

# Distinct generations at the broker level, with opaque synthetic values.
for generation in old new; do
    topic="alpha-smoke-$run_id-$generation"
    group="$topic-consumer"
    "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
        --create --topic "$topic" --partitions 3 --replication-factor 1 >&2
    "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 \
        --create --topic "$topic.dlq" --partitions 3 --replication-factor 1 >&2
    printf 'key:%s\n' "$generation" |
        "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
            --bootstrap-server kafka:9092 --topic "$topic" \
            --property parse.key=true --property key.separator=: >&2
    message=$("${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server kafka:9092 --topic "$topic" --group "$group" \
        --from-beginning --max-messages 1 --timeout-ms 20000 --property print.key=true)
    if [[ "$message" != $'key\t'"$generation" ]]; then
        echo "Unexpected Kafka message for $topic" >&2
        exit 1
    fi
done

# Record only explicit structured fields. Container config and logs may contain passwords.
image_refs=$("${compose[@]}" config --format json | python3 -c '
import json
import sys

for name, service in sorted(json.load(sys.stdin)["services"].items()):
    print(name, service["image"])
')
printf 'revision\t%s\nproject\t%s\n' "$revision" "$project"
printf 'service\tcompose_ref\tcontainer_id\timage_config_id\n'
while read -r service ref; do
    container=$("${compose[@]}" ps -q "$service")
    read -r running configured_ref running_id < <(
        docker container inspect --format '{{.State.Running}} {{.Config.Image}} {{.Image}}' "$container"
    )
    if [[ "$running" != true || "$configured_ref" != "$ref" || ! "$ref" =~ @sha256:[a-f0-9]{64}$ ]]; then
        echo "Unexpected running container or image reference for $service" >&2
        exit 1
    fi
    expected_id=$(docker image inspect --format '{{.Id}}' "$ref")
    if [[ "$running_id" != "$expected_id" || ! "$running_id" =~ ^sha256:[a-f0-9]{64}$ ]]; then
        echo "Unexpected running image for $service" >&2
        exit 1
    fi
    printf '%s\t%s\t%s\t%s\n' "$service" "$ref" "$container" "$running_id"
done <<< "$image_refs"
