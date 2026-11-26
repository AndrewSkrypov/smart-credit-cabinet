-- Run only after reverse import, with stopped Credit and Documents writers.
do $$ begin
 if exists(select 1 from document_ownership where owner <> 'LOCAL') then
  raise exception 'Reverse cutover and checksum verification required';
 end if;
end $$;
alter table message_attachments add foreign key(document_id,application_id) references documents(id,application_id);
alter table application_chat_upload_keys add foreign key(document_id,application_id) references documents(id,application_id);
alter table application_chat_audit add foreign key(document_id,application_id) references documents(id,application_id);
drop trigger documents_owner_fence on documents;
drop trigger artifacts_owner_fence on generated_artifacts;
drop function fence_local_documents();
-- Frozen terms are retained for audit and retry compatibility.
