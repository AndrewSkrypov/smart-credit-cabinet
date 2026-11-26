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
create unique index document_final_input_immutable on document_artifact_inputs(application_id,offer_id) where phase='FINAL';
create function preserve_document_artifact_input() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' then raise exception 'Document input is immutable'; end if;
 if (new.application_id,new.offer_id,new.phase,new.input_hash,new.input_json,new.created_at) is distinct from
    (old.application_id,old.offer_id,old.phase,old.input_hash,old.input_json,old.created_at) then
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
