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

comment on table document_ownership is 'Единственный владелец документов и эпоха переключения LOCAL/FROZEN/REMOTE';
comment on column document_ownership.owner is 'Административная ограда записи и устаревшего чтения';
comment on column document_ownership.epoch is 'Монотонная эпоха, проверяемая удалённым портом';
comment on table document_artifact_inputs is 'Неизменяемые бизнес-снимки Credit и намерения доставки артефактов';
comment on column document_artifact_inputs.input_hash is 'SHA-256 точного канонического входа рендера';
comment on column document_artifact_inputs.input_json is 'Зафиксированные условия, график и подписываемый текст с точным масштабом чисел';
comment on column document_artifact_inputs.delivered_at is 'Неизменяемая квитанция подтверждённой передачи задания';
comment on table document_references is 'Подтверждённые транзакцией Credit бизнес-связи документов с заявками; без файловых метаданных';
comment on column document_references.document_id is 'Идентификатор единственного документа в Documents либо историческом LOCAL';
comment on column document_references.application_id is 'Заявка, имеющая подтверждённую ссылку на документ';
comment on column pipeline_jobs.documents_outages is 'Счётчик ожидаемых сбоев Documents для ограниченной задержки без расходования бизнес-попыток';

-- Business removal is committed with the journal. Remote metadata receives it asynchronously.
create table document_deletion_intents (
 document_id uuid primary key references document_references(document_id),
 application_id uuid not null references applications(id), deleted_at timestamptz not null,
 delivered_at timestamptz, attempts integer not null default 0,
 run_at timestamptz not null default now()
);
comment on table document_deletion_intents is 'Подтверждённые удаления Credit и долговечная доставка tombstone единственному Documents owner';
comment on column document_deletion_intents.deleted_at is 'Время бизнес-удаления, фиксируемое вместе с журналом и сохраняемое при повторе RPC';
comment on column document_deletion_intents.delivered_at is 'Квитанция идемпотентной доставки удаления в Documents';

-- Historical identities may contain several frozen hashes per offer; do not weaken
-- the current FINAL input uniqueness to authorize their immutable historical files.
create table document_artifact_references (
 application_id uuid not null references applications(id), offer_id uuid not null references offers(id),
 phase varchar(10) not null, input_hash char(64) not null, input_json jsonb not null,
 primary key(application_id,offer_id,phase,input_hash)
);
insert into document_artifact_references(application_id,offer_id,phase,input_hash,input_json)
 select distinct application_id,offer_id,phase,input_hash,input_json from generated_artifacts;
create function preserve_document_artifact_reference() returns trigger language plpgsql as $$
begin raise exception 'Artifact business reference is immutable'; end $$;
create trigger document_artifact_references_immutable before update or delete on document_artifact_references for each row execute function preserve_document_artifact_reference();
comment on table document_artifact_references is 'Неизменяемая бизнес-авторизация исторических и текущих артефактов по точному frozen input';
comment on column document_artifact_references.input_json is 'Исходный бизнес-снимок для исторического чтения и проверки переноса без изменения текущего FINAL intent';

create function record_artifact_business_reference() returns trigger language plpgsql as $$
begin
 insert into document_artifact_references(application_id,offer_id,phase,input_hash,input_json)
 values(new.application_id,new.offer_id,new.phase,new.input_hash,new.input_json) on conflict do nothing;
 return new;
end $$;
create trigger document_artifact_input_reference after insert on document_artifact_inputs for each row execute function record_artifact_business_reference();
-- New LOCAL legacy artifacts remain authorized if they are created before cutover.
create trigger local_artifact_business_reference after insert on generated_artifacts for each row execute function record_artifact_business_reference();
create function preserve_document_deletion_intent() returns trigger language plpgsql as $$
begin
 if TG_OP='DELETE' or (new.document_id,new.application_id,new.deleted_at) is distinct from (old.document_id,old.application_id,old.deleted_at) then
  raise exception 'Document removal identity is immutable';
 end if;
 if old.delivered_at is not null and new.delivered_at is distinct from old.delivered_at then
  raise exception 'Document removal receipt is immutable';
 end if;
 return new;
end $$;
create trigger document_deletion_intents_immutable before update or delete on document_deletion_intents for each row execute function preserve_document_deletion_intent();

alter table pipeline_jobs add column business_failures integer not null default 0 check(business_failures>=0);
comment on column pipeline_jobs.business_failures is 'Бюджет неожиданных бизнес-ошибок, независимый от монотонных попыток захвата и ожидаемых сбоев портов';
