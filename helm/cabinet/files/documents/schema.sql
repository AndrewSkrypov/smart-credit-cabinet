create table document_ownership (
 singleton boolean primary key default true check(singleton),
 owner varchar(20) not null check(owner in ('FROZEN','DOCUMENTS')),
 epoch bigint not null check(epoch > 0)
);
insert into document_ownership values(true,'FROZEN',1);
-- Runtime keeps SELECT-only fence access; locking requires the schema owner.
create function assert_documents_writer(expected_epoch bigint) returns boolean
language plpgsql security definer set search_path=pg_catalog as $$
declare active_owner text; active_epoch bigint;
begin
 select owner,epoch into active_owner,active_epoch from public.document_ownership where singleton for share;
 if active_owner is distinct from 'DOCUMENTS' or expected_epoch is null or active_epoch is distinct from expected_epoch then
  raise exception 'Documents owner is fenced' using errcode='55000';
 end if;
 return true;
end $$;
revoke all on function assert_documents_writer(bigint) from public;
create table document_application_scopes (application_id uuid primary key, company_id uuid not null);
create table document_records (
 id uuid primary key, company_id uuid not null, application_id uuid not null,
 context varchar(12) not null check(context in ('BUSINESS','CHAT')),
 metadata jsonb not null, created_at timestamptz not null default now()
);
create index document_records_application on document_records(company_id,application_id);
create table artifact_records (
 id uuid primary key, company_id uuid not null, application_id uuid not null,
 offer_id uuid not null, kind varchar(30) not null, phase varchar(10) not null,
 template_version integer not null, input_hash char(64) not null,
 metadata jsonb not null, status varchar(10) not null default 'PENDING',
 attempts integer not null default 0, next_attempt_at timestamptz not null default now(),
 lease_token uuid, lease_until timestamptz,
 unique(application_id,offer_id,kind,phase,template_version,input_hash)
);
create table artifact_outbox (
 artifact_id uuid primary key references artifact_records(id),
 occurred_at timestamptz not null default now()
);
