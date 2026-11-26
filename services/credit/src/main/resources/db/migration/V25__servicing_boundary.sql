-- Additive only: legacy servicing rows and signed documents are preserved.
create table servicing_open_outbox (
    command_id uuid primary key,
    application_id uuid not null unique references applications(id),
    cycle_id uuid not null,
    application_version bigint not null,
    payload_sha256 char(64) not null,
    command_json jsonb not null,
    booked_loan_json jsonb not null,
    receipt_json jsonb,
    state varchar(16) not null default 'PENDING' check (state in ('PENDING','CONFIRMED','CONFLICT')),
    attempts integer not null default 0,
    available_at timestamptz not null default now(),
    last_error varchar(500),
    created_at timestamptz not null default now(),
    confirmed_at timestamptz
);
create index servicing_open_outbox_ready on servicing_open_outbox(available_at) where state='PENDING';

create table servicing_opened_commands (
    application_id uuid primary key,
    command_id uuid not null unique,
    cycle_id uuid not null,
    account_id uuid not null unique references loan_accounts(id) on delete cascade,
    payload_sha256 char(64) not null,
    command_json jsonb not null,
    receipt_json jsonb not null,
    recorded_at timestamptz not null default now()
);

create table servicing_writer_fence (
    singleton boolean primary key default true check (singleton),
    writer varchar(8) not null check (writer in ('LOCAL','REMOTE','FENCED')),
    epoch bigint not null default 1 check (epoch > 0),
    manifest_sha256 char(64),
    changed_at timestamptz not null default now()
);
insert into servicing_writer_fence(singleton,writer) values(true,'LOCAL');
-- FOR SHARE requires UPDATE privilege. Keep locking inside a narrow owner function;
-- runtime may read/check the fence but cannot change it or disable table triggers.
create function assert_servicing_writer(expected_writer text) returns boolean language plpgsql
security definer set search_path=pg_catalog as $$
declare active_writer text;
begin
    select writer into active_writer from public.servicing_writer_fence where singleton=true for share;
    if active_writer is distinct from expected_writer or expected_writer not in ('LOCAL','REMOTE') then
        raise exception 'Servicing writer is fenced' using errcode='55000';
    end if;
    return true;
end $$;
create function enforce_servicing_writer() returns trigger language plpgsql as $$
begin
    perform public.assert_servicing_writer('LOCAL');
    if tg_op='DELETE' then return old; end if;
    return new;
end $$;
create trigger loan_accounts_writer_fence before insert or update or delete on loan_accounts for each row execute function enforce_servicing_writer();
create trigger loan_tranches_writer_fence before insert or update or delete on loan_tranches for each row execute function enforce_servicing_writer();
create trigger loan_payments_writer_fence before insert or update or delete on loan_payments for each row execute function enforce_servicing_writer();
create trigger payment_reserves_writer_fence before insert or update or delete on payment_reserves for each row execute function enforce_servicing_writer();
create trigger payment_reserve_receipt_events_writer_fence before insert or update or delete on payment_reserve_receipt_events for each row execute function enforce_servicing_writer();
create trigger servicing_opened_commands_writer_fence before insert or update or delete on servicing_opened_commands for each row execute function enforce_servicing_writer();
