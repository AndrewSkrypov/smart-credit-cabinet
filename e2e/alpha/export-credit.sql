-- Run with psql -XAt. One statement supplies rows and counts from a consistent snapshot.
with visible as (
    select id, version, status
    from applications
    where status not in ('DRAFT', 'PENDING_CLIENT_CONFIRMATION')
), counts as (
    select status, count(*) as n from visible group by status
)
select jsonb_build_object(
    'rows', coalesce((select jsonb_agg(to_jsonb(visible) order by id) from visible), '[]'),
    'currentStatuses', coalesce((select jsonb_object_agg(status, n) from counts), '{}')
);
