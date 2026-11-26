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
kafka_pin="${ALPHA_PILOT_KAFKA_IMAGE:-$(cat ci/alpha/kafka-image.txt)}"
if [[ ! "$kafka_pin" =~ ^apache/kafka(:[a-zA-Z0-9._-]+)?@sha256:[a-f0-9]{64}$ ]]; then
    echo "Set ALPHA_PILOT_KAFKA_IMAGE to an apache/kafka JVM image pinned by SHA-256 digest" >&2
    exit 2
fi
postgres_pin=postgres:16.15-alpine@sha256:deb28ee471183cb0c303113dac9b2433c51eeeeb678def7af9a9ec5820367815
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

# The same manifest digest is required when using Google's Docker Hub cache.
has_pinned_image() {
    local ref=$1 repository details repo_digest
    local -a fields
    repository=${ref%@*}
    repository=${repository%:*}
    details=$(docker image inspect --format '{{.Os}}/{{.Architecture}} {{join .RepoDigests " "}}' "$ref" 2>/dev/null) || return 1
    read -r -a fields <<< "$details"
    [[ "${fields[0]:-}" == linux/amd64 ]] || return 1
    for repo_digest in "${fields[@]:1}"; do
        if [[ "${repo_digest#docker.io/}" == "$repository@${ref##*@}" ||
            "${repo_digest#docker.io/library/}" == "$repository@${ref##*@}" ]]; then
            return 0
        fi
    done
    return 1
}

select_pinned_image() {
    local official=$1 mirror=$2 ref
    for ref in "$official" "$mirror"; do
        if has_pinned_image "$ref"; then
            printf '%s\n' "$ref"
            return
        fi
    done
    for ref in "$mirror" "$official"; do
        if timeout 75s docker pull --platform linux/amd64 "$ref" >&2 && has_pinned_image "$ref"; then
            printf '%s\n' "$ref"
            return
        fi
    done
    echo "Could not obtain the pinned linux/amd64 image $official" >&2
    return 1
}

kafka_base=$(select_pinned_image "$kafka_pin" "mirror.gcr.io/$kafka_pin")
ALPHA_PILOT_POSTGRES_IMAGE=$(select_pinned_image "$postgres_pin" "mirror.gcr.io/library/$postgres_pin")
export ALPHA_PILOT_POSTGRES_IMAGE
kafka_base_id=$(docker image inspect --format '{{.Id}}' "$kafka_base")
postgres_id=$(docker image inspect --format '{{.Id}}' "$ALPHA_PILOT_POSTGRES_IMAGE")

read -r run_id ALPHA_PILOT_PG_ADMIN_PASSWORD ALPHA_PILOT_PG_OWNER_PASSWORD ALPHA_PILOT_PG_APP_PASSWORD < <(
    python3 - <<'PY'
import secrets

print(secrets.token_hex(8), *(secrets.token_hex(24) for _ in range(3)))
PY
)
export ALPHA_PILOT_PG_ADMIN_PASSWORD ALPHA_PILOT_PG_OWNER_PASSWORD ALPHA_PILOT_PG_APP_PASSWORD
project="cabinet-alpha-${CI_JOB_ID:-local}-$run_id"
export ALPHA_PILOT_KAFKA_IMAGE="cabinet-alpha-kafka:$run_id"
compose=(docker compose --env-file /dev/null -f ci/alpha/compose.yaml -p "$project" --profile alpha-pilot)
compose_started=false
scan_cache=

