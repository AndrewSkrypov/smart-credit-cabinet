comment on table agent_clients is null;
comment on column agent_clients.agent_user_id is null;
comment on column agent_clients.company_id is null;
comment on column agent_clients.status is null;
comment on column agent_clients.revoked_at is null;

comment on table companies is null;
comment on column companies.inn is null;

comment on table company_invitations is null;
comment on column company_invitations.company_id is null;
comment on column company_invitations.token_hash is null;
comment on column company_invitations.expires_at is null;
comment on column company_invitations.used_at is null;
comment on column company_invitations.can_sign is null;

comment on table delivery_outbox is null;
comment on column delivery_outbox.event_key is null;
comment on column delivery_outbox.state is null;
comment on column delivery_outbox.claim_token is null;
comment on column delivery_outbox.trace_parent is null;

comment on table identity_audit is null;
comment on column identity_audit.actor_id is null;
comment on column identity_audit.action is null;
comment on column identity_audit.request_id is null;

comment on table identity_mutation_keys is null;
comment on column identity_mutation_keys.mutation_key is null;
comment on column identity_mutation_keys.payload_hash is null;
comment on column identity_mutation_keys.result_id is null;

comment on table identity_rate_buckets is null;
comment on column identity_rate_buckets.bucket_key is null;
comment on column identity_rate_buckets.attempts is null;

comment on table password_reset_requests is null;
comment on column password_reset_requests.token_hash is null;
comment on column password_reset_requests.credentials_version is null;
comment on column password_reset_requests.expires_at is null;

comment on table personal_data_consents is null;
comment on column personal_data_consents.document is null;
comment on column personal_data_consents.version is null;
comment on column personal_data_consents.accepted_at is null;

comment on table users is null;
comment on column users.role is null;
comment on column users.company_id is null;
comment on column users.can_sign is null;
comment on column users.password_hash is null;
comment on column users.credentials_version is null;
