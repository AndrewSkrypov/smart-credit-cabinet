comment on table application_chat_audit is 'Отдельный аудит действий с перепиской';
comment on column application_chat_audit.action is 'Вид действия с сообщением или файлом';
comment on column application_chat_audit.actor_role is 'Роль автора действия';

comment on table application_chat_rate_buckets is 'Окна ограничения частоты сообщений';
comment on column application_chat_rate_buckets.window_start is 'Начало окна ограничения';
comment on column application_chat_rate_buckets.hits is 'Число действий в окне';

comment on table application_chat_read_cursors is 'Прочитанная последовательность для каждой стороны';
comment on column application_chat_read_cursors.side is 'Сторона клиента или банка';
comment on column application_chat_read_cursors.last_read_seq is 'Последнее прочитанное сообщение';

comment on table application_chat_state is 'Последовательность сообщений в заявке';
comment on column application_chat_state.last_seq is 'Последний присвоенный номер сообщения';

comment on table application_chat_upload_keys is 'Идемпотентность загрузок в чат';
comment on column application_chat_upload_keys.idempotency_key is 'Ключ повторной загрузки';
comment on column application_chat_upload_keys.payload_hash is 'Хеш содержимого загрузки';

comment on table application_events is 'Неизменяемый журнал событий и переходов заявки';
comment on column application_events.event is 'Бизнес-событие перехода';
comment on column application_events.to_status is 'Состояние после перехода';
comment on column application_events.seq is 'Порядок событий внутри журнала';
comment on column application_events.payload is 'Дополнительные факты перехода';

comment on table application_messages is 'Сообщения участников по заявке';
comment on column application_messages.author_role is 'Сохранённая роль автора';
comment on column application_messages.side is 'Сторона компании или банка';
comment on column application_messages.message_text is 'Текст, видимый участникам заявки';
comment on column application_messages.idempotency_key is 'Ключ повторной отправки сообщения';

comment on table applications is 'Агрегаты кредитных заявок и их текущие состояния';
comment on column applications.company_id is 'Компания заёмщика';
comment on column applications.status is 'Текущее состояние конечного автомата';
comment on column applications.version is 'Ожидаемая версия при конкурентном изменении';
comment on column applications.product_version is 'Версия выбранного продукта';
comment on column applications.product_catalog_snapshot is 'Версии условий на момент подачи';
comment on column applications.bureau_consent is 'Согласие клиента на запрос БКИ';
comment on column applications.agent_id is 'Агент, инициировавший заявку';
comment on column applications.agent_commission_status is 'Состояние начисления комиссии';
comment on column applications.pre_approved_offer_id is 'Связь с предложением до отправки заявки';

comment on table contract_text_snapshots is 'Неизменяемый текст согласуемого договора';
comment on column contract_text_snapshots.template_version is 'Редакция шаблона договора';
comment on column contract_text_snapshots.sha256 is 'Контрольная сумма текста';
comment on column contract_text_snapshots.offer_id is 'Предложение, для которого подготовлен договор';

comment on table decisions is 'Факторы оценки, рекомендация правил и принятое решение';
comment on column decisions.rule_set_version is 'Версия правил при вычислении';
comment on column decisions.outcome is 'Результат решения';
comment on column decisions.inputs is 'Снимок входных фактов';
comment on column decisions.recommendation is 'Рекомендация перед ручным решением';

comment on table delivery_outbox is 'Публикация уведомлений после бизнес-транзакции';
comment on column delivery_outbox.event_key is 'Ключ логической доставки';
comment on column delivery_outbox.claim_token is 'Маркер текущей аренды';
comment on column delivery_outbox.state is 'Состояние обработки';
comment on column delivery_outbox.trace_parent is 'Сохранённый родительский контекст W3C';

comment on table documents is 'Метаданные файлов заявки и переписки';
comment on column documents.storage_backend is 'Фактический backend хранения';
comment on column documents.stored_name is 'Внутренний ключ файла';
comment on column documents.context is 'Документ заявки или вложение переписки';
comment on column documents.checksum_sha256 is 'Контрольная сумма содержимого';
comment on column documents.deleted_at is 'Отметка логического удаления';

comment on table enrichment_results is 'Полученные ответы источников кредитного конвейера';
comment on column enrichment_results.source is 'Источник данных';
comment on column enrichment_results.payload is 'Сохранённый ответ источника';
comment on column enrichment_results.fetched_at is 'Время получения';

