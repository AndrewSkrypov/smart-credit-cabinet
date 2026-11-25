comment on table application_chat_audit is null;
comment on column application_chat_audit.action is null;
comment on column application_chat_audit.actor_role is null;

comment on table application_chat_rate_buckets is null;
comment on column application_chat_rate_buckets.window_start is null;
comment on column application_chat_rate_buckets.hits is null;

comment on table application_chat_read_cursors is null;
comment on column application_chat_read_cursors.side is null;
comment on column application_chat_read_cursors.last_read_seq is null;

comment on table application_chat_state is null;
comment on column application_chat_state.last_seq is null;

comment on table application_chat_upload_keys is null;
comment on column application_chat_upload_keys.idempotency_key is null;
comment on column application_chat_upload_keys.payload_hash is null;

comment on table application_events is null;
comment on column application_events.event is null;
comment on column application_events.to_status is null;
comment on column application_events.seq is null;
comment on column application_events.payload is null;

comment on table application_messages is null;
comment on column application_messages.author_role is null;
comment on column application_messages.side is null;
comment on column application_messages.message_text is null;
comment on column application_messages.idempotency_key is null;

comment on table applications is null;
comment on column applications.company_id is null;
comment on column applications.status is null;
comment on column applications.version is null;
comment on column applications.product_version is null;
comment on column applications.product_catalog_snapshot is null;
comment on column applications.bureau_consent is null;
comment on column applications.agent_id is null;
comment on column applications.agent_commission_status is null;
comment on column applications.pre_approved_offer_id is null;

comment on table contract_text_snapshots is null;
comment on column contract_text_snapshots.template_version is null;
comment on column contract_text_snapshots.sha256 is null;
comment on column contract_text_snapshots.offer_id is null;

comment on table decisions is null;
comment on column decisions.rule_set_version is null;
comment on column decisions.outcome is null;
comment on column decisions.inputs is null;
comment on column decisions.recommendation is null;

comment on table delivery_outbox is null;
comment on column delivery_outbox.event_key is null;
comment on column delivery_outbox.claim_token is null;
comment on column delivery_outbox.state is null;
comment on column delivery_outbox.trace_parent is null;

comment on table documents is null;
comment on column documents.storage_backend is null;
comment on column documents.stored_name is null;
comment on column documents.context is null;
comment on column documents.checksum_sha256 is null;
comment on column documents.deleted_at is null;

comment on table enrichment_results is null;
comment on column enrichment_results.source is null;
comment on column enrichment_results.payload is null;
comment on column enrichment_results.fetched_at is null;

comment on table generated_artifact_notices is null;
comment on column generated_artifact_notices.input_hash is null;
comment on column generated_artifact_notices.queued_at is null;

comment on table generated_artifacts is null;
comment on column generated_artifacts.input_hash is null;
comment on column generated_artifacts.phase is null;
comment on column generated_artifacts.status is null;
comment on column generated_artifacts.checksum is null;

comment on table idempotency_keys is null;
comment on column idempotency_keys.idempotency_key is null;
comment on column idempotency_keys.operation is null;
comment on column idempotency_keys.application_id is null;

comment on table info_requests is null;
comment on column info_requests.items is null;
comment on column info_requests.deadline is null;
comment on column info_requests.resolved_at is null;

comment on table loans is null;
comment on column loans.contract_number is null;
comment on column loans.disbursed_at is null;
comment on column loans.loan_account is null;

comment on table message_attachments is null;
comment on column message_attachments.message_id is null;
comment on column message_attachments.document_id is null;

comment on table offers is null;
comment on column offers.decision_id is null;
comment on column offers.product_version is null;
comment on column offers.valid_until is null;
comment on column offers.status is null;

comment on table pipeline_jobs is null;
comment on column pipeline_jobs.step is null;
comment on column pipeline_jobs.run_at is null;
comment on column pipeline_jobs.locked_until is null;
comment on column pipeline_jobs.status is null;

comment on table pre_approved_offers is null;
comment on column pre_approved_offers.max_amount is null;
comment on column pre_approved_offers.valid_until is null;
comment on column pre_approved_offers.status is null;
comment on column pre_approved_offers.application_id is null;

comment on table product_versions is null;
comment on column product_versions.version is null;
comment on column product_versions.lifecycle_status is null;
comment on column product_versions.effective_from is null;
comment on column product_versions.effective_to is null;
comment on column product_versions.agent_commission_rate_bps is null;

comment on table products is null;
comment on column products.code is null;
comment on column products.current_version is null;

comment on table rule_sets is null;
comment on column rule_sets.version is null;
comment on column rule_sets.params is null;

comment on table signing_sessions is null;
comment on column signing_sessions.offer_id is null;
comment on column signing_sessions.expires_at is null;
comment on column signing_sessions.contract_snapshot_id is null;
comment on column signing_sessions.signature_id is null;

-- The servicing migration is supplied by the agent-channel branch.
do $servicing$
begin
    if to_regclass('public.loan_accounts') is not null then
        comment on table loan_accounts is null;
    end if;
    if to_regclass('public.loan_payments') is not null then
        comment on table loan_payments is null;
    end if;
    if to_regclass('public.loan_tranches') is not null then
        comment on table loan_tranches is null;
    end if;
end
$servicing$;
