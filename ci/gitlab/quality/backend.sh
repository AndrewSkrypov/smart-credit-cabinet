#!/usr/bin/env bash
set -euo pipefail

prefix="cabinet-ci-${CI_JOB_ID:?}"
image="${prefix}-postgres:test"
postgres="${prefix}-postgres"
clamav="${prefix}-clamav"
seaweed="${prefix}-seaweedfs"
valkey="${prefix}-valkey"

cleanup() {
    docker rm -fv "$postgres" "$clamav" "$seaweed" "$valkey" >/dev/null 2>&1 || true
    docker image rm "$image" >/dev/null 2>&1 || true
}
trap cleanup EXIT

published_port() {
    local address
    address=$(docker port "$1" "$2/tcp")
    printf '%s\n' "${address##*:}"
}

retry() {
    local attempts=$1 delay=$2
    shift 2
    local i
    for ((i=0; i<attempts-1; i++)); do
        if "$@"; then return; fi
        sleep "$delay"
    done
    "$@"
}

postgres_ready() {
    docker exec "$postgres" pg_isready -U cabinet -d cabinet &&
        docker exec "$postgres" sh -c 'tr "\000" " " </proc/1/cmdline' | grep -q '^postgres'
}

docker build -f docker/postgres/Dockerfile -t "$image" .
python3 security/postgres-image.py --image "$image"
docker run -d --name "$postgres" -p 127.0.0.1::5432 \
    -e POSTGRES_USER=cabinet -e POSTGRES_PASSWORD=cabinet -e POSTGRES_DB=cabinet "$image"
retry 60 1 postgres_ready
export DB_PORT
DB_PORT=$(published_port "$postgres" 5432)
export PGPASSWORD=cabinet
for db in identity credit engagement common_test; do
    psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$DB_PORT" -U cabinet -d cabinet -c "create database $db"
done

clamav_image=$(bash ci/gitlab/quality/clamav-runtime.sh)
docker run -d --name "$clamav" --memory 2g -p 127.0.0.1::3310 \
    --mount type=bind,source="$PWD/docker/clamav/clamd.conf",target=/cabinet/clamd.conf,readonly \
    --mount type=bind,source="$PWD/docker/clamav/healthcheck.sh",target=/cabinet/healthcheck.sh,readonly \
    -e TZ=UTC --entrypoint /bin/sh "$clamav_image" \
    -c 'cp /cabinet/clamd.conf /etc/clamav/clamd.conf && exec /init'
retry 120 3 docker exec "$clamav" sh /cabinet/healthcheck.sh
docker run --rm --network none \
    --mount type=bind,source="$PWD/docker/clamav",target=/cabinet,readonly \
    --entrypoint /bin/sh "$clamav_image" /cabinet/test-healthcheck.sh
export AV_TEST_PORT
AV_TEST_PORT=$(published_port "$clamav" 3310)

docker run -d --name "$seaweed" --memory 512m -p 127.0.0.1::8333 \
    --mount type=bind,source="$PWD/docker/seaweedfs/start.sh",target=/cabinet/start.sh,readonly \
    -e S3_BUCKET=cabinet-documents -e S3_ACCESS_KEY=dev-cabinet-storage \
    -e S3_SECRET_KEY=dev-storage-secret-change-me -e S3_ADMIN_ACCESS_KEY=dev-storage-operator \
    -e S3_ADMIN_SECRET_KEY=dev-storage-operator-secret-change-me -e GOMEMLIMIT=384MiB \
    --entrypoint /bin/sh \
    chrislusf/seaweedfs:4.55@sha256:7ca46aeae2f223b37e5d91593c17ab37738eec6df6ab69b48a0487f7643342b9 \
    /cabinet/start.sh
export TEST_S3_ENDPOINT
TEST_S3_ENDPOINT="http://127.0.0.1:$(published_port "$seaweed" 8333)"
s3_private() {
    local response
    response=$(curl -s -o /dev/null -w '%{http_code}' "$TEST_S3_ENDPOINT/cabinet-documents/") &&
        test "$response" = 403
}
retry 60 2 s3_private

docker run -d --name "$valkey" --memory 128m -p 127.0.0.1::6379 \
    --mount type=bind,source="$PWD/docker/valkey/start.sh",target=/cabinet/start.sh,readonly \
    -e VALKEY_PASSWORD=dev-valkey-integration-password --entrypoint /bin/sh \
    valkey/valkey:9.1.2-alpine@sha256:48332870af354a799964c0012ae1194a0bf2bf894eb508f945810596dc2d8d11 /cabinet/start.sh
valkey_ready() {
    docker exec -e VALKEYCLI_AUTH=dev-valkey-integration-password "$valkey" valkey-cli ping | grep -qx PONG
}
retry 30 1 valkey_ready
export VALKEY_TEST_PORT
VALKEY_TEST_PORT=$(published_port "$valkey" 6379)

OUTBOX_WORKER_ENABLED=false ./gradlew build --no-daemon

mkdir -p ci-artifacts
for db in identity credit engagement; do
    psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$DB_PORT" -U cabinet -d cabinet \
        -c "create database cabinet_test_$db template $db"
done
python3 deploy/check-schema-rollback.py --pg-bin "$(pg_config --bindir)" --port "$DB_PORT" --user cabinet \
    --source-prefix cabinet_test_ --output ci-artifacts/schema-rollback.json
python3 deploy/check-migration-restore.py --pg-bin "$(pg_config --bindir)" --java-bin "${JAVA_HOME:?}/bin" \
    --port "$DB_PORT" --user cabinet --output ci-artifacts/migration-restore.json

test "$(find services -path '*/build/libs/app.jar' | wc -l)" -eq 4
sha256sum services/*/build/libs/app.jar > ci-artifacts/backend.sha256
