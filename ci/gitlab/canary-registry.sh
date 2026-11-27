#!/usr/bin/env bash
set -euo pipefail
: "${CI_REGISTRY:?}" "${CI_REGISTRY_IMAGE:?}" "${CI_REGISTRY_USER:?}" "${CI_REGISTRY_PASSWORD:?}" "${CI_COMMIT_SHA:?}" "${CI_PIPELINE_ID:?}"
export DOCKER_CONFIG
DOCKER_CONFIG=$(mktemp -d)
trap 'rm -rf -- "$DOCKER_CONFIG"' EXIT
repository="$CI_REGISTRY_IMAGE/runner-canary"

login() {
    printf '%s' "$CI_REGISTRY_PASSWORD" | docker login --username "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY" >/dev/null
}

case "${1:?push or pull}" in
    push)
        : "${CABINET_CI_DOCKER_HOST:?}"
        [[ "$CABINET_CI_DOCKER_HOST" == unix:///* ]]
        export DOCKER_HOST="$CABINET_CI_DOCKER_HOST"
        unset DOCKER_CONTEXT
        docker info --format '{{range .Labels}}{{println .}}{{end}}' | grep -Fxq 'cabinet-ci=true'
        login
        mkdir "$DOCKER_CONFIG/context"
        printf '%s\n' "$CI_COMMIT_SHA" > "$DOCKER_CONFIG/context/revision"
        tag="$repository:pipeline-$CI_PIPELINE_ID"
        docker build --build-arg "GIT_REVISION=$CI_COMMIT_SHA" -t "$tag" -f - "$DOCKER_CONFIG/context" <<'DOCKER'
FROM scratch
ARG GIT_REVISION
LABEL org.opencontainers.image.revision=$GIT_REVISION
COPY revision /revision
DOCKER
        docker push "$tag"
        digest=$(docker buildx imagetools inspect "$tag" --format '{{json .Manifest}}' | python3 -c 'import json, sys; print(json.load(sys.stdin)["digest"])')
        [[ "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]
        printf '%s\n' "$repository@$digest" > runner-canary-image.txt
        docker image rm "$tag" >/dev/null
        ;;
    pull)
        ref=$(cat runner-canary-image.txt)
        digest=${ref#"$repository@"}
        [[ "$ref" == "$repository@$digest" && "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]
        login
        docker pull "$ref"
        revision=$(docker image inspect "$ref" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')
        [[ "$revision" == "$CI_COMMIT_SHA" ]]
        printf 'OCI revision verified for %s\n' "$ref"
        ;;
    *)
        exit 2
        ;;
esac
