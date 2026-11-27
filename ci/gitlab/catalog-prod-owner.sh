#!/bin/sh
# Invoked over stdin in the selected PostgreSQL Pod. Emit only the writer flags.
set -eu
export PGPASSWORD="$POSTGRES_PASSWORD" PGCONNECT_TIMEOUT=5 PGOPTIONS='-c statement_timeout=5000'
query() {
    psql -Xw -h 127.0.0.1 -U "$POSTGRES_USER" -Atq -v ON_ERROR_STOP=1 "$@"
}
if [ "${1:-}" = history ]; then
    umask 077
    work=$(mktemp -d)
    trap 'rm -r -- "$work"' EXIT
    snapshot_export >"$work/export.sql"
    export PGOPTIONS='-c statement_timeout=90000'
    for database in credit catalog; do
        mkdir "$work/$database"
        (
            cd "$work/$database"
            credit_source=false
            if [ "$database" = credit ]; then credit_source=true; fi
            query -d "$database" -v "expected_database=$database" -v "credit_source=$credit_source" \
                -f "$work/export.sql" >/dev/null
            sha256sum products.csv product_versions.csv
        ) >"$work/$database.sha256"
    done
    if ! cmp -s "$work/credit.sha256" "$work/catalog.sha256"; then
        echo 'Catalog history and current pointers differ between Credit and Catalog' >&2
        exit 1
    fi
    cat "$work/credit.sha256"
    exit 0
fi
for database in credit catalog; do
    exists=$(query -d "$POSTGRES_DB" -c "select exists (select from pg_database where datname = '$database')")
    case "$exists" in
        f) echo absent ;;
        t) query -d "$database" <<'SQL'
select to_regclass('catalog_cutover.authority') is not null as has_authority \gset
\if :has_authority
select writable from catalog_cutover.authority where singleton;
\else
select 'unfenced';
\endif
SQL
        ;;
        *) exit 1 ;;
    esac
done
