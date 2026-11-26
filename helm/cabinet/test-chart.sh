#!/bin/sh
# Render both installation phases with non-deployable fixtures.
set -eu
chart=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
source_root=$(CDPATH='' cd -- "$chart/../.." && pwd)
digest=$(printf '%064d' 0)
revision=$(printf '%040d' 1)
for file in "$source_root"/docker/gateway/nginx.conf "$source_root"/docker/gateway/conf.d/*.conf; do
  cmp "$file" "$chart/files/gateway/${file##*/}"
done
cmp "$source_root/docker/postgres/bootstrap.sh" "$chart/files/bootstrap.sh"
cmp "$source_root/docker/valkey/start.sh" "$chart/files/start.sh"
cmp "$source_root/docker/seaweedfs/start.sh" "$chart/files/seaweedfs.sh"
cmp "$source_root/docker/clamav/clamd.conf" "$chart/files/clamd.conf"
cmp "$source_root/docker/clamav/healthcheck.sh" "$chart/files/healthcheck.sh"
cmp "$source_root/libs/platform-common/src/main/resources/cabinet/database-permissions.sql" "$chart/files/database-permissions.sql"
infra="images.postgres=example.invalid/postgres@sha256:$digest"
helm lint "$chart" --set app.enabled=false --set-string "$infra"
helm template cabinet "$chart" --namespace cabinet --set app.enabled=false --set-string "$infra" >/dev/null
set -- --set-string "$infra" --set-string "release.revision=$revision" \
  --set-string app.publicURL=https://cabinet.example.invalid --set-string gateway.dnsResolver=10.96.0.10 \
  --set-string 'gateway.trustedProxyCIDRs[0]=10.0.0.1/32' --set gateway.service.type=NodePort \
  --set gateway.service.nodePort=30880 --set-string s3.endpoint=https://s3.example.invalid
for name in identity credit engagement external-sim frontend swagger; do
  set -- "$@" --set-string "images.$name=example.invalid/$name@sha256:$digest"
done
helm lint "$chart" "$@"
helm template cabinet "$chart" --namespace cabinet --is-upgrade "$@"

helm template cabinet "$chart" --namespace cabinet --is-upgrade "$@" \
  --set gateway.service.type=ClusterIP --set ingress.enabled=true \
  --set-string ingress.className=cabinet-public --set-string ingress.host=cabinet.example.invalid \
  --set-string ingress.tlsSecret=cabinet-prod-tls --set-string 'ingress.sourceNamespaces[0]=cabinet-prod' \
  --set-json 'ingress.sourcePodLabels={"app.kubernetes.io/instance":"cabinet-edge-cabinet-prod"}' >/dev/null

helm lint "$chart" --set app.enabled=false --set s3.internal.enabled=true --set-string "$infra"
helm template cabinet "$chart" --namespace cabinet --set app.enabled=false --set s3.internal.enabled=true --set-string "$infra" >/dev/null
helm lint "$chart" "$@" --set-string s3.endpoint= --set s3.internal.enabled=true
helm template cabinet "$chart" --namespace cabinet --is-upgrade "$@" \
  --set-string s3.endpoint= --set s3.internal.enabled=true >/dev/null

# Exercise production-only bridge and restricted OTLP egress.
sh -n "$chart/files/ollama-ssh-bridge.sh"
set -- "$@" --set ollamaBridge.enabled=true --set-string ollamaBridge.host=192.0.2.10 \
  --set ollamaBridge.port=2222 --set-string "images.ssh-bridge=example.invalid/ssh-bridge@sha256:$digest" \
  --set-string app.otlpEndpoint=http://tempo.monitoring.svc.cluster.local:4318/v1/traces \
  --set-string ollamaBridge.otlp.namespace=monitoring --set-string ollamaBridge.otlp.podLabels.app=tempo
helm template cabinet "$chart" --namespace cabinet-prod --is-upgrade "$@" >/dev/null
if helm template cabinet "$chart" --namespace cabinet-dev --is-upgrade "$@" >/dev/null 2>&1; then
  echo 'Ollama SSH bridge unexpectedly rendered outside cabinet-prod' >&2
  exit 1
fi
