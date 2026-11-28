#!/usr/bin/env bash
set -euo pipefail
source ci/gitlab/quality/mcp-image.sh

: "${CI_REGISTRY:?}" "${CI_REGISTRY_IMAGE:?}" "${CI_REGISTRY_USER:?}" "${CI_REGISTRY_PASSWORD:?}" "${CI_COMMIT_SHA:?}" "${CI_JOB_ID:?}"
[[ "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ ]]
sha256sum -c ci-artifacts/backend.sha256
sha256sum -c ci-artifacts/frontend.sha256

[[ "$(git rev-parse HEAD)" == "$CI_COMMIT_SHA" ]]
declare -A modes=(
    [catalog-grpc]="${CABINET_CATALOG_DEV_MODE:-disabled}"
    [assessment]="${CABINET_ASSESSMENT_DEV_MODE:-disabled}"
    [servicing]="${CABINET_SERVICING_DEV_MODE:-disabled}"
    [documents]="${CABINET_DOCUMENTS_DEV_MODE:-disabled}"
)
declare -A jar_hashes
backend_services=(assessment catalog-grpc credit documents engagement external-sim identity servicing)
[[ $(wc -l <ci-artifacts/backend.sha256) == "${#backend_services[@]}" ]]
for service in "${backend_services[@]}"; do
    jar="services/$service/build/libs/app.jar"
    jar_sha256=$(awk -v path="$jar" '$2 == path { print $1 }' ci-artifacts/backend.sha256)
    [[ -s "$jar" && "$jar_sha256" =~ ^[a-f0-9]{64}$ ]]
    if [[ -v modes[$service] ]]; then
        jar_hashes[$service]=$jar_sha256
        case "${modes[$service]}" in
            disabled|local|remote) ;;
            *) echo "Unexpected Dev mode for $service" >&2; exit 1 ;;
        esac
    fi
done

catalog_chart=helm/cabinet/files/catalog
cmp deploy/catalog-sql/roles.sql "$catalog_chart/roles.sql"
cmp deploy/catalog-sql/fence-check.sql "$catalog_chart/fence-check.sql"
cmp services/catalog-grpc/src/main/resources/db/catalog/V1__product_history.sql "$catalog_chart/V1__product_history.sql"
for sql in grants.sql credit-fence.sql; do
    cmp "services/catalog-grpc/cutover/$sql" "$catalog_chart/$sql"
done

analytics_jar_sha256=
kafka_upstream=$(cat ci/alpha/kafka-image.txt)
if [[ ! "$kafka_upstream" =~ ^apache/kafka:[0-9.]+@sha256:[a-f0-9]{64}$ ]]; then
    echo "ci/alpha/kafka-image.txt must identify a pinned Apache Kafka release" >&2
    exit 1
fi
if [[ "${CABINET_ALPHA_PILOT:-0}" == 1 ]]; then
    artifacts=ci-artifacts/alpha-analytics
    [[ "$(git rev-parse HEAD)" == "$CI_COMMIT_SHA" ]]
    [[ "$(cat "$artifacts/source-revision.txt")" == "$CI_COMMIT_SHA" ]]
    read -r analytics_jar_sha256 filename extra < "$artifacts/app.jar.sha256"
    [[ "$analytics_jar_sha256" =~ ^[a-f0-9]{64}$ && "$filename" == app.jar && -z "$extra" ]]
    printf '%s  %s\n' "$analytics_jar_sha256" "$artifacts/app.jar" | sha256sum -c -
    install -D -m 0644 "$artifacts/app.jar" services/analytics/build/libs/app.jar

fi

export DOCKER_CONFIG
DOCKER_CONFIG=$(mktemp -d)
trap 'rm -rf -- "$DOCKER_CONFIG"' EXIT
printf '%s' "$CI_REGISTRY_PASSWORD" | docker login --username "$CI_REGISTRY_USER" --password-stdin "$CI_REGISTRY" >/dev/null
tag="$CI_COMMIT_SHA-$CI_JOB_ID"
manifest="$DOCKER_CONFIG/images.tsv"
provenance="$DOCKER_CONFIG/runtime.tsv"
mcp_base=$(mcp_base_image)
mcp_config_digest=

components=(kafka assessment catalog-grpc credit documents engagement external-sim identity servicing frontend postgres swagger ssh-bridge mcp)
if [[ -n "$analytics_jar_sha256" ]]; then
    components+=(analytics)
fi
if [[ "${CABINET_CI_CLAMAV_SEED_FROM_FILES:-0}" != 0 || -n "${CABINET_CI_CLAMAV_SEED_IMAGE:-}" ]]; then
    seeded_clamav=$(bash ci/gitlab/quality/clamav-runtime.sh release)
    components+=(clamav)
