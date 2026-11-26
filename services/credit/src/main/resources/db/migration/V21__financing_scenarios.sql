alter table applications
    add column client_handoff_at timestamptz,
    add column client_managed boolean not null default false,
    add constraint ck_applications_client_managed
        check (not client_managed or (agent_id is not null and client_handoff_at is not null));

update applications a
set client_handoff_at = history.first_handoff_at
from (
    select application_id, min(occurred_at) as first_handoff_at
    from application_events
    where event = 'REQUEST_CLIENT_CONFIRMATION'
    group by application_id
) history
where a.id = history.application_id and a.agent_id is not null;

update applications
set client_handoff_at = coalesce(submitted_at, status_changed_at)
where agent_id is not null and client_handoff_at is null
  and (submitted_at is not null or status = 'PENDING_CLIENT_CONFIRMATION');

create table financing_scenario_revisions (
    application_id uuid not null references applications(id),
    revision bigint not null check (revision > 0),
    product_version bigint not null check (product_version > 0),
    snapshot jsonb not null,
    primary key (application_id, revision)
);

create table financing_scenario_publications (
    application_id uuid not null,
    revision bigint not null,
    status varchar(24) not null check (status in ('AWAITING_CLIENT', 'CONFIRMED')),
    document_ids jsonb not null default '[]'::jsonb,
    handed_terms jsonb,
    check (status <> 'AWAITING_CLIENT' or handed_terms is not null),
    primary key (application_id, revision, status),
    foreign key (application_id, revision) references financing_scenario_revisions(application_id, revision)
);

create function financing_scenario_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'financing scenario history is append-only';
end
$$;

create trigger financing_scenario_revisions_append_only
    before update or delete on financing_scenario_revisions
    for each row execute function financing_scenario_append_only();

create trigger financing_scenario_publications_append_only
    before update or delete on financing_scenario_publications
    for each row execute function financing_scenario_append_only();

comment on column applications.client_handoff_at is 'Время первой передачи агентской заявки своему клиенту';
comment on column applications.client_managed is 'Клиент продолжил переданную заявку самостоятельно после прекращения мандата';
comment on table financing_scenario_revisions is 'Неизменяемые редакции сценария финансирования';
comment on table financing_scenario_publications is 'Факты передачи и подтверждения редакции клиентом';
comment on column financing_scenario_publications.document_ids is 'Идентификаторы общих документов в переданной редакции';
comment on column financing_scenario_publications.handed_terms is 'Условия заявки на момент передачи клиенту';
