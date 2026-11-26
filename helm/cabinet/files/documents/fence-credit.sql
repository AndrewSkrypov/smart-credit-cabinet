-- The trigger locks the row, which otherwise requires UPDATE privilege for the runtime role.
alter function public.fence_local_documents() security definer set search_path = pg_catalog, public, pg_temp;
revoke all on function public.fence_local_documents() from public, credit_app;