fi
for service in "${components[@]}"; do
    image="$CI_REGISTRY_IMAGE/$service:$tag"
    args=(--platform linux/amd64 --label "org.opencontainers.image.revision=$CI_COMMIT_SHA" --tag "$image")
    context=.
    case "$service" in
        mcp) context=integrations/cabinet-mcp;;
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
    if [[ -v jar_hashes[$service] ]]; then
        args+=(--label "org.opencontainers.image.jar.sha256=${jar_hashes[$service]}")
    fi
    if [[ "$service" != clamav ]]; then docker build "${args[@]}" "$context"; fi
    scan_image=$image
    report_name="release-$service"
    if [[ "$service" == mcp ]]; then
        mcp_config_digest=$(mcp_image_provenance "$image" "$CI_COMMIT_SHA" "$mcp_base")
        scan_image=$mcp_config_digest
        smoke_mcp_image "$scan_image" "$mcp_base"
    elif [[ "$service" == kafka ]]; then
        metadata=$(docker image inspect --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}} {{index .Config.Labels "org.opencontainers.image.base.name"}}' "$image")
        read -r scan_image image_revision image_upstream <<< "$metadata"
        if [[ ! "$scan_image" =~ ^sha256:[a-f0-9]{64}$ || "$image_revision" != "$CI_COMMIT_SHA" || "$image_upstream" != "$kafka_upstream" ]]; then
            echo "Built Kafka image has unexpected OCI provenance" >&2
            exit 1
        fi
        report_name=release-alpha-kafka
    elif [[ -v jar_hashes[$service] ]]; then
        metadata=$(docker image inspect --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}} {{index .Config.Labels "org.opencontainers.image.jar.sha256"}}' "$image")
        read -r scan_image image_revision image_jar_sha256 <<< "$metadata"
        if [[ ! "$scan_image" =~ ^sha256:[a-f0-9]{64}$ || "$image_revision" != "$CI_COMMIT_SHA" || "$image_jar_sha256" != "${jar_hashes[$service]}" ]]; then
            echo "Built $service image has unexpected OCI provenance" >&2
            exit 1
        fi
    fi
    python3 security/scan.py --image "$scan_image" --report-name "$report_name"
    docker push "$image"
    digest=$(docker buildx imagetools inspect "$image" --format '{{json .Manifest}}' | python3 -c 'import json, sys; print(json.load(sys.stdin)["digest"])')
    [[ "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]
    registry_image="$CI_REGISTRY_IMAGE/$service@$digest"
    if [[ "$service" == mcp || "$service" == kafka || -v jar_hashes[$service] ]]; then
        docker pull --platform linux/amd64 "$registry_image"
        if [[ "$(docker image inspect --format '{{.Id}}' "$registry_image")" != "$scan_image" ]]; then
            echo "Published $service digest does not resolve to the scanned image" >&2
            exit 1
        fi
    fi
    if [[ "$service" == kafka || -v jar_hashes[$service] ]]; then
        printf '%s\t%s\t%s\t%s\n' "$service" "$scan_image" "${jar_hashes[$service]-}" "${modes[$service]-}" >> "$provenance"
        python3 - "$registry_image" "$scan_image" "$CI_COMMIT_SHA" "$report_name" "${jar_hashes[$service]-}" "$kafka_upstream" <<'PY_REPORT'
import json
import sys
from pathlib import Path

registry_ref, image_id, revision, report_name, jar_sha256, kafka_upstream = sys.argv[1:]
report = {"registry_ref": registry_ref, "image_id": image_id, "revision": revision}
if jar_sha256:
    report["jar_sha256"] = jar_sha256
else:
    report["upstream_ref"] = kafka_upstream
Path("security/reports", report_name, "published-image.json").write_text(json.dumps(report, indent=2) + "\n")
PY_REPORT
    elif [[ "$service" == mcp ]]; then
        python3 - "$registry_image" "$scan_image" "$CI_COMMIT_SHA" "$mcp_base" <<'PY_MCP_REPORT'
import json
import sys
from pathlib import Path

registry_ref, image_id, revision, base_image = sys.argv[1:]
report = {"registry_ref": registry_ref, "image_id": image_id, "revision": revision, "base_image": base_image}
Path("security/reports/release-mcp/published-image.json").write_text(json.dumps(report, indent=2) + "\n")
PY_MCP_REPORT
    fi
    printf '%s\t%s\n' "$service" "$registry_image" >> "$manifest"
done

python3 - "$CI_COMMIT_SHA" "$manifest" "$provenance" "$analytics_jar_sha256" "$mcp_config_digest" "$mcp_base" <<'PY_MANIFEST'
import json
import sys
from pathlib import Path

revision, manifest_path, provenance_path, analytics_jar_sha256, mcp_digest, mcp_base = sys.argv[1:]
images = dict(line.split("\t", 1) for line in Path(manifest_path).read_text().splitlines())
alpha_image = images.pop("analytics", None)
kafka_image = images.pop("kafka")
release = {"revision": revision, "images": images, "runtime_oci": {},
           "mcp": {"config_digest": mcp_digest, "base_image": mcp_base}}
for line in Path(provenance_path).read_text().splitlines():
    component, config_digest, jar_sha256, mode = line.split("\t")
    release["runtime_oci"][component] = config_digest
    if component == "kafka":
        continue
    key = "catalog" if component == "catalog-grpc" else component
    descriptor = {"jar_sha256": jar_sha256, "dev_mode": mode}
    if key == "catalog":
        descriptor["config_digest"] = config_digest
    if key == "assessment":
        descriptor["kafka_image"] = kafka_image
    release[key] = descriptor
if alpha_image:
    release["alpha_dev"] = {"image": alpha_image, "jar_sha256": analytics_jar_sha256, "kafka_image": kafka_image}
Path("release.json").write_text(json.dumps(release, indent=2) + "\n")
PY_MANIFEST

python3 ci/gitlab/release-manifest.py release.json "$CI_COMMIT_SHA" "$CI_REGISTRY_IMAGE" --require-runtimes --require-mcp
