#!/usr/bin/env bash
# Install root-owned and use as an authorized_keys forced command on pump-final.
set -euo pipefail
umask 077

if [[ "${SSH_ORIGINAL_COMMAND:-}" =~ ^(stage|deploy)\ ([a-f0-9]{40})$ ]]; then
    operation="${BASH_REMATCH[1]}"
    revision="${BASH_REMATCH[2]}"
else
    echo "Expected stage or deploy with a full Git commit SHA" >&2
    exit 2
fi
releases=/srv/cabinet-dev/releases
state=/var/lib/cabinet-dev
release="$releases/$revision"

case "$operation" in
    stage)
        stage=$(mktemp -d "$releases/.stage.XXXXXXXX")
        trap 'rm -rf -- "$stage"' EXIT
        (umask 022; tar -xf - -C "$stage" --no-same-owner --no-same-permissions)
        test -f "$stage/deploy/dev-release.py"
        if [[ ! -d "$release" ]]; then
            mv -T -- "$stage" "$release"
        fi
        ;;
    deploy)
        manifest=$(mktemp "$release/.manifest.XXXXXXXX")
        trap 'rm -f -- "$manifest"' EXIT
        cat > "$manifest"
        python3 - "$manifest" "$revision" <<'PY'
import json
import sys

with open(sys.argv[1]) as manifest:
    if json.load(manifest).get("revision") != sys.argv[2]:
        sys.exit("release revision does not match the requested checkout")
PY
        cd "$release"
        if ! ln "$manifest" release.json 2>/dev/null; then
            cmp -s "$manifest" release.json || { echo "Stored release manifest differs" >&2; exit 2; }
        fi
        python3 deploy/dev-release.py --state-dir "$state" --release-manifest "$release/release.json" --host-memory-gib 31
        ;;
esac
