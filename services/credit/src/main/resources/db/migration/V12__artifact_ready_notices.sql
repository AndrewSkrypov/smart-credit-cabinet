-- One readiness event per immutable final input, independently of rendering retries/replicas.
create table generated_artifact_notices (
    application_id uuid not null references applications(id),
    offer_id uuid not null references offers(id),
    template_version integer not null,
    input_hash varchar(64) not null,
    queued_at timestamptz not null,
    primary key(application_id,offer_id,template_version,input_hash)
);
