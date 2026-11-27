-- Reapply after every baseline grant reconciliation and in the ownership-change transaction.
revoke all on document_ownership from credit_app;
grant select on document_ownership to credit_app;
do $$
begin
 if to_regprocedure('public.assert_local_documents_writer()') is not null then
  grant execute on function assert_local_documents_writer() to credit_app;
 end if;
 if (select owner from public.document_ownership where singleton) = 'LOCAL' then
  grant select,insert,update,delete on public.documents,public.generated_artifacts to credit_app;
 else
  revoke all on public.documents,public.generated_artifacts from credit_app;
 end if;
end $$;
