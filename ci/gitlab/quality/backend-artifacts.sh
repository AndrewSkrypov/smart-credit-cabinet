#!/usr/bin/env bash
set -euo pipefail

services=(assessment catalog-grpc credit engagement external-sim identity servicing)
jars=()
for service in "${services[@]}"; do
    jar="services/$service/build/libs/app.jar"
    if [[ ! -s "$jar" ]]; then
        printf 'Missing tested backend artifact %s\n' "$jar" >&2
        exit 1
    fi
    jars+=("$jar")
done

shopt -s nullglob
actual=(services/*/build/libs/app.jar)
if (( ${#actual[@]} != ${#jars[@]} )); then
    printf 'Unexpected backend artifact set: %s\n' "${actual[*]}" >&2
    exit 1
fi

mkdir -p ci-artifacts
sha256sum "${jars[@]}" > ci-artifacts/backend.sha256
