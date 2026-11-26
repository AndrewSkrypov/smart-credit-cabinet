#!/usr/bin/env bash
set -euo pipefail

: "${CI_REGISTRY:?}" "${CI_REGISTRY_IMAGE:?}" "${CI_REGISTRY_USER:?}" "${CI_REGISTRY_PASSWORD:?}" "${CI_COMMIT_SHA:?}" "${CI_JOB_ID:?}"
[[ "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ ]]
sha256sum -c ci-artifacts/backend.sha256
sha256sum -c ci-artifacts/frontend.sha256

analytics_jar_sha256=
kafka_upstream=
if [[ "${CABINET_ALPHA_PILOT:-0}" == 1 ]]; then
    artifacts=ci-artifacts/alpha-analytics
    [[ "$(git rev-parse HEAD)" == "$CI_COMMIT_SHA" ]]
    [[ "$(cat "$artifacts/source-revision.txt")" == "$CI_COMMIT_SHA" ]]
    read -r analytics_jar_sha256 filename extra < "$artifacts/app.jar.sha256"
    [[ "$analytics_jar_sha256" =~ ^[a-f0-9]{64}$ && "$filename" == app.jar && -z "$extra" ]]
    printf '%s  %s\n' "$analytics_jar_sha256" "$artifacts/app.jar" | sha256sum -c -
    install -D -m 0644 "$artifacts/app.jar" services/analytics/build/libs/app.jar
    kafka_upstream=$(cat ci/alpha/kafka-image.txt)
    if [[ ! "$kafka_upstream" =~ ^apache/kafka:[0-9.]+@sha256:[a-f0-9]{64}$ ]]; then
        echo "ci/alpha/kafka-image.txt must identify a pinned Apache Kafka release" >&2
        exit 1
    fi
fi

export DOCKER_CONFIG
DOCKER_CONFIG=$(mktemp -d)
trap 'rm -rf -- "$DOCKER_CONFIG"' EXIT
printf '%s' "$CI_REGISTRY_PASSWORD" | docker login --username "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY" >/dev/null
tag="$CI_COMMIT_SHA-$CI_JOB_ID"
manifest="$DOCKER_CONFIG/images.tsv"

components=(identity credit engagement external-sim frontend postgres swagger ssh-bridge)
if [[ -n "$analytics_jar_sha256" ]]; then
    components=(kafka "${components[@]}" analytics)
fi
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
        kafka) args+=(-f ci/alpha/Kafka.Dockerfile --build-arg "UPSTREAM_KAFKA_IMAGE=$kafka_upstream");;
        ssh-bridge) args+=(-f ci/gitlab/ssh-bridge.Dockerfile);;
        clamav) docker tag "$seeded_clamav" "$image";;
        *) args+=(-f docker/service/Dockerfile --build-arg "SERVICE=$service" --build-arg JAR_SOURCE=prebuilt --build-arg "SOURCE_REVISION=$CI_COMMIT_SHA");;
    esac
    if [[ "$service" == analytics ]]; then
        args+=(--label "org.opencontainers.image.jar.sha256=$analytics_jar_sha256")
    fi
    if [[ "$service" != clamav ]]; then docker build "${args[@]}" .; fi
    scan_image=$image
    report_name="release-$service"
    if [[ "$service" == kafka ]]; then
        metadata=$(docker image inspect --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}} {{index .Config.Labels "org.opencontainers.image.base.name"}}' "$image")
        read -r scan_image image_revision image_upstream <<< "$metadata"
        if [[ ! "$scan_image" =~ ^sha256:[a-f0-9]{64}$ || "$image_revision" != "$CI_COMMIT_SHA" || "$image_upstream" != "$kafka_upstream" ]]; then
            echo "Built Kafka image has unexpected OCI provenance" >&2
            exit 1
        fi
        report_name=release-alpha-kafka
    fi
    python3 security/scan.py --image "$scan_image" --report-name "$report_name"
    docker push "$image"
    digest=$(docker buildx imagetools inspect "$image" --format '{{json .Manifest}}' | python3 -c 'import json, sys; print(json.load(sys.stdin)["digest"])')
    [[ "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]
    registry_image="$CI_REGISTRY_IMAGE/$service@$digest"
    if [[ "$service" == kafka ]]; then
        docker pull --platform linux/amd64 "$registry_image"
        if [[ "$(docker image inspect --format '{{.Id}}' "$registry_image")" != "$scan_image" ]]; then
            echo "Published Kafka digest does not resolve to the scanned image" >&2
            exit 1
        fi
        python3 - "$registry_image" "$scan_image" "$CI_COMMIT_SHA" "$kafka_upstream" <<'PY'
import json
import sys
from pathlib import Path

registry_ref, image_id, revision, upstream_ref = sys.argv[1:]
report = {"registry_ref": registry_ref, "image_id": image_id, "revision": revision, "upstream_ref": upstream_ref}
Path("security/reports/release-alpha-kafka/published-image.json").write_text(json.dumps(report, indent=2) + "\n")
PY
    fi
    printf '%s\t%s\n' "$service" "$registry_image" >> "$manifest"
done

python3 - "$CI_COMMIT_SHA" "$manifest" "$analytics_jar_sha256" <<'PY'
import json
import sys
from pathlib import Path

revision, manifest_path, analytics_jar_sha256 = sys.argv[1:]
images = dict(line.split("\t", 1) for line in Path(manifest_path).read_text().splitlines())
alpha_image = images.pop("analytics", None)
kafka_image = images.pop("kafka", None)
release = {"revision": revision, "images": images}
if alpha_image:
    release["alpha_dev"] = {"image": alpha_image, "jar_sha256": analytics_jar_sha256, "kafka_image": kafka_image}
Path("release.json").write_text(json.dumps(release, indent=2) + "\n")
PY

python3 ci/gitlab/release-manifest.py release.json "$CI_COMMIT_SHA" "$CI_REGISTRY_IMAGE"
