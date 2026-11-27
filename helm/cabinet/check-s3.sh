#!/bin/sh
# Check the application's bucket from a localhost-only port-forward.
set -eu
port=${1:?Usage: check-s3.sh LOCAL_PORT [BUCKET] [REGION]}
case "$port" in ''|*[!0-9]*) echo 'Expected a localhost TCP port' >&2; exit 2;; esac
export S3_SMOKE_ENDPOINT="http://127.0.0.1:$port"
export S3_SMOKE_BUCKET=${2:-cabinet-documents}
export S3_SMOKE_REGION=${3:-us-east-1}
exec sh "$(dirname -- "$0")/files/s3-smoke.sh"
