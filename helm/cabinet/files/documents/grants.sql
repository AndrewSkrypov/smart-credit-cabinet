-- Run after the common baseline grants. Runtime identities are never schema owners.
revoke all on document_ownership from documents_app;
grant select on document_ownership to documents_app;
revoke update,delete on document_application_scopes from documents_app;
revoke update,delete on artifact_outbox from documents_app;

grant execute on function assert_documents_writer(bigint) to documents_app;
