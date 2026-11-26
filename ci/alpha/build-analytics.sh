#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
revision=$(git rev-parse HEAD)
if [[ "$revision" != "${CI_COMMIT_SHA:-$revision}" ||
    -n "$(git status --porcelain --untracked-files=all)" ]]; then
    echo "Build Analytics from a clean checkout matching the candidate revision" >&2
    exit 2
fi

./gradlew --no-daemon -PalphaAnalytics=true :services:analytics:bootJar

artifacts=ci-artifacts/alpha-analytics
mkdir -p "$artifacts"
install -m 0644 services/analytics/build/libs/app.jar "$artifacts/app.jar"
printf '%s\n' "$revision" > "$artifacts/source-revision.txt"
(cd "$artifacts" && sha256sum app.jar > app.jar.sha256)
