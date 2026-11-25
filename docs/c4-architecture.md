# Архитектура финального кабинета

Схемы описывают агентский контур и границы сервисов. Переходы заявки приведены в [конечном автомате](state-machine.md), права PostgreSQL и порядок восстановления в [операционной документации](database-isolation.md).

## C4 System Context

```mermaid
flowchart LR
  Agent["Агент банка<br/>Ведёт закреплённых клиентов"]
  Client["Представитель компании<br/>Подтверждает заявку и подписывает"]
  Staff["Сотрудники банка<br/>Рассматривают заявки и ведут продукты"]
  App["Умный кредитный кабинет"]
  Sources["Синтетические ЕГРЮЛ, ФНС, БКИ,<br/>ПЭП и АБС"]
  Agent -->|Портфель, черновик, документы| App
  Client -->|Подтверждение, акцепт, ПЭП| App
  Staff -->|Решение, ручная очередь, продукты| App
  App -->|Сведения, подпись, демовыдача| Sources
```

Агент и клиент работают с общей заявкой после запроса подтверждения. Действующий мандат проверяется в identity, а credit проверяет владельца заявки и разрешённый переход. Банк получает заявку после подтверждения клиентом и согласия БКИ.

## C4 Container

```mermaid
flowchart LR
  User["Браузер"] -->|HTTPS| Caddy["Caddy"]
  Caddy --> Gateway["Nginx gateway"]
  Gateway -->|SPA| Frontend["React frontend"]
  Gateway -->|auth, users, agent/clients| Identity["identity"]
  Gateway -->|applications, agent, products, loans| Credit["credit"]
  Gateway -->|notifications, assistant| Engagement["engagement"]
  Identity --> IdentityDB[(identity DB)]
  Credit --> CreditDB[(credit DB)]
  Engagement --> EngagementDB[(engagement DB)]
  Credit -->|Мандаты и компании| Identity
  Credit -->|Источники, подписание, АБС| Sim["external-sim"]
  Credit -->|Outbox| Engagement
  Identity -->|Outbox| Engagement
  Engagement -->|Чтение с токеном вызывающего| Credit
  Engagement -->|Пересказ справки| Ollama["Ollama, профиль ai"]
  Credit --> Storage[(Частное хранилище документов)]
  Identity --> Valkey[(Valkey)]
  Credit --> Valkey
  Engagement --> Valkey
```

Входящий шлюз маршрутизирует публичные контуры. Доступ к `/internal/` и `/ext/` через него закрыт. Веб-процессы работают под собственными ролями `*_app`, отдельно от владельцев схем. Помощник работает по правилам при отключении Ollama.

## C4 Component. Границы внутри credit

```mermaid
flowchart LR
  subgraph In["Входные адаптеры"]
    AgentHttp["AgentController"]
    ClientHttp["ApplicationController"]
    BankHttp["UnderwritingController"]
    Worker["PipelineWorker"]
  end
  subgraph Use["Операции приложения"]
    AgentUse["AgentApplicationService"]
    AppUse["ApplicationService"]
    BankUse["UnderwritingService"]
    Steps["PipelineSteps"]
  end
  subgraph Domain["Домен"]
    State["LoanApplication и автомат статусов"]
    Risk["Scorecard и CreditPolicy"]
    Product["Версии и критерии продуктов"]
  end
  subgraph Port["Выходные порты"]
    Store["ApplicationRepository, ApplicationJournal, PipelineJobs"]
    Mandate["AgentAccessPort"]
    Doc["DocumentRepository, FileStore"]
    Source["Внешние источники, ПЭП и АБС"]
    Notices["PlatformNotifications"]
  end
  AgentHttp --> AgentUse
  ClientHttp --> AppUse
  BankHttp --> BankUse
  Worker --> Steps
  AgentUse --> State & Mandate & Doc
  AppUse --> State & Product & Store & Notices
  BankUse --> State & Risk
  Steps --> State & Risk & Store & Source
  Store --> Postgres[(PostgreSQL)]
  Mandate --> Identity["identity, внутренний HTTP"]
  Doc --> Files["JDBC, S3 или локальное хранилище"]
  Source --> Sim["external-sim"]
  Notices --> Outbox[(delivery_outbox)]
```

Запись перехода, журнала, следующей задачи и уведомления выполняется в БД credit. Вызовы к источникам идут вне транзакции. Обработка задач использует аренду и повтор. Идемпотентность операции АБС привязана к заявке.

## C4. Целевая эволюция credit

```mermaid
flowchart LR
  Edge["Ролевые входы client, agent, bank"]
  Catalog["Каталог продуктов<br/>Неизменяемые версии"]
  Workflow["Конвейер заявок<br/>Статус, журнал, оркестрация"]
  Risk["Андеррайтинг<br/>Факторы, правила, решение"]
  Docs["Документы<br/>Файлы, сканирование, метаданные"]
  Loans["Выдача и обслуживание<br/>Договоры, транши, платежи"]
  Events["Публикация бизнес-событий через outbox"]
  Edge --> Catalog & Workflow & Docs & Loans
  Workflow -->|ID и версия условий| Catalog
  Workflow -->|Снимок фактов и версия решения| Risk
  Workflow -->|Статус необходимых документов| Docs
  Workflow -->|Подписанный договор| Loans
  Catalog --> Events
  Workflow --> Events
  Risk --> Events
  Docs --> Events
  Loans --> Events
```

Каталог отдаёт неизменяемый снимок по версии. Андеррайтинг получает версионированные факты. Документы выносятся после согласования проверки прав на заявку и атомарного связывания файла с метаданными. Выдача выносится после проверки идемпотентного ключа и обработки повторных сообщений. На каждой границе владелец данных меняет своё состояние и пишет outbox в одной транзакции. Потребитель хранит ключ обработанного события, а конвейер отображает ожидающий статус до подтверждения. Разделение на отдельные процессы относится к целевой архитектуре.
