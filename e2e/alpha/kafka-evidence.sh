#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 {publish|offsets} TOPIC, or $0 capture TOPIC GROUP /tmp/PREFIX" >&2
  exit 2
fi
operation=$1
topic=$2
project=${ALPHA_QA_COMPOSE_PROJECT:?Supply the isolated Alpha Compose project}
if [[ ! "$project" =~ ^cabinet-alpha-[a-z0-9_-]+$ || ! "$topic" =~ ^alpha\.synthetic\.[a-zA-Z0-9._-]+$ ]]; then
  echo "Use the isolated Alpha project and an alpha.synthetic.* topic" >&2
  exit 2
fi
docker_host=${DOCKER_HOST:-}
if [[ -n "${DOCKER_CONTEXT:-}" || -z "$docker_host" ]]; then
  docker_host=$(docker context inspect "$(docker context show)" --format '{{.Endpoints.docker.Host}}')
fi
[[ "$docker_host" == unix:///* ]] || { echo "Use a local Docker daemon" >&2; exit 2; }
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
compose=(docker compose --env-file /dev/null -f "$root/ci/alpha/compose.yaml" -p "$project" --profile alpha-pilot)
kafka() {
  local executable=$1
  shift
  "${compose[@]}" exec -T kafka "/opt/kafka/bin/$executable.sh" --bootstrap-server kafka:9092 "$@"
}
offsets() {
  kafka kafka-get-offsets --topic "$topic" |
    awk -F: -v topic="$topic" '$1 == topic {print; n++} END {if (!n) exit 1}'
}

case "$operation" in
  publish)
    [[ $# == 2 ]] || exit 2
    kafka kafka-console-producer --topic "$topic" --property parse.key=true --property $'key.separator=\t'
    ;;
  offsets)
    [[ $# == 2 ]] || exit 2
    offsets
    ;;
  capture)
    [[ $# == 4 && "$4" == /tmp/* ]] || { echo "Supply a group and a scratch prefix under /tmp" >&2; exit 2; }
    group=$3
    prefix=$4
    offsets > "$prefix-end-offsets.txt"
    kafka kafka-consumer-groups --describe --group "$group" > "$prefix-group.txt"
    count=$(awk -F: '{sum += $3} END {print sum + 0}' "$prefix-end-offsets.txt")
    : > "$prefix-topic.tsv"
    if (( count > 0 )); then
      # A fresh console consumer reads the complete synthetic topic without advancing the product group.
      kafka kafka-console-consumer --topic "$topic" --from-beginning --max-messages "$count" --timeout-ms 15000 \
        --consumer-property enable.auto.commit=false --property print.key=true > "$prefix-topic.tsv"
      [[ $(wc -l < "$prefix-topic.tsv") == "$count" ]] || { echo "Incomplete broker capture for $topic" >&2; exit 1; }
    fi
    ;;
  *) echo "Unknown operation $operation" >&2; exit 2 ;;
esac
