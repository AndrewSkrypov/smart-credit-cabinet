create table application_status_outbox (
    like delivery_outbox including defaults including constraints including indexes,
    check (channel = 'application-status')
);

create index idx_application_status_outbox_open
    on application_status_outbox(state, created_at) where state in ('PENDING', 'RUNNING', 'FAILED');

comment on table application_status_outbox is 'Долговечная доставка событий статуса заявки в Kafka';
