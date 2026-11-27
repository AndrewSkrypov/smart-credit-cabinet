-- Drain or close pinned remote cycles before rolling back the transport schema.
do $$
begin
    if to_regclass('credit_assessment_cycle') is not null then
        if exists (
            select 1 from credit_assessment_cycle c
            join applications a on a.id = c.application_id
            where c.request is not null and c.closed_at is null and a.status in ('ENRICHMENT', 'SCORING')
        ) then
            raise exception 'Active remote assessment cycles must finish before rollback';
        end if;
    end if;
end $$;

drop table if exists assessment_exchange_outbox;
drop table if exists credit_assessment_cycle;
