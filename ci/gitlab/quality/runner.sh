#!/usr/bin/env bash
# The runner config provides this socket. A shared host's default socket is never a fallback.
: "${CABINET_CI_DOCKER_HOST:?Set CABINET_CI_DOCKER_HOST in the cabinet-ci runner environment}"
case "$CABINET_CI_DOCKER_HOST" in
    unix:///*) ;;
    *) echo "The quality runner needs a local, isolated Docker daemon" >&2; return 2 ;;
esac
unset DOCKER_CONTEXT
export DOCKER_HOST="$CABINET_CI_DOCKER_HOST"
docker info --format '{{range .Labels}}{{println .}}{{end}}' | grep -Fxq 'cabinet-ci=true'
