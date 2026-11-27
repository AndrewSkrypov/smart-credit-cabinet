begin;
revoke all on all tables in schema public from public, assessment_app;
grant select, insert, update on assessment_execution, assessment_exchange_outbox to assessment_app;
commit;
