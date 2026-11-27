#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

if [[ $# != 2 || $1 != /* ]]; then
    echo "Usage: $0 /absolute/path/to/postgresql/bin isolated-ci-postgres-port" >&2
    exit 2
fi
: "${PGPASSWORD:?Set the password of the disposable CI PostgreSQL role cabinet}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
pg_bin=$1
pg_port=$2
scratch=$(mktemp -d /tmp/alpha-assessment-ci.XXXXXXXX)
trap 'rm -rf -- "$scratch"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
reports=ci-artifacts/assessment
mkdir -p "$reports"
archive=kafka_2.13-4.3.2.tgz

bash e2e/alpha-assessment/verify-migrations.sh "$pg_bin" "$pg_port" 2>&1 | tee "$reports/migrations.log"
if [[ -n "${ASSESSMENT_KAFKA_ARCHIVE:-}" ]]; then
    cp -- "$ASSESSMENT_KAFKA_ARCHIVE" "$scratch/$archive"
else
    curl_flags=(--fail --silent --show-error --location --proto '=https' --proto-redir '=https' --retry 2 --connect-timeout 12 --max-time 180)
    if ! curl "${curl_flags[@]}" "https://dlcdn.apache.org/kafka/4.3.2/$archive" -o "$scratch/$archive"; then
        curl "${curl_flags[@]}" "https://archive.apache.org/dist/kafka/4.3.2/$archive" -o "$scratch/$archive"
    fi
fi
pin=$PWD/e2e/alpha-assessment/kafka-ci.sha512
(cd "$scratch" && sha512sum --check --strict "$pin") | tee "$reports/kafka-distribution.log"
tar -xzf "$scratch/$archive" -C "$scratch"
python3 -EBu e2e/alpha-assessment/verify_exchange.py \
    --credit services/credit/build/libs/app.jar --assessment services/assessment/build/libs/app.jar \
    --pg-bin "$pg_bin" --postgres-port "$pg_port" --kafka-home "$scratch/kafka_2.13-4.3.2" \
    --require-replay-test 2>&1 | tee "$reports/exchange.log"
