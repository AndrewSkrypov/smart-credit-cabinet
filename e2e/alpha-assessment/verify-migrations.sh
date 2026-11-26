#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || $1 != /* ]]; then
    echo "Usage: $0 /absolute/path/to/postgresql/bin" >&2
    exit 2
fi
pg_bin=$1
for executable in initdb pg_ctl createdb psql; do
    test -x "$pg_bin/$executable" || { echo "Missing $pg_bin/$executable" >&2; exit 2; }
done

root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
run_dir=$(mktemp -d /tmp/alpha-assessment-pg.XXXXXXXX)
data_dir=$run_dir/data

cleanup() {
    local status=$?
    if [[ -f "$data_dir/postmaster.pid" ]]; then
        if ! "$pg_bin/pg_ctl" -D "$data_dir" -w -t 20 -m fast stop; then
            echo "Could not stop PostgreSQL. The isolated directory remains at $run_dir" >&2
            exit 1
        fi
        echo "Stopped isolated PostgreSQL PID $pg_pid"
    fi
    rm -rf -- "$run_dir"
    exit "$status"
}
pg_pid=unknown
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

export LC_ALL=C
"$pg_bin/initdb" -D "$data_dir" -U alpha_verifier --auth=trust --locale=C --encoding=UTF8 > "$run_dir/initdb.log"
if ! "$pg_bin/pg_ctl" -D "$data_dir" -l "$run_dir/postgres.log" -w -t 20 start \
    -o "-h '' -k $run_dir -p 5432 -c max_connections=10 -c shared_buffers=16MB"; then
    cat "$run_dir/postgres.log" >&2
    exit 1
fi
read -r pg_pid < "$data_dir/postmaster.pid"
echo "Started isolated PostgreSQL PID $pg_pid"

pg_connection=(-h "$run_dir" -p 5432 -U alpha_verifier)
for database in alpha_qa_credit alpha_qa_assessment; do
    "$pg_bin/createdb" "${pg_connection[@]}" "$database"
done

psql() {
    "$pg_bin/psql" -Xq "${pg_connection[@]}" -v ON_ERROR_STOP=1 "$@"
}

psql -d alpha_qa_credit -Atc 'select version()'
credit_migrations=$root/services/credit/src/main/resources/db/migration
for migration in "$credit_migrations"/V{1..23}__*.sql; do
    psql -d alpha_qa_credit --single-transaction -f "$migration"
done

psql -d alpha_qa_credit --single-transaction -f - <<'SQL'
insert into applications (
    id, company_id, company_inn, legal_form, created_by, product_code, product_version, product_catalog_snapshot,
    purpose, amount, term_months, status, created_at, updated_at, submitted_at, status_changed_at
)
select '10000000-0000-4000-8000-000000000001', '20000000-0000-4000-8000-000000000001', '7700000001',
       'LEGAL_ENTITY', '30000000-0000-4000-8000-000000000001', code, current_version,
       jsonb_build_object(code, current_version), 'WORKING_CAPITAL', 1000000, 12, 'ENRICHMENT', now(), now(), now(), now()
from products where code = 'WORKING_CAPITAL';

insert into pipeline_jobs (id, application_id, step, run_at, created_at, updated_at)
values ('40000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'ENRICHMENT', now(), now(), now());
SQL

psql -d alpha_qa_credit --single-transaction -f "$credit_migrations/V24__remote_assessment.sql"
psql -d alpha_qa_assessment --single-transaction -f "$root/services/assessment/src/main/resources/db/migration/V1__assessment.sql"
echo 'Applied Credit V1–V24 and Assessment V1 in separate databases'

psql -d alpha_qa_credit --single-transaction -f - <<'SQL'
do $$
begin
    if (select count(*) from credit_assessment_cycle where request is null and stream is null) <> 1 then
        raise exception 'V24 did not pin the pending legacy task to local assessment';
    end if;
end $$;

update pipeline_jobs set status = 'DONE', updated_at = now();
insert into pipeline_jobs (id, application_id, step, run_at, created_at, updated_at)
select '40000000-0000-4000-8000-000000000002', id, 'ENRICHMENT', now(), now(), now()
from applications where status = 'ENRICHMENT';

insert into credit_assessment_cycle (cycle_id, application_id, application_version, stream, request, accept_until)
select j.id, a.id, a.version, 'synthetic.migrations', '{}', now() + interval '1 minute'
from pipeline_jobs j join applications a on a.id = j.application_id
where j.step = 'ENRICHMENT' and j.status = 'PENDING' and a.status = 'ENRICHMENT';

insert into assessment_exchange_outbox (stream, cycle_id, application_id, payload)
select stream, cycle_id, application_id, request from credit_assessment_cycle where request is not null;
SQL

undo=$root/db/rollback/credit/U24__remote_assessment.sql
if psql -d alpha_qa_credit --single-transaction -f "$undo" > "$run_dir/rollback.log" 2>&1; then
    echo 'U24 unexpectedly accepted the open pinned cycle' >&2
    exit 1
else
    status=$?
    cat "$run_dir/rollback.log"
    test "$status" = 3
    [[ $(<"$run_dir/rollback.log") == *"Active remote assessment cycles must finish before rollback"* ]]
fi

psql -d alpha_qa_credit --single-transaction -f - <<'SQL'
do $$
begin
    if (select count(*) from credit_assessment_cycle where closed_at is null and request is not null) <> 1
       or (select count(*) from assessment_exchange_outbox where sent_at is null) <> 1 then
        raise exception 'Refused U24 changed the pinned cycle or its outbox';
    end if;
end $$;

update applications set status = 'MANUAL_REVIEW', updated_at = now();
update pipeline_jobs set status = 'DONE', updated_at = now();
update credit_assessment_cycle set closed_at = now();
SQL
echo 'U24 refused the open pinned ENRICHMENT cycle and preserved its outbox'

psql -d alpha_qa_credit --single-transaction -f "$undo"
psql -d alpha_qa_credit -f - <<'SQL'
do $$
begin
    if to_regclass('credit_assessment_cycle') is not null or to_regclass('assessment_exchange_outbox') is not null then
        raise exception 'U24 left assessment transport tables in Credit';
    end if;
    if (select count(*) from applications where status = 'MANUAL_REVIEW') <> 1
       or (select count(*) from pipeline_jobs where step = 'ENRICHMENT' and status = 'DONE') <> 2 then
        raise exception 'U24 changed the existing Credit application or jobs';
    end if;
end $$;
SQL
echo 'U24 succeeded after closure and preserved the Credit application and job'

psql -d alpha_qa_assessment --single-transaction -f "$root/e2e/alpha-assessment/verify-assessment-lease.sql"
echo 'Assessment V1 lease predicates passed with SQL equivalent to the worker'
