#!/usr/bin/env bash
# Optional official seed for restricted egress. The ordinary ClamAV readiness
# remains in force, including the seven-day age bound and engine reload checks.
set -euo pipefail

runtime='clamav/clamav:1.5.5@sha256:64e30c0bf976198024deb3dc6d5cfe2485fb969c0f73549ba0fdb364b9469614'
upstream=${CABINET_CI_CLAMAV_SEED_IMAGE:-}
from_files=${CABINET_CI_CLAMAV_SEED_FROM_FILES:-0}
[[ "$from_files" = 0 || "$from_files" = 1 ]]
if [[ -z "$upstream" && "$from_files" = 0 ]]; then
    printf '%s\n' "$runtime"
    exit 0
fi

: "${CI_JOB_ID:?}" "${CABINET_CI_CLAMAV_SEED_SHA256:?}" "${CABINET_CI_CLAMAV_SEED_SERIAL:?}"
if [[ -n "$upstream" && "$from_files" = 1 ]]; then
    echo 'Choose one official ClamAV seed source' >&2
    exit 2
fi
[[ "$CABINET_CI_CLAMAV_SEED_SHA256" =~ ^[a-f0-9]{64}$ ]]
[[ "$CABINET_CI_CLAMAV_SEED_SERIAL" =~ ^[0-9]+$ ]]
[[ "$CI_JOB_ID" =~ ^[0-9]+$ ]]
serial="$CABINET_CI_CLAMAV_SEED_SERIAL"
image="cabinet-ci-$CI_JOB_ID-clamav:test"
context=$(mktemp -d)
cid=
cleanup() {
    if [[ -n "$cid" ]]; then docker rm "$cid" >/dev/null 2>&1 || true; fi
    rm -r -- "$context"
}
trap cleanup EXIT

if [[ -n "$upstream" ]]; then
    [[ "$upstream" =~ ^clamav/clamav@sha256:[a-f0-9]{64}$ ]]
    docker pull --platform linux/amd64 "$upstream" >&2
    cid=$(docker create --platform linux/amd64 "$upstream")
    docker cp "$cid:/var/lib/clamav/daily.cvd" "$context/daily.cvd"
    docker cp "$cid:/var/lib/clamav/daily-$serial.cvd.sign" "$context/daily-$serial.cvd.sign"
else
    case "${1:-quality}" in
        quality) seed_dir="/var/lib/pump-ci/clamav-official/$serial";;
        release) seed_dir="/var/lib/pump-release/clamav-official/$serial";;
        *) exit 2;;
    esac
    [[ "$(realpath -e -- "$seed_dir")" == "$seed_dir" ]]
    cp -- "$seed_dir/daily.cvd" "$seed_dir/daily-$serial.cvd.sign" "$context/"
fi
printf '%s  %s\n' "$CABINET_CI_CLAMAV_SEED_SHA256" "$context/daily.cvd" | sha256sum -c - >&2
result=$(docker run --rm --network none --platform linux/amd64 --entrypoint /bin/sh \
    --mount "type=bind,source=$context,target=/seed,readonly" "$runtime" -ec '
        sigtool --cvdcertsdir /etc/clamav/certs --verify /seed/daily.cvd >&2
        exec sigtool --info /seed/daily.cvd
    ')
grep -Fxq "Version: $serial" <<<"$result"
grep -Fxq 'Verification OK.' <<<"$result"
published=$(date -u -d "$(sed -n 's/^Build time: //p' <<<"$result")" +%s)
age=$(( $(date -u +%s) - published ))
if (( age < -300 || age > 7 * 24 * 60 * 60 )); then
    printf 'Official ClamAV seed %s age %s seconds is outside the freshness gate\n' "$serial" "$age" >&2
    exit 1
fi
printf 'Verified official ClamAV seed %s, age %s seconds\n' "$serial" "$age" >&2
cat >"$context/Dockerfile" <<DOCKER
FROM $runtime
COPY --chown=clamav:clamav daily.cvd daily-$serial.cvd.sign /var/lib/clamav/
DOCKER
args=(--platform linux/amd64 -t "$image")
if [[ -n "${CI_COMMIT_SHA:-}" ]]; then
    [[ "$CI_COMMIT_SHA" =~ ^[a-f0-9]{40}$ ]]
    args+=(--label "org.opencontainers.image.revision=$CI_COMMIT_SHA")
fi
docker build "${args[@]}" "$context" >&2
printf '%s\n' "$image"
