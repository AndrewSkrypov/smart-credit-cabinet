-- Pre-upgrade rows cannot be assigned to a configured Kafka generation safely.
alter table application_status_outbox
    add column stream_generation text check (stream_generation <> '');

create index idx_application_status_outbox_generation
    on application_status_outbox(stream_generation, state, next_attempt_at)
    where state in ('PENDING', 'RUNNING', 'FAILED');

comment on column application_status_outbox.stream_generation is
    'Immutable Kafka topic for this event. NULL legacy rows are excluded from all generations.';
