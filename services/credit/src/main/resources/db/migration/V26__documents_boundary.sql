create table document_ownership (
 singleton boolean primary key default true check(singleton),
 owner varchar(10) not null check(owner in ('LOCAL','FROZEN','REMOTE')),
 epoch bigint not null check(epoch>0)
);
insert into document_ownership values(true,'LOCAL',1);
create function fence_local_documents() returns trigger language plpgsql as $$
begin
 perform 1 from document_ownership where singleton and owner='LOCAL' for share;
 if not found then raise exception 'Document ownership fenced' using errcode='55000'; end if;
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
-- Existing FK assumes local documents. Application/chat FKs and immutable attachment
-- triggers remain; Credit validates document scope before inserting each reference.
alter table message_attachments drop constraint message_attachments_document_id_application_id_fkey;
alter table application_chat_upload_keys drop constraint application_chat_upload_keys_document_id_application_id_fkey;
alter table application_chat_audit drop constraint application_chat_audit_document_id_application_id_fkey;
