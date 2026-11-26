-- Export synthetic restore tripwires only. Run against the isolated Credit database with psql -XAt.
with ids as (
    select unnest(array[
        '10000000-0000-4000-8000-000000000005'::uuid,
        '10000000-0000-4000-8000-000000000010'::uuid,
        '10000000-0000-4000-8000-000000000099'::uuid
    ]) as id
), apps as (
    select a.id, a.version, a.status
    from applications a join ids using (id)
), journal as (
    select e.id, e.application_id, e.event, e.to_status
    from application_events e
    where e.application_id in (select id from ids) and e.event = 'WITHDRAW'
), outbox as (
    select o.id, o.event_key, o.state, o.attempts, o.payload,
           to_jsonb(o) ->> 'stream_generation' as stream_generation
    from application_status_outbox o
    where o.payload ->> 'applicationId' in (select id::text from ids)
)
select jsonb_build_object(
    'applications', coalesce((select jsonb_agg(to_jsonb(apps) order by id) from apps), '[]'),
    'journal', coalesce((select jsonb_agg(to_jsonb(journal) order by id) from journal), '[]'),
    'outbox', coalesce((select jsonb_agg(to_jsonb(outbox) order by id) from outbox), '[]')
);
