-- Run only after reverse import, with stopped Credit and Documents writers.
begin;
do $$ begin
 if exists(select 1 from document_ownership where owner <> 'LOCAL') then
  raise exception 'Reverse cutover and checksum verification required';
 end if;
end $$;
alter table message_attachments drop constraint message_attachments_business_reference_fkey;
alter table application_chat_upload_keys drop constraint application_chat_upload_keys_business_reference_fkey;
alter table application_chat_audit drop constraint application_chat_audit_business_reference_fkey;
alter table message_attachments add foreign key(document_id,application_id) references documents(id,application_id);
alter table application_chat_upload_keys add foreign key(document_id,application_id) references documents(id,application_id);
alter table application_chat_audit add foreign key(document_id,application_id) references documents(id,application_id);
drop trigger documents_owner_fence on documents;
drop trigger artifacts_owner_fence on generated_artifacts;
drop function fence_local_documents();
drop function assert_local_documents_writer();
-- Frozen terms are retained for audit and retry compatibility.
commit;
