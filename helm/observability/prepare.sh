#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
destination="${1:?usage: prepare.sh NEW_CHART_DIRECTORY}"
mkdir "$destination"
cp -R "$root/helm/observability/chart/." "$destination/"
mkdir -p "$destination/files/dashboards" "$destination/files/rules"
cp "$root"/monitoring/{loki,tempo}.yaml "$destination/files/"
cp "$root/monitoring/grafana/provisioning/datasources/prometheus.yml" "$destination/files/datasources.yaml"
cp "$root"/monitoring/grafana/dashboards/*.json "$destination/files/dashboards/"
cp "$root/monitoring/alerts.yml" "$destination/files/rules/base.yaml"
availability="$root/monitoring/alerts/cabinet-availability.yml"
if [[ -f "$availability" ]]; then
  cp "$availability" "$destination/files/rules/availability.yaml"
fi