cleanup() {
    local status=$?
    if [[ "$compose_started" == true ]] && ! "${compose[@]}" down --volumes >&2; then
        echo "Alpha cleanup failed for $project" >&2
        if [[ "$status" == 0 ]]; then
            status=1
        fi
    fi
    if docker image inspect "$ALPHA_PILOT_KAFKA_IMAGE" >/dev/null 2>&1 &&
        ! docker image rm "$ALPHA_PILOT_KAFKA_IMAGE" >&2; then
        echo "Alpha cleanup failed for $ALPHA_PILOT_KAFKA_IMAGE" >&2
        if [[ "$status" == 0 ]]; then
            status=1
        fi
    fi
    if [[ -n "$scan_cache" ]] && docker volume inspect "$scan_cache" >/dev/null 2>&1 &&
        ! docker volume rm "$scan_cache" >&2; then
        echo "Alpha cleanup failed for $scan_cache" >&2
        if [[ "$status" == 0 ]]; then
            status=1
        fi
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
timeout 120s docker build --platform linux/amd64 --build-arg "UPSTREAM_KAFKA_IMAGE=$kafka_base" \
    --tag "$ALPHA_PILOT_KAFKA_IMAGE" --file ci/alpha/Kafka.Dockerfile ci/alpha >&2
kafka_image_id=$(docker image inspect --format '{{.Id}}' "$ALPHA_PILOT_KAFKA_IMAGE")
kafka_base_label=$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.base.name"}}' "$ALPHA_PILOT_KAFKA_IMAGE")
if [[ "$kafka_base_label" != "$kafka_base" || "$kafka_image_id" == "$kafka_base_id" ||
    ! "$kafka_base_id" =~ ^sha256:[a-f0-9]{64}$ || ! "$kafka_image_id" =~ ^sha256:[a-f0-9]{64}$ ]]; then
    echo "The locally built Kafka image does not match the pinned upstream base" >&2
    exit 1
fi

if [[ "${ALPHA_PILOT_SCAN_KAFKA:-0}" == 1 ]]; then
    scan_cache="cabinet-alpha-scan-${CI_JOB_ID:-local}-$run_id"
    python3 security/scan.py --image "$kafka_image_id" --report-name alpha-kafka --cache-volume "$scan_cache" >&2
fi

echo "Starting synthetic dependencies in Compose project $project" >&2
compose_started=true
"${compose[@]}" up --pull never --wait --wait-timeout 150 >&2

if ! "${compose[@]}" exec -T kafka apk info --exists 'brotli-libs=1.2.1-r0' >/dev/null; then
    echo "The running Kafka container is missing patched brotli-libs" >&2
    exit 1
fi

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
            --reader-property parse.key=true --reader-property key.separator=: >&2
    message=$("${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
        --bootstrap-server kafka:9092 --topic "$topic" --group "$group" \
        --from-beginning --max-messages 1 --timeout-ms 20000 --formatter-property print.key=true)
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
printf 'kafka_base_ref\t%s\nkafka_base_image_config_id\t%s\n' "$kafka_base" "$kafka_base_id"
printf 'service\tcompose_ref\tcontainer_id\timage_config_id\n'
while read -r service ref; do
    case "$service" in
        kafka) expected_ref=$ALPHA_PILOT_KAFKA_IMAGE; expected_id=$kafka_image_id ;;
        analytics-db) expected_ref=$ALPHA_PILOT_POSTGRES_IMAGE; expected_id=$postgres_id ;;
        *) echo "Unexpected Alpha service $service" >&2; exit 1 ;;
    esac
    container=$("${compose[@]}" ps -q "$service")
    read -r running configured_ref running_id < <(
        docker container inspect --format '{{.State.Running}} {{.Config.Image}} {{.Image}}' "$container"
    )
    if [[ "$running" != true || "$configured_ref" != "$ref" || "$ref" != "$expected_ref" ]]; then
        echo "Unexpected running container or image reference for $service" >&2
        exit 1
    fi
    if [[ "$running_id" != "$expected_id" || ! "$running_id" =~ ^sha256:[a-f0-9]{64}$ ]]; then
        echo "Unexpected running image for $service" >&2
        exit 1
    fi
    printf '%s\t%s\t%s\t%s\n' "$service" "$ref" "$container" "$running_id"
done <<< "$image_refs"
