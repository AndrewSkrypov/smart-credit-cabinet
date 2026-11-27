create table credit_assessment_cycle (
    cycle_id uuid primary key references pipeline_jobs(id),
    application_id uuid not null references applications(id),
    application_version bigint not null check (application_version >= 0),
    stream varchar(65),
    request jsonb,
    result jsonb,
    accept_until timestamptz,
    received_at timestamptz,
    score_version bigint,
    closed_at timestamptz,
    check ((stream is null) = (request is null)),
    check ((request is null) = (accept_until is null)),
    check ((result is null) = (received_at is null)),
    check (score_version is null or result is not null)
);
create unique index credit_assessment_open_score on credit_assessment_cycle(application_id)
    where score_version is not null and closed_at is null;

insert into credit_assessment_cycle(cycle_id, application_id, application_version)
select j.id, a.id, a.version
from pipeline_jobs j join applications a on a.id = j.application_id
where j.step = 'ENRICHMENT' and j.status in ('PENDING', 'RUNNING') and a.status = 'ENRICHMENT';

create table assessment_exchange_outbox (
    stream varchar(65) not null,
    cycle_id uuid not null,
    application_id uuid not null,
    payload jsonb not null,
    attempts integer not null default 0 check (attempts >= 0),
    available_at timestamptz not null default now(),
    sent_at timestamptz,
    claim_token uuid,
    locked_until timestamptz,
    last_error varchar(120),
    primary key (stream, cycle_id),
    check ((claim_token is null) = (locked_until is null))
);
create index assessment_exchange_outbox_due on assessment_exchange_outbox (stream, available_at) where sent_at is null;

comment on table credit_assessment_cycle is 'Закреплённый цикл локальной или удалённой оценки заявки';
comment on column credit_assessment_cycle.cycle_id is 'Идентификатор исходной задачи оценки';
comment on column credit_assessment_cycle.application_version is 'Версия заявки при закреплении входных данных';
comment on column credit_assessment_cycle.stream is 'Поколение Kafka; NULL сохраняет локальный исполнитель старого цикла';
comment on column credit_assessment_cycle.request is 'Неизменяемый запрос с версиями фактов, правил, каталога и согласий';
comment on column credit_assessment_cycle.result is 'Принятый результат того же цикла и поколения';
comment on column credit_assessment_cycle.accept_until is 'Крайний срок приёма результата удалённой оценки';
comment on column credit_assessment_cycle.score_version is 'Версия заявки, для которой разрешено применение рекомендации';
comment on column credit_assessment_cycle.closed_at is 'Момент закрытия цикла без повторного применения результата';
comment on table assessment_exchange_outbox is 'Транзакционная доставка запросов оценки с повтором и арендой';
comment on column assessment_exchange_outbox.stream is 'Поколение обмена, закреплённое в запросе';
comment on column assessment_exchange_outbox.payload is 'Неизменяемое тело контракта запроса оценки';
comment on column assessment_exchange_outbox.claim_token is 'Уникальный маркер текущей аренды публикации';
comment on column assessment_exchange_outbox.locked_until is 'Срок действия текущей аренды публикации';
comment on column assessment_exchange_outbox.sent_at is 'Подтверждение доставки брокером Kafka';
