#!/usr/bin/env bash

mcp_base_image() {
    local base
    base=$(sed -n 's/^ARG NODE_IMAGE=//p' integrations/cabinet-mcp/Dockerfile)
    if [[ ! "$base" =~ ^node:[0-9]+\.[0-9]+\.[0-9]+-bookworm-slim@sha256:[a-f0-9]{64}$ ]]; then
        echo 'The MCP Dockerfile must identify an immutable Node.js base image' >&2
        return 1
    fi
    printf '%s\n' "$base"
}

mcp_image_provenance() {
    local image="$1" revision="$2" base="$3" metadata id actual_revision actual_base
    metadata=$(docker image inspect --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}} {{index .Config.Labels "org.opencontainers.image.base.name"}}' "$image")
    read -r id actual_revision actual_base <<< "$metadata"
    if [[ ! "$id" =~ ^sha256:[a-f0-9]{64}$ || "$actual_revision" != "$revision" || "$actual_base" != "$base" ]]; then
        echo 'Built MCP image has unexpected OCI provenance' >&2
        return 1
    fi
    printf '%s\n' "$id"
}

smoke_mcp_image() (
    set -euo pipefail
    local image="$1" base="$2" fixture version token
    version=${base#node:}
    version=${version%%-bookworm-slim@*}
    mkdir -p ci-artifacts
    fixture=$(mktemp -d "$PWD/ci-artifacts/.mcp-smoke.XXXXXX")
    trap 'rm -rf -- "$fixture"' EXIT
    # These disposable files contain only generated test material. The target image runs as node.
    openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=mcp-smoke.invalid' \
        -keyout "$fixture/client.key" -out "$fixture/client.crt" >/dev/null 2>&1
    chmod 0755 "$fixture"
    chmod 0444 "$fixture/client.key" "$fixture/client.crt"
    token=$(openssl rand -hex 32)
    docker run --rm --network none -i \
        --mount "type=bind,src=$fixture,dst=/mcp-smoke,readonly" \
        -e "EXPECTED_NODE_VERSION=v$version" \
        -e MCP_ISSUER=https://mcp-smoke.invalid \
        -e MCP_RESOURCE=https://mcp-smoke.invalid/api/mcp \
        -e MCP_CREDIT_ORIGIN=https://credit:8443 \
        -e MCP_IDENTITY_ORIGIN=https://identity-mcp:8443 \
        -e "CABINET_MCP_IDENTITY_SERVICE_TOKEN=$token" \
        -e MCP_CREDIT_CA_FILE=/mcp-smoke/client.crt -e MCP_IDENTITY_CA_FILE=/mcp-smoke/client.crt \
        -e MCP_CREDIT_CERT_FILE=/mcp-smoke/client.crt -e MCP_CREDIT_KEY_FILE=/mcp-smoke/client.key \
        --entrypoint /bin/sh "$image" -s <<'SH'
set -eu
test "$(id -u)" -ne 0
test "$(node --version)" = "$EXPECTED_NODE_VERSION"
node dist/http-main.js &
server=$!
trap 'kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true' EXIT
node --input-type=module <<'JS'
import assert from "node:assert/strict";
import { setTimeout as sleep } from "node:timers/promises";

const base = "http://127.0.0.1:8080";
const origin = "https://mcp-smoke.invalid";
const metadataPath = "/.well-known/oauth-protected-resource/api/mcp";
async function request(path, init) {
    return fetch(base + path, { signal: AbortSignal.timeout(1500), ...init });
}

let health;
for (let attempt = 0; attempt < 50; attempt++) {
    health = await request("/healthz").catch(() => null);
    if (health?.ok) break;
    await sleep(100);
}
assert.equal(health?.status, 200);
assert.deepEqual(await health.json(), { status: "ok" });
const discovery = await request(metadataPath);
assert.equal(discovery.status, 200);
assert.deepEqual(await discovery.json(), {
    authorization_servers: [origin],
    bearer_methods_supported: ["header"],
    resource: origin + "/api/mcp",
    scopes_supported: ["cabinet:mcp:read"],
});
const unauthorized = await request("/api/mcp", { method: "POST" });
assert.equal(unauthorized.status, 401);
assert.ok(unauthorized.headers.get("www-authenticate")?.includes(origin + metadataPath));
assert.equal((await request("/internal/mcp/authorize")).status, 404);
JS
SH
)
