export PGCONNECT_TIMEOUT=5
export PGOPTIONS='-c lock_timeout=30000 -c statement_timeout=90000'
umask 077
cd /tmp
for key in "$CATALOG_DB_PASSWORD" "$CATALOG_MIGRATION_PASSWORD"; do
    case "$key" in
        *[!a-f0-9]*) echo "Catalog database credentials must be 64 lowercase hexadecimal digits" >&2; exit 1 ;;
    esac
    if [ "${#key}" != 64 ]; then
        echo "Catalog database credentials must be 64 lowercase hexadecimal digits" >&2
        exit 1
    fi
done
[ "$CATALOG_DB_PASSWORD" != "$CATALOG_MIGRATION_PASSWORD" ]

psql_ro() {
    psql -Xw -v ON_ERROR_STOP=1 -v VERBOSITY=terse "$@"
}
{
    printf '\\set owner_password %s\n' "$CATALOG_MIGRATION_PASSWORD"
    printf '\\set app_password %s\n' "$CATALOG_DB_PASSWORD"
    cat roles.sql
} | psql_ro -U cabinet -d cabinet >/dev/null

{
    printf '%s\n' 'begin;' "select to_regclass('catalog_cutover.authority') is null as pending \gset" '\if :pending'
    cat V1__product_history.sql
    printf '%s\n' '\endif' 'commit;'
    cat grants.sql
} | PGPASSWORD="$CATALOG_MIGRATION_PASSWORD" psql_ro -U catalog_owner -d catalog >/dev/null

credit_has_fence="$(PGPASSWORD="$CREDIT_MIGRATION_PASSWORD" psql_ro -U credit_owner -d credit -Atqc \
    "select to_regclass('catalog_cutover.authority') is not null")"
if [ "$credit_has_fence" = f ]; then
    PGPASSWORD="$CREDIT_MIGRATION_PASSWORD" psql_ro -U credit_owner -d credit -v credit_app=credit_app -f credit-fence.sql >/dev/null
fi
authority='select writable from catalog_cutover.authority where singleton'
credit="$(PGPASSWORD="$CREDIT_DB_PASSWORD" psql_ro -U credit_app -d credit -Atqc "$authority")"
catalog="$(PGPASSWORD="$CATALOG_DB_PASSWORD" psql_ro -U catalog_app -d catalog -Atqc "$authority")"
case "$CATALOG_MODE:$credit:$catalog" in
    local:t:f) PGPASSWORD="$CATALOG_DB_PASSWORD" psql_ro -U catalog_app -d catalog -f fence-check.sql >/dev/null ;;
    remote:f:t)
        PGPASSWORD="$CREDIT_DB_PASSWORD" psql_ro -U credit_app -d credit -f fence-check.sql >/dev/null
        [ "$(PGPASSWORD="$CREDIT_DB_PASSWORD" psql_ro -U credit_app -d credit -Atqc 'select catalog_cutover.credit_replay_ready()')" = t ]
        ;;
    *) echo "Catalog database authority does not match the requested Credit mode" >&2; exit 1 ;;
esac
