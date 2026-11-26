#!/usr/bin/env bash
# Source this in the job shell to hold a per-host port lease through the entire script.
# The cabinet-ci runner user owns these leases on a single isolated Linux worker.
port_lock_dir="${XDG_RUNTIME_DIR:-/tmp}/cabinet-ci-port-locks-$(id -u)"
mkdir -m 700 -p "$port_lock_dir"
allocated=
for ((slot=0; slot<128; slot++)); do
    base=$((20000 + 16 * slot))
    exec {port_lock_fd}>"$port_lock_dir/$slot"
    if flock -n "$port_lock_fd" && python3 - "$base" <<'PY'
import socket
import sys

base = int(sys.argv[1])
sockets = []
try:
    for port in range(base, base + 9):
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sockets.append(probe)
        probe.bind(("127.0.0.1", port))
finally:
    for probe in sockets:
        probe.close()
PY
    then
        allocated=1
        break
    fi
    exec {port_lock_fd}>&-
done
if [[ -z "$allocated" ]]; then
    echo "No available port lease for this CI job" >&2
    return 1
fi

export GATEWAY_PORT="$base" PROMETHEUS_PORT="$((base + 1))"
export GRAFANA_PORT="$((base + 2))" MAILPIT_PORT="$((base + 3))"
export CI_HTTP_PORT="$((base + 4))" CI_HTTPS_PORT="$((base + 6))"
export CABINET_URL="http://127.0.0.1:$GATEWAY_PORT" MAILPIT_URL="http://127.0.0.1:$MAILPIT_PORT"
export PUBLIC_CABINET_URL="$CABINET_URL"
