create table document_ownership (
 singleton boolean primary key default true check(singleton),
 owner varchar(10) not null check(owner in ('LOCAL','FROZEN','REMOTE')),
 epoch bigint not null check(epoch>0)
);
insert into document_ownership values(true,'LOCAL',1);
-- The app may lock/check ownership through this function but cannot update it.
create function assert_local_documents_writer() returns boolean
language plpgsql security definer set search_path=pg_catalog as $$
declare active_owner text;
begin
 select owner into active_owner from public.document_ownership where singleton for share;
 if active_owner is distinct from 'LOCAL' then
  raise exception 'Document ownership fenced' using errcode='55000';
 end if;
 return true;
end $$;
revoke all on function assert_local_documents_writer() from public;
create function fence_local_documents() returns trigger language plpgsql as $$
begin
 perform public.assert_local_documents_writer();
 return case when TG_OP='DELETE' then old else new end;
end $$;
create trigger documents_owner_fence before insert or update or delete on documents for each row execute function fence_local_documents();
create trigger artifacts_owner_fence before insert or update or delete on generated_artifacts for each row execute function fence_local_documents();
-- Credit retains frozen terms and delivery intent, not authoritative file metadata.
create table document_artifact_inputs (
 application_id uuid not null references applications(id), offer_id uuid not null references offers(id),
 phase varchar(10) not null, input_hash char(64) not null, input_json jsonb not null,
 created_at timestamptz not null default now(), delivered_at timestamptz,
 primary key(application_id,offer_id,phase,input_hash)
);
create unique index document_final_input_immutable on document_artifact_inputs(application_id,offer_id) where phase='FINAL';
create function preserve_document_artifact_input() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Document input is immutable'; end if;
 if (new.application_id,new.offer_id,new.phase,new.input_hash,new.input_json::text,new.created_at) is distinct from
    (old.application_id,old.offer_id,old.phase,old.input_hash,old.input_json::text,old.created_at) then
  raise exception 'Document input is immutable';
 end if;
 if old.delivered_at is not null and new.delivered_at is distinct from old.delivered_at then
  raise exception 'Document delivery receipt is immutable';
 end if;
 return new;
end $$;
create trigger document_artifact_inputs_immutable before update or delete on document_artifact_inputs for each row execute function preserve_document_artifact_input();
-- Existing FK assumes local documents. Application/chat FKs and immutable attachment
-- triggers remain; Credit validates document scope before inserting each reference.
alter table message_attachments drop constraint message_attachments_document_id_application_id_fkey;
alter table application_chat_upload_keys drop constraint application_chat_upload_keys_document_id_application_id_fkey;
alter table application_chat_audit drop constraint application_chat_audit_document_id_application_id_fkey;

-- Credit owns only the committed application association, never remote file metadata.
-- The upload RPC may finish before this transaction: unreferenced remote rows remain invisible.
create table document_references (
 document_id uuid primary key, application_id uuid not null references applications(id),
 created_at timestamptz not null default now()
);
insert into document_references(document_id,application_id) select id,application_id from documents;
create function record_local_document_reference() returns trigger language plpgsql as $$
begin
 insert into document_references(document_id,application_id) values(new.id,new.application_id) on conflict do nothing;
 return new;
end $$;
create trigger documents_business_reference after insert on documents for each row execute function record_local_document_reference();

-- Expected Documents outages do not consume business failure attempts.
alter table pipeline_jobs add column documents_outages integer not null default 0 check(documents_outages between 0 and 7);
