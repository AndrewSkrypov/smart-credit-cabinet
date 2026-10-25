create table contract_text_snapshots (
    id uuid primary key,
    application_id uuid not null references applications(id),
    offer_id uuid not null unique references offers(id),
    template_version integer not null check (template_version > 0),
    contract_text text not null,
    sha256 varchar(64) not null,
    created_at timestamptz not null
);
alter table signing_sessions add column sequence_no bigint generated always as identity;
alter table signing_sessions add column contract_snapshot_id uuid references contract_text_snapshots(id);

create function contract_text_snapshots_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'contract_text_snapshots is append-only';
end $$;
create trigger contract_text_snapshots_no_update before update or delete on contract_text_snapshots
    for each row execute function contract_text_snapshots_append_only();
