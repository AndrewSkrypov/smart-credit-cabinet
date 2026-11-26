#!/usr/bin/env bash
set -euo pipefail

: "${CI_REGISTRY:?}" "${CI_REGISTRY_IMAGE:?}" "${CI_REGISTRY_USER:?}" "${CI_REGISTRY_PASSWORD:?}" "${CI_COMMIT_SHA:?}" "${CI_JOB_ID:?}"
[[ "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ ]]
sha256sum -c ci-artifacts/backend.sha256
sha256sum -c ci-artifacts/frontend.sha256

export DOCKER_CONFIG
DOCKER_CONFIG=$(mktemp -d)
trap 'rm -rf -- "$DOCKER_CONFIG"' EXIT
printf '%s' "$CI_REGISTRY_PASSWORD" | docker login --username "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY" >/dev/null
tag="$CI_COMMIT_SHA-$CI_JOB_ID"
manifest="$DOCKER_CONFIG/images.tsv"

components=(identity credit engagement external-sim frontend postgres swagger ssh-bridge)
if [[ "${CABINET_CI_CLAMAV_SEED_FROM_FILES:-0}" != 0 || -n "${CABINET_CI_CLAMAV_SEED_IMAGE:-}" ]]; then
    seeded_clamav=$(bash ci/gitlab/quality/clamav-runtime.sh release)
    components+=(clamav)
fi
for service in "${components[@]}"; do
    image="$CI_REGISTRY_IMAGE/$service:$tag"
    args=(--platform linux/amd64 --label "org.opencontainers.image.revision=$CI_COMMIT_SHA" --tag "$image")
    case "$service" in
        frontend) args+=(-f frontend/Dockerfile --build-arg DIST_SOURCE=prebuilt --build-arg "SOURCE_REVISION=$CI_COMMIT_SHA");;
        postgres|swagger) args+=(-f "docker/$service/Dockerfile");;
        ssh-bridge) args+=(-f ci/gitlab/ssh-bridge.Dockerfile);;
        clamav) docker tag "$seeded_clamav" "$image";;
        *) args+=(-f docker/service/Dockerfile --build-arg "SERVICE=$service" --build-arg JAR_SOURCE=prebuilt --build-arg "SOURCE_REVISION=$CI_COMMIT_SHA");;
    esac
    if [[ "$service" != clamav ]]; then docker build "${args[@]}" .; fi
    python3 security/scan.py --image "$image" --report-name "release-$service"
    docker push "$image"
    digest=$(docker buildx imagetools inspect "$image" --format '{{json .Manifest}}' | python3 -c 'import json, sys; print(json.load(sys.stdin)["digest"])')
    [[ "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]
    printf '%s\t%s\n' "$service" "$CI_REGISTRY_IMAGE/$service@$digest" >> "$manifest"
done

python3 - "$CI_COMMIT_SHA" "$manifest" <<'PY'
import json
import sys
from pathlib import Path

revision, manifest_path = sys.argv[1:]
images = dict(line.split("\t", 1) for line in Path(manifest_path).read_text().splitlines())
Path("release.json").write_text(json.dumps({"revision": revision, "images": images}, indent=2) + "\n")
PY
