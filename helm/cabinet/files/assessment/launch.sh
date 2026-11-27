#!/usr/bin/env bash
set -euo pipefail

health() {
    curl -fsS --max-time 3 http://127.0.0.1:8081/actuator/health/readiness >/dev/null
}

cache() {
    local host=${CABINET_VALKEY_HOST:?} port=${CABINET_VALKEY_PORT:-6379}
    local password=${CABINET_VALKEY_PASSWORD:?} reply
    [[ ${CABINET_VALKEY_ENABLED:-} = true && $password =~ ^[A-Za-z0-9_-]{32,}$ && $password != dev-* ]]
    [[ $host =~ ^[A-Za-z0-9.-]+$ && $port =~ ^[0-9]{1,5}$ ]]
    exec 3<>"/dev/tcp/$host/$port"
    printf '*2\r\n$4\r\nAUTH\r\n$%s\r\n%s\r\n' "${#password}" "$password" >&3
    IFS= read -r -t 2 reply <&3
    [[ $reply == $'+OK\r' ]]
    printf '*1\r\n$4\r\nPING\r\n' >&3
    IFS= read -r -t 2 reply <&3
    [[ $reply == $'+PONG\r' ]]
}

sources() {
    local key=${CABINET_ASSESSMENT_SOURCES_API_KEY:?} status
    [[ $key =~ ^[A-Za-z0-9_-]{24,}$ && $key != dev-* ]]
    [[ ${CABINET_ASSESSMENT_SOURCES_URL:-} = http://external-sim:8080 ]]
    status=$(printf 'header = "X-Api-Key: %s"\n' "$key" |
        curl --config - -sS --max-time 5 --output /dev/null --write-out '%{http_code}' \
            "$CABINET_ASSESSMENT_SOURCES_URL/ext/registry/v1/companies/000000000000")
    [[ $status == 200 || $status == 404 ]]
}

start_java() {
    export SPRING_PROFILES_ACTIVE=prod SPRING_MAIN_WEB_APPLICATION_TYPE=servlet SERVER_ADDRESS=127.0.0.1 SERVER_PORT=8081
    export MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true
    export MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db
    export SPRINGDOC_API_DOCS_ENABLED=false SPRINGDOC_SWAGGER_UI_ENABLED=false
    exec java org.springframework.boot.loader.launch.JarLauncher
}

case "${1:-}" in
    health) health ;;
    cache) cache ;;
    runtime)
        [[ ${ASSESSMENT_DB_USER:-} = assessment_app && ${SPRING_FLYWAY_ENABLED:-} = false ]]
        [[ ${ASSESSMENT_DB_URL:-} = jdbc:postgresql://assessment-db:5432/assessment ]]
        [[ ${CABINET_ASSESSMENT_KAFKA_ENABLED:-} = true ]]
        timeout 5s bash "$0" cache
        sources
        start_java
        ;;
    migrate)
        [[ ${ASSESSMENT_DB_USER:-} = assessment_owner && ${SPRING_FLYWAY_ENABLED:-} = true ]]
        [[ ${ASSESSMENT_DB_URL:-} = jdbc:postgresql://assessment-db:5432/assessment ]]
        [[ ${CABINET_ASSESSMENT_KAFKA_ENABLED:-} = false ]]
        start_java &
        child=$!
        trap 'kill "$child" 2>/dev/null || true; wait "$child" 2>/dev/null || true' EXIT
        trap 'exit 143' TERM
        for ((attempt=0; attempt<60; attempt++)); do
            kill -0 "$child"
            if health 2>/dev/null; then exit 0; fi
            sleep 2
        done
        exit 1
        ;;
    *) exit 2 ;;
esac
