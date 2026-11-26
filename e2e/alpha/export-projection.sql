-- Run against the isolated Analytics database of the generation under test with psql -XAt.
with visible as (
    select application_id as id, application_version as version, status
    from application_status_projection
    where status not in ('DRAFT', 'PENDING_CLIENT_CONFIRMATION')
), counts as (
    select status, count(*) as n from visible group by status
)
select jsonb_build_object(
    'rows', coalesce((select jsonb_agg(to_jsonb(visible) order by id) from visible), '[]'),
    'currentStatuses', coalesce((select jsonb_object_agg(status, n) from counts), '{}')
);
