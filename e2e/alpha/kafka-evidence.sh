#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 {publish|offsets} TOPIC, or $0 capture TOPIC GROUP { /tmp | /var/tmp }/PREFIX" >&2
  exit 2
fi
operation=$1
topic=$2
if [[ ! "$topic" =~ ^alpha\.synthetic\.[a-zA-Z0-9._-]+$ ]]; then
  echo "Use an alpha.synthetic.* topic" >&2
  exit 2
fi

if [[ -n "${ALPHA_QA_KAFKA_BIN:-}${ALPHA_QA_KAFKA_BOOTSTRAP:-}" ]]; then
  native_bin=${ALPHA_QA_KAFKA_BIN:?Supply the native Kafka 3.9 CLI directory}
  bootstrap=${ALPHA_QA_KAFKA_BOOTSTRAP:?Supply the isolated native Kafka listener}
  if [[ "$native_bin" != /* || ! -x "$native_bin/kafka-topics.sh" ||
        ! "$bootstrap" =~ ^127\.0\.0\.1:([0-9]{1,5})$ ]] ||
        (( 10#${BASH_REMATCH[1]} < 1 || 10#${BASH_REMATCH[1]} > 65535 )); then
    echo "Use an installed Kafka 3.9 CLI and an explicit IPv4 loopback port" >&2
    exit 2
  fi
  runner=()
  reader_property=--property
  formatter_property=--property
else
  project=${ALPHA_QA_COMPOSE_PROJECT:?Supply the isolated Alpha Compose project}
  [[ "$project" =~ ^cabinet-alpha-[a-z0-9_-]+$ ]] || { echo "Use the isolated Alpha project" >&2; exit 2; }
  docker_host=${DOCKER_HOST:-}
  if [[ -n "${DOCKER_CONTEXT:-}" || -z "$docker_host" ]]; then
    docker_host=$(docker context inspect "$(docker context show)" --format '{{.Endpoints.docker.Host}}')
  fi
  [[ "$docker_host" == unix:///* ]] || { echo "Use a local Docker daemon" >&2; exit 2; }
  root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
  runner=(docker compose --env-file /dev/null -f "$root/ci/alpha/compose.yaml"
    -p "$project" --profile alpha-pilot exec -T kafka)
  native_bin=/opt/kafka/bin
  bootstrap=kafka:9092
  reader_property=--reader-property
  formatter_property=--formatter-property
fi

kafka() {
  local executable=$1
  shift
  "${runner[@]}" "$native_bin/$executable.sh" --bootstrap-server "$bootstrap" "$@"
}
offsets() {
  kafka kafka-get-offsets --topic "$topic" |
    awk -F: -v topic="$topic" '$1 == topic {print; n++} END {if (!n) exit 1}'
}

case "$operation" in
  publish)
    [[ $# == 2 ]] || exit 2
    kafka kafka-console-producer --sync --topic "$topic" "$reader_property" parse.key=true "$reader_property" $'key.separator=\t'
    ;;
  offsets)
    [[ $# == 2 ]] || exit 2
    offsets
    ;;
  capture)
    [[ $# == 4 && ( "$4" == /tmp/* || "$4" == /var/tmp/* ) ]] || { echo "Supply a group and an evidence prefix under /tmp or /var/tmp" >&2; exit 2; }
    group=$3
    prefix=$4
    offsets > "$prefix-end-offsets.txt"
    kafka kafka-consumer-groups --describe --group "$group" > "$prefix-group.txt"
    count=$(awk -F: '{sum += $3} END {print sum + 0}' "$prefix-end-offsets.txt")
    : > "$prefix-topic.tsv"
    if (( count > 0 )); then
      # A fresh console consumer reads the complete synthetic topic without advancing the product group.
      kafka kafka-console-consumer --topic "$topic" --from-beginning --max-messages "$count" --timeout-ms 15000 \
        "$formatter_property" print.key=true > "$prefix-topic.tsv"
      [[ $(wc -l < "$prefix-topic.tsv") == "$count" ]] || { echo "Incomplete broker capture for $topic" >&2; exit 1; }
    fi
    ;;
  *) echo "Unknown operation $operation" >&2; exit 2 ;;
esac
