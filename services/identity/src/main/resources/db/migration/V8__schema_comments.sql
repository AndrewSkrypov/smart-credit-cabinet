comment on table agent_clients is 'Действующие и отозванные поручения агента на сопровождение компаний';
comment on column agent_clients.agent_user_id is 'Учётная запись агента';
comment on column agent_clients.company_id is 'Компания под действующим поручением';
comment on column agent_clients.status is 'Состояние ACTIVE или REVOKED';
comment on column agent_clients.revoked_at is 'Момент прекращения доступа';

comment on table companies is 'Синтетические компании и ИП в кабинете';
comment on column companies.inn is 'Налоговый идентификатор компании';

comment on table company_invitations is 'Одноразовые приглашения сотрудников компании';
comment on column company_invitations.company_id is 'Компания приглашающего';
comment on column company_invitations.token_hash is 'Хеш одноразового токена';
comment on column company_invitations.expires_at is 'Крайний срок действия ссылки';
comment on column company_invitations.used_at is 'Момент использования приглашения';
comment on column company_invitations.can_sign is 'Делегированное право подписи';

comment on table delivery_outbox is 'Очередь доставки событий identity с повтором и дедупликацией';
comment on column delivery_outbox.event_key is 'Неизменяемый ключ логической доставки';
comment on column delivery_outbox.state is 'Состояние доставки';
comment on column delivery_outbox.claim_token is 'Маркер актуального владения задачей';
comment on column delivery_outbox.trace_parent is 'Сохранённый родительский контекст W3C';

comment on table identity_audit is 'История действий с доступом пользователей и компаний';
comment on column identity_audit.actor_id is 'Пользователь, инициировавший действие';
comment on column identity_audit.action is 'Тип события аудита';
comment on column identity_audit.request_id is 'Идентификатор для поиска запроса в логах';

comment on table identity_mutation_keys is 'Ключи идемпотентности изменения состава компании';
comment on column identity_mutation_keys.mutation_key is 'Повторно применяемый ключ запроса';
comment on column identity_mutation_keys.payload_hash is 'Хеш значимых параметров запроса';
comment on column identity_mutation_keys.result_id is 'Результат первой успешной операции';

comment on table identity_rate_buckets is 'Окна ограничений чувствительных операций identity';
comment on column identity_rate_buckets.bucket_key is 'Идентификатор корзины ограничения';
comment on column identity_rate_buckets.attempts is 'Число операций в текущем окне';

comment on table password_reset_requests is 'Заявки на восстановление доступа по одноразовой ссылке';
comment on column password_reset_requests.token_hash is 'Хеш токена восстановления';
comment on column password_reset_requests.credentials_version is 'Версия полномочий при выдаче ссылки';
comment on column password_reset_requests.expires_at is 'Крайний срок использования';

comment on table personal_data_consents is 'История согласий на обработку персональных данных';
comment on column personal_data_consents.document is 'Тип принятого документа';
comment on column personal_data_consents.version is 'Редакция документа';
comment on column personal_data_consents.accepted_at is 'Момент принятия согласия';

comment on table users is 'Учётные записи пользователей и полномочия';
comment on column users.role is 'Роль доступа к кабинету';
comment on column users.company_id is 'Компания клиента, отсутствует у агента и сотрудников банка';
comment on column users.can_sign is 'Разрешение подписывать от имени компании';
comment on column users.password_hash is 'Хеш пароля BCrypt';
comment on column users.credentials_version is 'Версия полномочий для отзыва JWT';
