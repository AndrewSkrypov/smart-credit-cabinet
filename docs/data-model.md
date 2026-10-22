# Модель данных

Источник схемы — неизменённые миграции `services/*/src/main/resources/db/migration/`.
У каждого сервиса своя база. UUID компании, пользователя и получателя в другой базе
являются логическими ссылками, а не межсервисными внешними ключами PostgreSQL.

## Identity

```mermaid
erDiagram
    COMPANIES ||--o{ USERS : "сотрудники компании"
    USERS ||--o{ PERSONAL_DATA_CONSENTS : "версии согласия"
    COMPANIES {
        uuid id PK
        varchar inn UK
        varchar name
        timestamptz created_at
    }
    USERS {
        uuid id PK
        varchar email UK
        uuid company_id FK
        varchar role
        varchar status
        boolean can_sign
        varchar password_hash
        integer credentials_version
    }
    PERSONAL_DATA_CONSENTS {
        uuid id PK
        uuid user_id FK
        varchar document
        varchar version
        timestamptz accepted_at
    }
```

| Таблица | Назначение и ограничения |
| --- | --- |
| `companies` | ИНН уникален; имя и дата подключения. Организационная форма определяется по ИНН, отдельного столбца в этой таблице нет |
| `users` | Email уникален; роль, состояние, bcrypt-хэш, право подписи и версия учётных данных. Пользователь банка не имеет компании; `can_sign` требует компании |
| `personal_data_consents` | Кто, какую редакцию документа и когда принял. Отложенный FK позволяет сохранить пользователя и согласие одной транзакцией |

Триггер согласий запрещает `UPDATE`, чтобы ранее принятое согласие нельзя было
переписать. В текущей миграции он не запрещает `DELETE`; FK пользователя имеет
`ON DELETE CASCADE`. Публичного API удаления пользователя/согласия нет. Это граница
имеющейся защиты, а не гарантия неизменности против администратора БД.

## Credit

```mermaid
erDiagram
    PRODUCTS ||--o{ APPLICATIONS : "выбранный продукт"
    APPLICATIONS ||--o{ APPLICATION_EVENTS : "журнал"
    APPLICATIONS ||--o{ PIPELINE_JOBS : "шаги"
    APPLICATIONS ||--o{ IDEMPOTENCY_KEYS : "повторы запросов"
    APPLICATIONS ||--o{ DOCUMENTS : "файлы"
    APPLICATIONS ||--o{ ENRICHMENT_RESULTS : "ответы источников"
    APPLICATIONS ||--o{ DECISIONS : "оценки и решения"
    RULE_SETS ||--o{ DECISIONS : "версия политики"
    APPLICATIONS ||--o{ INFO_REQUESTS : "запросы сведений"
    DECISIONS ||--o{ OFFERS : "условия"
    PRODUCTS ||--o{ OFFERS : "предлагаемый продукт"
    APPLICATIONS ||--o{ OFFERS : "предложения"
    APPLICATIONS ||--o{ SIGNING_SESSIONS : "попытки подписи"
    OFFERS ||--o{ SIGNING_SESSIONS : "договор"
    APPLICATIONS ||--o| LOANS : "выданный кредит"
    PRODUCTS {
        varchar code PK
        bigint min_amount
        bigint max_amount
        numeric base_rate
        bigint auto_approval_limit
    }
    APPLICATIONS {
        uuid id PK
        bigint number UK
        uuid company_id
        varchar company_inn
        varchar product_code FK
        varchar status
        bigint version
        timestamptz submitted_at
    }
    APPLICATION_EVENTS {
        uuid id PK
        uuid application_id FK
        bigint seq
        varchar event
        varchar from_status
        varchar to_status
        jsonb payload
    }
    PIPELINE_JOBS {
        uuid id PK
        uuid application_id FK
        varchar step
        varchar status
        integer attempts
        timestamptz run_at
        timestamptz locked_until
    }
    IDEMPOTENCY_KEYS {
        uuid user_id PK
        varchar idempotency_key PK
        varchar operation
        uuid application_id FK
    }
    DOCUMENTS {
        uuid id PK
        uuid application_id FK
        varchar type
        varchar stored_name UK
        bigint size_bytes
    }
    ENRICHMENT_RESULTS {
        uuid application_id PK,FK
        varchar source PK
        jsonb payload
        timestamptz fetched_at
    }
    RULE_SETS {
        integer version PK
        jsonb params
        varchar comment
    }
    DECISIONS {
        uuid id PK
        uuid application_id FK
        integer rule_set_version FK
        bigint seq
        varchar outcome
        jsonb inputs
        jsonb factors
        jsonb recommendation
    }
    INFO_REQUESTS {
        uuid id PK
        uuid application_id FK
        jsonb items
        timestamptz deadline
        timestamptz resolved_at
    }
    OFFERS {
        uuid id PK
        uuid application_id FK
        uuid decision_id FK
        varchar product_code FK
        varchar kind
        varchar status
        numeric monthly_payment
        timestamptz valid_until
    }
    SIGNING_SESSIONS {
        uuid id PK
        uuid application_id FK
        uuid offer_id FK
        uuid external_session_id
        varchar code
        uuid signature_id
        timestamptz expires_at
    }
    LOANS {
        uuid application_id PK,FK
        varchar contract_number UK
        bigint amount
        numeric rate
        date maturity_date
    }
```