comment on table generated_artifact_notices is 'Дедупликация уведомлений о готовых файлах договора';
comment on column generated_artifact_notices.input_hash is 'Хеш снимка параметров формирования';
comment on column generated_artifact_notices.queued_at is 'Момент постановки уведомления';

comment on table generated_artifacts is 'Очередь и метаданные сформированных файлов';
comment on column generated_artifacts.input_hash is 'Хеш данных и версии шаблона';
comment on column generated_artifacts.phase is 'Предварительный или итоговый экземпляр';
comment on column generated_artifacts.status is 'Текущее состояние формирования';
comment on column generated_artifacts.checksum is 'Контрольная сумма результата';

comment on table idempotency_keys is 'Повторно используемые ключи операций заявки';
comment on column idempotency_keys.idempotency_key is 'Ключ идентичной операции';
comment on column idempotency_keys.operation is 'Тип операции';
comment on column idempotency_keys.application_id is 'Связанный агрегат';

comment on table info_requests is 'Запросы банка на документы и пояснения';
comment on column info_requests.items is 'Перечень запрошенных материалов';
comment on column info_requests.deadline is 'Крайний срок ответа';
comment on column info_requests.resolved_at is 'Момент закрытия запроса';

comment on table loans is 'Результат выдачи кредита в синтетической АБС';
comment on column loans.contract_number is 'Номер заключённого договора';
comment on column loans.disbursed_at is 'Момент подтверждённой выдачи';
comment on column loans.loan_account is 'Синтетический счёт выданного кредита';

comment on table message_attachments is 'Файлы, связанные с сообщениями заявки';
comment on column message_attachments.message_id is 'Сообщение с вложением';
comment on column message_attachments.document_id is 'Файл той же заявки';

comment on table offers is 'Версии предложенных банком условий';
comment on column offers.decision_id is 'Решение, выпустившее предложение';
comment on column offers.product_version is 'Зафиксированная версия продукта';
comment on column offers.valid_until is 'Крайний срок ответа клиента';
comment on column offers.status is 'Состояние предложения';

comment on table pipeline_jobs is 'Очередь обработки шагов кредитного конвейера';
comment on column pipeline_jobs.step is 'Операция конвейера';
comment on column pipeline_jobs.run_at is 'Не ранее этого момента';
comment on column pipeline_jobs.locked_until is 'Окончание аренды исполнителем';
comment on column pipeline_jobs.status is 'Текущее состояние задачи';

comment on table pre_approved_offers is 'Предодобренные предложения для конкретной компании';
comment on column pre_approved_offers.max_amount is 'Верхняя граница суммы предложения';
comment on column pre_approved_offers.valid_until is 'Время окончания предложения';
comment on column pre_approved_offers.status is 'Текущее состояние предложения';
comment on column pre_approved_offers.application_id is 'Черновик, использовавший предложение';

comment on table product_versions is 'Неизменяемые редакции банковских продуктов';
comment on column product_versions.version is 'Номер редакции продукта';
comment on column product_versions.lifecycle_status is 'Состояние редакции в каталоге';
comment on column product_versions.effective_from is 'Начало действия редакции';
comment on column product_versions.effective_to is 'Конец действия редакции';
comment on column product_versions.agent_commission_rate_bps is 'Комиссия агента в базисных пунктах';

comment on table products is 'Текущие указатели каталога продуктов';
comment on column products.code is 'Устойчивый код продукта';
comment on column products.current_version is 'Активная редакция условий';

comment on table rule_sets is 'Неизменяемые версии параметров кредитной политики';
comment on column rule_sets.version is 'Номер редакции правил';
comment on column rule_sets.params is 'Числовые параметры скоринга и ограничений';

comment on table signing_sessions is 'Сеансы подтверждения подписи по предложению';
comment on column signing_sessions.offer_id is 'Предложение, переданное на подписание';
comment on column signing_sessions.expires_at is 'Крайний срок действия кода';
comment on column signing_sessions.contract_snapshot_id is 'Версия подписываемого текста';
comment on column signing_sessions.signature_id is 'Идентификатор результата подписи';

-- The servicing migration is supplied by the agent-channel branch.
do $servicing$
begin
    if to_regclass('public.loan_accounts') is not null then
        comment on table loan_accounts is 'Договоры, остатки и состояние обслуживания кредита';
    end if;
    if to_regclass('public.loan_payments') is not null then
        comment on table loan_payments is 'График и зарегистрированные платежи по выданному кредиту';
    end if;
    if to_regclass('public.loan_tranches') is not null then
        comment on table loan_tranches is 'Выборки средств в пределах одобренного лимита';
    end if;
end
$servicing$;
