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
  --set-string ingress.tlsSecret=cabinet-prod-tls --set-string 'ingress.sourceNamespaces[0]=cabinet-edge' >/dev/null
