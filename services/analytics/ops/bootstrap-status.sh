#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 ]]; then
    printf 'Использование: %s <topic нового поколения> <файл снимка CSV>\n' "$0" >&2
    exit 2
fi

generation=$1
snapshot=$2
if [[ ! $generation =~ ^[a-zA-Z0-9._-]{1,249}$ || $generation == . || $generation == .. ]]; then
    printf 'Некорректное имя топика Kafka\n' >&2
    exit 2
fi

: "${ANALYTICS_SNAPSHOT_SERVICE:?Задайте профиль PostgreSQL для владельца БД Analytics}"
ops_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)

umask 077
if [[ ! -e $snapshot && ! -L $snapshot ]]; then
    : "${CREDIT_SNAPSHOT_SERVICE:?Задайте профиль PostgreSQL для чтения статусов Credit}"
    temporary=$(mktemp -- "$(dirname -- "$snapshot")/.analytics-snapshot.XXXXXX")
    trap 'rm -f -- "$temporary"' EXIT
    PGSERVICE=$CREDIT_SNAPSHOT_SERVICE psql -X -q -v ON_ERROR_STOP=1 \
        -f "$ops_dir/export-status-snapshot.sql" > "$temporary"
    ln -T -- "$temporary" "$snapshot"
fi
if [[ ! -f $snapshot || -L $snapshot ]]; then
    printf 'Снимок должен быть обычным файлом\n' >&2
    exit 2
fi

digest=$(sha256sum < "$snapshot")
PGSERVICE=$ANALYTICS_SNAPSHOT_SERVICE psql -X -q -v ON_ERROR_STOP=1 \
    -v generation="$generation" -v snapshot_sha256="${digest%% *}" \
    -f "$ops_dir/import-status-snapshot.sql" < "$snapshot"
printf 'Загружен снимок поколения %s, SHA-256 %s\n' "$generation" "${digest%% *}"
