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
sh -n "$chart/files/clamav-seed.sh"
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
mcp_render=$(mktemp)
trap 'rm -f "$mcp_render"' EXIT
helm template cabinet "$chart" --namespace cabinet --is-upgrade "$@" \
  --set agentMcpCredit.enabled=true \
  --set-string "images.mcp=example.invalid/mcp@sha256:$digest" \
  --set-string "agentMcpCredit.peerSha256=$digest" \
  --set-string agentMcpCredit.tlsSecret=fixture-mcp-tls \
  --set-string agentMcpCredit.redemptionSecret=fixture-mcp-redeem \
  --set-string agentMcpCredit.identityAuthSecret=fixture-mcp-auth \
  --set-string agentMcpCredit.identityTlsSecret=fixture-mcp-identity-tls \
  --set-string agentMcpCredit.clientTlsSecret=fixture-mcp-client-tls \
  --set-string agentMcpCredit.oauth.clientId=cabinet-local-fixture \
  --set-string agentMcpCredit.oauth.redirectUri=http://127.0.0.1:8912/callback >"$mcp_render"
grep -F 'port: 8443' "$mcp_render" >/dev/null
grep -F 'port: 8444' "$mcp_render" >/dev/null
grep -F 'include /etc/nginx/cabinet-gateway/mcp.conf;' "$mcp_render" >/dev/null
grep -F 'https://cabinet-mcp-identity:8444' "$mcp_render" >/dev/null
grep -F 'kind: NetworkPolicy' "$mcp_render" >/dev/null
rm -f "$mcp_render"
trap - EXIT

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

# Demo is an optional production proxy, never a switch for local demo accounts.
set -- "$@" --set ollamaBridge.enabled=false --set publicDemo.enabled=true \
  --set-string publicDemo.upstreamIPv4=192.0.2.20 --set-string app.publicURL=https://credit-cabinet.ru
helm template cabinet "$chart" --namespace cabinet-prod --is-upgrade "$@" \
  --show-only templates/demo-proxy.yaml | grep -F 'cidr: "192.0.2.20/32"' >/dev/null
helm template cabinet "$chart" --namespace cabinet-prod --is-upgrade "$@" \
  --show-only templates/gateway-config.yaml | grep -F 'location = /demo/api/v1 { return 404; }' >/dev/null
if helm template cabinet "$chart" --namespace cabinet-dev --is-upgrade "$@" >/dev/null 2>&1; then
  echo 'Demo proxy unexpectedly rendered outside cabinet-prod' >&2
  exit 1
fi

# Catalog publishes a digest even when the chart stays disabled. Remote requires opt-in and both TLS secrets.
catalog="$chart/files/catalog"
sh -n "$catalog/migrate.sh"
cmp "$source_root/deploy/catalog-sql/roles.sql" "$catalog/roles.sql"
cmp "$source_root/deploy/catalog-sql/fence-check.sql" "$catalog/fence-check.sql"
cmp "$source_root/services/catalog-grpc/src/main/resources/db/catalog/V1__product_history.sql" "$catalog/V1__product_history.sql"
for sql in grants.sql credit-fence.sql; do
  cmp "$source_root/services/catalog-grpc/cutover/$sql" "$catalog/$sql"
done
if helm template cabinet "$chart" --namespace cabinet-prod --is-upgrade "$@" \
  --set catalog.creditMode=remote >/dev/null 2>&1; then
  echo 'Remote Credit unexpectedly rendered without enabled Catalog' >&2
  exit 1
fi
set -- "$@" --set publicDemo.enabled=false --set catalog.enabled=true \
  --set-string "images.catalog-grpc=example.invalid/catalog-grpc@sha256:$digest" \
  --set-string catalog.databaseSecret=cabinet-catalog-db --set-string catalog.creditTLSSecret=cabinet-catalog-credit \
  --set-string catalog.serverTLSSecret=cabinet-catalog-server
helm lint "$chart" "$@"
for mode in local remote; do
  helm template cabinet "$chart" --namespace cabinet-prod --is-upgrade "$@" \
    --set "catalog.creditMode=$mode" --show-only templates/catalog.yaml \
    | grep -F "{name: CATALOG_MODE, value: \"$mode\"}" >/dev/null
done

helm template cabinet "$chart" --namespace cabinet-prod --is-upgrade "$@" --set catalog.maintenance=true \
  --show-only templates/catalog-release-state.yaml | grep -F '"maintenance":true' >/dev/null
helm template cabinet "$chart" --namespace cabinet-prod --is-upgrade "$@" --set catalog.maintenance=true \
  --show-only templates/apps.yaml | awk '
    /^kind: Deployment$/ { deployment = 1; gateway = 0 }
    /^---$/ { deployment = 0; gateway = 0 }
    deployment && /^  name: gateway$/ { gateway = 1 }
    gateway && /^  replicas: 0$/ { found = 1 }
    END { exit !found }
  '

# All private candidates share one release. Credit needs both TLS mounts and the Assessment settings.
joint=$(helm template cabinet "$chart" --namespace cabinet-dev --is-upgrade "$@" \
  -f "$chart/values.assessment.yaml" -f "$chart/values.servicing.yaml" -f "$chart/values.documents.yaml" \
  --set assessment.enabled=true --set servicing.enabled=true --set documents.enabled=true \
  --set-string assessment.generation=g0123456789abcdef --set-string assessment.clusterId=abcdefghijklmnopqrstuA \
  --set-string "images.assessment=example.invalid/assessment@sha256:$digest" \
  --set-string "images.assessment-kafka=example.invalid/kafka@sha256:$digest" \
  --set-string "images.servicing=example.invalid/servicing@sha256:$digest" --set-string "servicing.jarSha256=$digest" \
  --set-string "images.documents=example.invalid/documents@sha256:$digest" --set-string "documents.jarSha256=$digest" \
  --show-only templates/apps.yaml)
for credit_setting in 'name: assessment-credit-transport' 'mountPath: /run/catalog-tls' 'mountPath: /run/documents-tls'; do
  printf '%s\n' "$joint" | grep -F "$credit_setting" >/dev/null
done
