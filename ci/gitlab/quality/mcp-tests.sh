#!/usr/bin/env bash
set -euo pipefail
umask 077
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || { echo 'MCP tests require Linux x86_64' >&2; exit 2; }
: "${RUNNER_TEMP:?}" "${CI_COMMIT_SHA:?}" "${CI_JOB_ID:?}"
[[ "$RUNNER_TEMP" = /* && -d "$RUNNER_TEMP" && "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ && "$CI_JOB_ID" =~ ^[0-9]+$ ]]
expected=4f2b175a6d53a5c617a80dba2676238a0688cef28c6da4eba35c7d844e66fcfb
reports="$PWD/ci-artifacts/mcp-tests/$CI_JOB_ID"
[[ ! -e "$reports" ]]; mkdir -p "$reports"
work=$(mktemp -d "$RUNNER_TEMP/cabinet-mcp-tests.XXXXXXXX")
phase=bootstrap version=unavailable actual=unavailable
finish() {
    local status=$?; trap - EXIT; set +e
    python3 -B - "$reports" "$CI_COMMIT_SHA" "$version" "$actual" "$phase" "$status" <<'PY'
import json, sys, xml.etree.ElementTree as E
from pathlib import Path
directory, source, version, digest, phase, status = sys.argv[1:]; directory = Path(directory); status = int(status)
gates = {name: int((directory / (name + '.exit')).read_text()) if (directory / (name + '.exit')).exists() else -1 for name in ('install', 'sdk', 'http', 'stdio')}
suite = E.Element('testsuite', name='cabinet-mcp')
for name, code in gates.items():
    case = E.SubElement(suite, 'testcase', classname='cabinet-mcp', name=name)
    if code < 0: E.SubElement(case, 'skipped')
    elif code: E.SubElement(case, 'failure', message='Command exited with ' + str(code))
if status and not any(code > 0 for code in gates.values()): E.SubElement(E.SubElement(suite, 'testcase', name=phase), 'failure', message='MCP setup failed')
suite.set('tests', str(len(suite))); suite.set('failures', str(len(suite.findall('.//failure')))); suite.set('skipped', str(len(suite.findall('.//skipped'))))
E.ElementTree(suite).write(directory / 'junit.xml', encoding='utf-8', xml_declaration=True)
calls = len(json.loads((directory / 'stdio.log').read_text())) if gates['stdio'] == 0 else None
if calls is not None: assert calls == 3
(directory / 'receipt.json').write_text(json.dumps({'source': source, 'node': version, 'archive_sha256': digest, 'exit_code': status, 'gates': gates, 'stdio_calls': calls}, indent=2) + '\n')
PY
    local report_status=$?; rm -rf -- "$work"
    (( report_status == 0 )) || status=1
    exit "$status"
}
trap finish EXIT
run() {
    phase=$1; shift
    "$@" >"$reports/$phase.log" 2>&1 && rc=0 || rc=$?
    printf '%s\n' "$rc" >"$reports/$phase.exit"; cat "$reports/$phase.log"; return "$rc"
}
archive="$work/node.tar.xz"
if [[ -n ${CABINET_MCP_NODE_ARCHIVE:-} ]]; then
    cp -- "$CABINET_MCP_NODE_ARCHIVE" "$archive"
else
    curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' --tlsv1.2 \
        --connect-timeout 15 --max-time 180 --output "$archive" https://nodejs.org/dist/v24.22.3/node-v24.22.3-linux-x64.tar.xz
fi
actual=$(sha256sum "$archive"); actual=${actual%% *}; [[ "$actual" = "$expected" ]]
mkdir -p "$work/node" "$work/tmp"
tar -xJf "$archive" -C "$work/node" --strip-components=1
version=$("$work/node/bin/node" --version); [[ "$version" = v24.22.3 ]]
git archive "$CI_COMMIT_SHA" integrations/cabinet-mcp ci/gitlab/quality/mcp-image.sh | tar -xf - -C "$work"
(
    export PATH="$work/node/bin:$PATH" TMPDIR="$work/tmp" npm_config_cache="$work/npm-cache"
    unset NODE_OPTIONS NODE_PATH
    cd "$work/integrations/cabinet-mcp"
    run install npm ci --ignore-scripts --no-audit --no-fund
    run sdk npm test
    run http npm run test:credit-http
    run stdio npm run --silent verify
)
