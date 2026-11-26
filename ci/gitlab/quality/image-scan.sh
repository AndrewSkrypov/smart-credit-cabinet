#!/usr/bin/env bash
set -euo pipefail

image="cabinet-ci-${CI_JOB_ID:?}-scan:test"
cache="cabinet-trivy-${CI_JOB_ID}"
case "${1:?}" in
    app)
        if [[ "$SERVICE" = frontend ]]; then
            docker build -f frontend/Dockerfile --build-arg DIST_SOURCE=prebuilt \
                --build-arg SOURCE_REVISION="$CI_COMMIT_SHA" -t "$image" .
        elif [[ "$SERVICE" = ssh-bridge ]]; then
            docker build -f ci/gitlab/ssh-bridge.Dockerfile -t "$image" .
            docker run --rm "$image" -V
        else
            docker build -f docker/service/Dockerfile --build-arg "SERVICE=$SERVICE" \
                --build-arg JAR_SOURCE=prebuilt --build-arg SOURCE_REVISION="$CI_COMMIT_SHA" -t "$image" .
        fi
        python3 security/scan.py --image "$image" --report-name "image-$SERVICE" --cache-volume "$cache"
        ;;
    infra)
        case "$INFRA_NAME" in
            postgres)
                docker build -f docker/postgres/Dockerfile -t "$image" .
                python3 security/postgres-image.py --image "$image"
                ;;
            swagger)
                docker build -f docker/swagger/Dockerfile -t "$image" .
                docker run --rm --entrypoint apk "$image" info brotli-libs
                ;;
            gateway) image=nginx:1.30.7-alpine@sha256:caf4e1e032a20f9c3c5e28fc7f25ffd768ad8677b4975c54193075997b3a97e3 ;;
            prometheus) image=prom/prometheus:v3.15.1 ;;
            grafana) image=grafana/grafana:13.2.5@sha256:9f7aa1e1e3246aade5792d35a738049f7f32f89cd51d1956cf426a4e86268a84 ;;
            caddy) image=caddy:2.11.8-alpine@sha256:f6228111e5fafa1a5238258d0858e103f10599b8d1659a146e7726681a002bb9 ;;
            mailpit) image=axllent/mailpit:v1.31.6@sha256:23fe39e81bde596a918000b3135c0f85dc8bb4c9ea6be274b051143f3e300387 ;;
            clamav) image=clamav/clamav:1.5.5@sha256:64e30c0bf976198024deb3dc6d5cfe2485fb969c0f73549ba0fdb364b9469614 ;;
            seaweedfs) image=chrislusf/seaweedfs:4.55@sha256:7ca46aeae2f223b37e5d91593c17ab37738eec6df6ab69b48a0487f7643342b9 ;;
            valkey) image=valkey/valkey:9.1.2-alpine@sha256:48332870af354a799964c0012ae1194a0bf2bf894eb508f945810596dc2d8d11 ;;
            *) exit 2 ;;
        esac
        if [[ "$image" != "cabinet-ci-${CI_JOB_ID}-scan:test" ]]; then
            docker pull "$image"
        fi
        python3 security/scan.py --image "$image" \
            --report-name "infra-$INFRA_NAME" --cache-volume "$cache"
        ;;
    *) exit 2 ;;
esac
