#!/usr/bin/env bash
set -euo pipefail

image=ollama/ollama@sha256:2b8eecdb85aa6ea67c381c8ecffec04b2658496cf2821a8f88b0485bdff198e8

checked_local_image() {
    docker image inspect "$image" --format '{{range .RepoDigests}}{{println .}}{{end}}' 2>/dev/null |
        grep -Fx -- "$image" >/dev/null
}

if ! checked_local_image; then
    docker pull --platform linux/amd64 "$image"
fi
checked_local_image
test "$(docker image inspect "$image" --format '{{.Os}}/{{.Architecture}}')" = linux/amd64
image_id=$(docker image inspect "$image" --format '{{.Id}}')
mkdir -p security/reports/infra-ollama
python3 - "$image" "$image_id" > security/reports/infra-ollama/oci.json <<'PY'
import json
import sys

_, verified_repo_digest, image_id = sys.argv
print(json.dumps({"verified_repo_digest": verified_repo_digest, "image_id": image_id, "platform": "linux/amd64"}))
PY
python3 security/scan.py --image "$image" --report-name infra-ollama \
    --cache-volume "cabinet-trivy-${CI_JOB_ID:?}" --timeout-minutes 30
