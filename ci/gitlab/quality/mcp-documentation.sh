#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'MCP documentation requires Linux x86_64' >&2; exit 2; }
: "${RUNNER_TEMP:?}"
[[ "$RUNNER_TEMP" = /* && -d "$RUNNER_TEMP" && -f integrations/cabinet-mcp/package-lock.json ]]

# Install in the runner checkout: a Docker daemon may not share its bind-mount paths or UID.
expected=4f2b175a6d53a5c617a80dba2676238a0688cef28c6da4eba35c7d844e66fcfb
work=$(mktemp -d "$RUNNER_TEMP/cabinet-mcp-documentation.XXXXXXXX")
trap 'rm -rf -- "$work"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
archive="$work/node.tar.xz"
if [[ -n ${CABINET_MCP_NODE_ARCHIVE:-} ]]; then
    cp -- "$CABINET_MCP_NODE_ARCHIVE" "$archive"
else
    curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' --tlsv1.2 \
        --connect-timeout 15 --max-time 180 --output "$archive" https://nodejs.org/dist/v24.22.3/node-v24.22.3-linux-x64.tar.xz
fi
actual=$(sha256sum "$archive"); actual=${actual%% *}
[[ "$actual" = "$expected" ]] || { echo 'MCP Node archive SHA256 mismatch' >&2; exit 2; }
mkdir -p "$work/node" "$work/tmp"
tar -xJf "$archive" -C "$work/node" --strip-components=1
unset NODE_OPTIONS NODE_PATH
version=$("$work/node/bin/node" --version)
[[ "$version" = v24.22.3 ]] || { echo 'Unexpected MCP Node version' >&2; exit 2; }
printf 'MCP documentation Node %s; archive SHA256 %s\n' "$version" "$actual"
export PATH="$work/node/bin:$PATH" TMPDIR="$work/tmp" npm_config_cache="$work/npm-cache"
"$work/node/bin/node" "$work/node/lib/node_modules/npm/bin/npm-cli.js" ci \
    --engine-strict --ignore-scripts --no-audit --no-fund --prefix integrations/cabinet-mcp
