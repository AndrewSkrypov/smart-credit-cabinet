#!/usr/bin/env bash
set -euo pipefail

if (( $# < 2 )); then
    printf 'Usage: %s <revision> <artifact-directory> [Gradle options]\n' "$0" >&2
    exit 2
fi

revision=$(git rev-parse --verify --end-of-options "$1^{commit}")
artifact_directory=$2
shift 2
mkdir -p "$artifact_directory"
artifact_directory=$(cd "$artifact_directory" && pwd -P)
if [[ -e "$artifact_directory/source.sha" || -e "$artifact_directory/junit" ]]; then
    printf 'Choose an unused artifact directory: %s\n' "$artifact_directory" >&2
    exit 2
fi
mkdir "$artifact_directory/junit"
printf '%s\n' "$revision" > "$artifact_directory/source.sha"

source_directory=$(mktemp -d "${TMPDIR:-/tmp}/cabinet-backend-template.XXXXXXXX")
trap 'rm -rf -- "$source_directory"' EXIT
git archive --format=tar "$revision" | tar -xf - -C "$source_directory"
cd "$source_directory"

module=template-smoke
python3 templates/backend-service/generate.py "$module"
project=":services:$module"
gradle_status=0
"${CABINET_TEMPLATE_GRADLE:-./gradlew}" "$@" --no-daemon --no-watch-fs --max-workers=1 \
    -Pkotlin.compiler.execution.strategy=in-process \
    '-Dorg.gradle.jvmargs=-Xmx850m -XX:ActiveProcessorCount=2' \
    "$project:test" "$project:ktlintCheck" "$project:bootJar" || gradle_status=$?

shopt -s nullglob
reports=(services/"$module"/build/test-results/test/TEST-*.xml)
if (( ${#reports[@]} )); then
    cp -- "${reports[@]}" "$artifact_directory/junit/"
elif (( gradle_status == 0 )); then
    printf 'Expected JUnit reports for %s\n' "$project" >&2
    exit 1
fi
if (( gradle_status == 0 )); then
    test -s "services/$module/build/libs/app.jar"
fi
exit "$gradle_status"
