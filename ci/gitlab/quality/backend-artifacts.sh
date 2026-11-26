#!/usr/bin/env bash
set -euo pipefail
services=(credit documents engagement external-sim identity)
jars=()
for service in "${services[@]}"; do
    jar="services/$service/build/libs/app.jar"
    test -s "$jar"
    jars+=("$jar")
done
shopt -s nullglob
actual=(services/*/build/libs/app.jar)
if (( ${#actual[@]} != ${#jars[@]} )); then
    printf 'Unexpected backend artifact inventory\n' >&2
    exit 1
fi
mkdir -p ci-artifacts
sha256sum "${jars[@]}" > ci-artifacts/backend.sha256
