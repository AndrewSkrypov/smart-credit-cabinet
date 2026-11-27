begin;
revoke all on schema catalog_cutover from public;
revoke all on table catalog_cutover.authority from public;
revoke all on function catalog_cutover.lock_authority(), catalog_cutover.require_authority(),
    catalog_cutover.credit_replay_ready(), catalog_cutover.store_revisions(jsonb) from public;
grant usage on schema catalog_cutover to credit_app;
grant select on catalog_cutover.authority to credit_app;
grant execute on function catalog_cutover.lock_authority(), catalog_cutover.credit_replay_ready(),
    catalog_cutover.store_revisions(jsonb) to credit_app;
commit;
