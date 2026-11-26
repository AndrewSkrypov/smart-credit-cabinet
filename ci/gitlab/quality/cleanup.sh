#!/usr/bin/env bash
set -u
id="${CI_JOB_ID:?}"
[[ "$id" =~ ^[0-9]+$ ]] || exit 2
source ci/gitlab/quality/runner.sh || exit 2

e2e="cabinet-e2e-$id"
demo="cabinet-demo-$id"
# after_script runs in a new shell. Discover only this job's Compose resources by project label.
projects=("$e2e" "$demo")
for project in "${projects[@]}"; do
    if [[ "$project" == "$e2e" && "$CI_JOB_NAME" =~ ^(e2e|reliability) ]]; then
        mkdir -p e2e/test-results/compose
        docker ps -a --filter "label=com.docker.compose.project=$project" \
            --format '{{.Names}} {{.Image}} {{.Status}}' > e2e/test-results/compose/status.txt
    fi
    mapfile -t containers < <(docker ps -aq --filter "label=com.docker.compose.project=$project")
    if ((${#containers[@]})); then docker rm -fv "${containers[@]}" || true; fi
    mapfile -t networks < <(docker network ls -q --filter "label=com.docker.compose.project=$project")
    if ((${#networks[@]})); then docker network rm "${networks[@]}" || true; fi
    mapfile -t volumes < <(docker volume ls -q --filter "label=com.docker.compose.project=$project")
    if ((${#volumes[@]})); then docker volume rm "${volumes[@]}" || true; fi
done

if [[ "$CI_JOB_NAME" = backend || "$CI_JOB_NAME" = maintenance ]]; then
    for dependency in postgres clamav seaweedfs valkey; do
        docker rm -fv "cabinet-ci-$id-$dependency" >/dev/null 2>&1 || true
    done
    docker image rm "cabinet-ci-$id-postgres:test" >/dev/null 2>&1 || true
fi

docker image rm "cabinet-ci-$id-scan:test" >/dev/null 2>&1 || true
docker volume rm "cabinet-trivy-$id" >/dev/null 2>&1 || true
for service in identity credit engagement external-sim frontend; do
    docker image rm "$e2e-$service:latest" "$demo-$service:latest" >/dev/null 2>&1 || true
done
