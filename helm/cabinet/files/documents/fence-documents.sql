-- SELECT ... FOR SHARE needs UPDATE on one column. NOT NULL + CHECK(singleton)
-- + the primary key prevent the app from changing this sentinel or ownership.
grant update(singleton) on public.document_ownership to documents_app;
revoke all on table public.documents_delivery_schema from documents_app;
