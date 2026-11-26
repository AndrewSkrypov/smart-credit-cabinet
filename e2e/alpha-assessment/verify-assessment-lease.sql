-- Exercise the lease conditions with controlled times on the real V1 schema.
-- This SQL mirrors the worker predicates. It does not start the Assessment application.
do $$
declare
    test_stream constant text := 'synthetic.migrations';
    test_cycle constant uuid := '40000000-0000-4000-8000-000000000001';
    test_application constant uuid := '10000000-0000-4000-8000-000000000001';
    old_token constant uuid := '50000000-0000-4000-8000-000000000001';
    new_token constant uuid := '50000000-0000-4000-8000-000000000002';
    test_now constant timestamptz := '2026-11-27T09:00:00Z';
    lease timestamptz;
    claimed_token uuid;
begin
    if current_database() !~ '^r2_migration_assessment_[a-z0-9]{8}$' then
        raise exception 'Lease verification requires a private migration-test database';
    end if;
    if to_regclass('applications') is not null or to_regclass('credit_assessment_cycle') is not null then
        raise exception 'Assessment V1 shares Credit tables';
    end if;

    insert into assessment_execution (stream, cycle_id, application_id, request, attempts, available_at, locked_until, claim_token)
    values (test_stream, test_cycle, test_application, '{}', 1, test_now, test_now, old_token);

    select locked_until into lease from assessment_execution
    where stream = test_stream and cycle_id = test_cycle and completed_at is null and claim_token = old_token for update;
    if lease is null or lease > test_now then
        raise exception 'The old claim must exist but fail the worker time check at lease expiry';
    end if;

    with candidate as (
        select cycle_id from assessment_execution
        where stream = test_stream and completed_at is null and available_at <= test_now
          and (locked_until is null or locked_until <= test_now)
        order by available_at, cycle_id limit 1 for update skip locked
    )
    update assessment_execution e
    set locked_until = test_now + interval '45 seconds', claim_token = new_token, attempts = e.attempts + 1
    from candidate c where e.stream = test_stream and e.cycle_id = c.cycle_id
    returning e.claim_token into claimed_token;
    if claimed_token is distinct from new_token then
        raise exception 'The expired lease was not reclaimed';
    end if;

    select locked_until into lease from assessment_execution
    where stream = test_stream and cycle_id = test_cycle and completed_at is null and claim_token = old_token for update;
    if lease is not null then
        raise exception 'The old claim token remained valid after takeover';
    end if;

    select locked_until into lease from assessment_execution
    where stream = test_stream and cycle_id = test_cycle and completed_at is null and claim_token = new_token for update;
    if lease is null or lease <= test_now then
        raise exception 'The replacement token does not have a live lease';
    end if;

    insert into assessment_exchange_outbox (stream, cycle_id, application_id, payload)
    values (test_stream, test_cycle, test_application, '{}');
end $$;
