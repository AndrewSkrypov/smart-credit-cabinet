revoke all on document_ownership from credit_app;
grant select on document_ownership to credit_app;
grant execute on function assert_local_documents_writer() to credit_app;