| Таблица | Ключевые данные и смысл |
| --- | --- |
| `products` | Коды, цели, диапазоны суммы/срока, ставка, лимит автоодобрения, признак возобновляемого лимита; `CHECK` проверяет диапазоны |
| `applications` | Снимок компании и условий, согласие БКИ, статус и даты. `version` нужен для optimistic locking. Номер выдаётся последовательностью; частичный уникальный индекс допускает одну активную заявку компании на продукт |
| `application_events` | Из какого статуса в какой, действие, автор, причина, комментарий и payload. `seq` упорядочивает события при одинаковом времени |
| `pipeline_jobs` | Следующий шаг и срок запуска; состояние, попытки, ошибка и аренда `locked_until`. Частичный уникальный индекс допускает одну открытую задачу на пару заявка/шаг |
| `idempotency_keys` | PK — пользователь и ключ; операция сохранена отдельным полем. Ключ отправки связан с конкретной заявкой и не создаёт повторного перехода |
| `documents` | Метаданные, автор, тип и случайное имя объекта. Байты находятся в отдельном томе, не в PostgreSQL; полное восстановление требует обоих хранилищ |
| `enrichment_results` | По одному сохранённому ответу каждого источника на заявку; JSONB и время получения |
| `rule_sets` | Версия порогов и параметров с причиной изменения; новые оценки используют новую версию |
| `decisions` | Версия правил, входные данные, баллы факторов, рейтинг, стоп-факторы, причины ручного решения и рекомендация. Это снимок оценки, а не ссылка на меняющиеся внешние данные |
| `info_requests` | Перечень файлов/вопросов, срок, автор, время ответа и комментарий. Новый запрос не переписывает историю предыдущего |
| `offers` | Запрошенные или изменённые условия, решение-основание, срок и состояние принятия. Денежные платежи хранятся с двумя знаками |
| `signing_sessions` | Связь с оффером и сессией симулятора, срок, подпись и подписант. Код хранится открыто и показывается на демо-стенде |
| `loans` | Один кредит на заявку; уникальный номер договора и сохранённые реквизиты выдачи. График вычисляется из условий кредита |

`application_events` защищён от `UPDATE` и `DELETE` триггером: аудит должен сохранять
последовательность действий и основания решений. Администратор БД, способный снять
триггер, остаётся доверенной стороной. Старые версии `rule_sets` и снимки `decisions`
приложение сохраняет добавлением записей; отдельного SQL-триггера неизменности у них нет.
Изменяемые сущности — черновик заявки, задания, состояние оффера и разрешение запроса
сведений — не следует смешивать с неизменяемым журналом.

## Engagement

```mermaid
erDiagram
    NOTIFICATIONS {
        uuid id PK
        uuid recipient_id
        varchar type
        varchar title
        varchar body
        varchar link
        varchar dedup_key
        timestamptz read_at
        timestamptz created_at
    }
```

`notifications.recipient_id` логически ссылается на `identity.users.id`, физического
FK нет. Индекс `(recipient_id, dedup_key)` исключает повтор доставки с непустым ключом;
`read_at` меняется отдельно для каждого человека. Ссылка ограничена путём внутри кабинета.
У помощника нет собственной таблицы диалогов: история приходит в запросе, а факты
заявки запрашиваются у `credit` с токеном пользователя.

## External-sim и служебные данные

У `external-sim` нет PostgreSQL-схемы. Сценарные компании читаются из JSON, прочие
синтетические компании воспроизводимо выводятся из ИНН с `00`. Режимы сбоев,
сессии подписания и реестр смоделированных выдач хранятся в памяти процесса.
Они не попадают в backup PostgreSQL и теряются при перезапуске симулятора.

Каждая из трёх баз также содержит служебную `flyway_schema_history`: она ведётся
Flyway и не является бизнес-таблицей. Существующие миграции не редактируются;
изменение структуры оформляется новой миграцией и проверяется сборкой.
