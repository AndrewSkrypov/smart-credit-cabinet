#!/bin/sh
set -eu

pg() {
    psql -XqwAt -v ON_ERROR_STOP=1 -h assessment-db "$@"
}

case "${1:-}" in
    bootstrap)
        for password in "${ASSESSMENT_ADMIN_PASSWORD:?}" "${ASSESSMENT_MIGRATION_PASSWORD:?}" "${ASSESSMENT_DB_PASSWORD:?}"; do
            printf '%s\n' "$password" | grep -Eq '^[a-f0-9]{64}$' || exit 1
        done
        test "$ASSESSMENT_ADMIN_PASSWORD" != "$ASSESSMENT_MIGRATION_PASSWORD"
        test "$ASSESSMENT_ADMIN_PASSWORD" != "$ASSESSMENT_DB_PASSWORD"
        test "$ASSESSMENT_MIGRATION_PASSWORD" != "$ASSESSMENT_DB_PASSWORD"
        export PGPASSWORD="${ASSESSMENT_ADMIN_PASSWORD:?}"
        until pg -U assessment_admin -d postgres -c 'select 1' >/dev/null 2>&1; do sleep 2; done
        pg -U assessment_admin -d postgres -f /run/assessment/bootstrap.sql
        export PGPASSWORD="${ASSESSMENT_MIGRATION_PASSWORD:?}"
        test "$(pg -U assessment_owner -d assessment -f /run/assessment/identity.sql)" = "${ASSESSMENT_GENERATION:?}"
        ;;
    grant)
        export PGPASSWORD="${ASSESSMENT_MIGRATION_PASSWORD:?}"
        pg -U assessment_owner -d assessment -f /run/assessment/grants.sql
        ;;
    check)
        export PGPASSWORD="${ASSESSMENT_DB_PASSWORD:?}"
        for attempt in $(seq 1 90); do
            if test "$(pg -U assessment_app -d assessment -f /run/assessment/check.sql 2>/dev/null)" = 1; then exit 0; fi
            sleep 2
        done
        exit 1
        ;;
    *) exit 2 ;;
esac
