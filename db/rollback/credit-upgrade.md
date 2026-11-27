# Проверка заполненной Credit V23 при обновлении

`deploy/check-migration-restore.py` остаётся обязательной частью backend CI.
Миграции берутся из проверенного `app.jar`, сверяются с исходниками и выполняются
поставляемым Flyway. Новый fixture не заменяет исходную DRAFT-заявку V2 и проверки
полного снимка данных, последовательностей, DDL и истории Flyway.

После проверки V23 helper `credit_upgrade_fixture.py` одной транзакцией `psql`
добавляет 14 синтетических строк. Следующий снимок перед V24 уже содержит:

- Две поданные ENRICHMENT-заявки с разными версиями и jobs PENDING/RUNNING.
  Контрольные jobs DONE и PENDING у SUBMITTED-заявки исключают лишний backfill.
- SUBMITTED-заявку с ненулевым `submitted_at` и committed BUSINESS BANK_STATEMENT:
  известные UUID, имя, размер, LOCAL location, SHA-256 и `private_to_bank=true`.
  Это сохраняет состояние, по которому документ доступен сотруднику банка;
  HTTP-авторизация и байты файла данным тестом не проверяются.
- DISBURSED-заявку, loan account, tranche, paid payment, reserve и receipt event
  с известными идентификаторами, суммами, датами и версиями.

Все явно заданные поля каждой строки проверяются SQL-сравнением с ожидаемым
значением, включая NULL, JSON, checksum и связи. Проверка выполняется в исходной
обновляемой БД и после восстановления снимка с повторным накатом той же версии.

| Шаг | Дополнительные обязательные проверки |
| --- | --- |
| V24 | Ровно два cycle с исходными job/application UUID и application version; все remote-поля (`stream`, `request`, result, deadlines, score и closure) NULL. Assessment outbox пуст: legacy jobs остаются LOCAL. |
| V25 | Единственный servicing writer LOCAL, epoch 1, manifest NULL. Remote open outbox и opened commands пусты; исходные servicing-строки сохранены. |
| V26 | Единственный Documents owner LOCAL, epoch 1; точный backfill `document_references(document_id, application_id)` для исходного документа с ненулевым created_at. Legacy jobs имеют documents_outages=0. |

Проверки читают состояние и не меняют authority markers. Отдельных runtime-ролей
в этих временных БД нет: проверка runtime grants, внешних сервисов и переключения
REMOTE остаётся в соответствующих delivery/runtime gates.

Список успешно выполненных проверок обеих веток V24+ записывается в
`credit_upgrade_checks` у каждой версии в `migration-restore.json`. При ошибке
общий `passed` остаётся false, `pending` указывает незавершённый шаг; отсутствующая
`document_references` или `documents_outages` в V26 является ошибкой, пропуска нет.
Для полного прогона нужен JAR из итогового исходника с этим контрактом V26.

Изолированные проверки конструкции fixture и обработки ошибок запускаются без
PostgreSQL и JVM:

```bash
python3 -B -m unittest scripts.tests.test_migration_upgrade_fixture
```

Эти unit-тесты не подтверждают исполнение SQL, PostgreSQL restore/reapply или
runtime PASS. Реальный gate запускается по команде из [README](README.md) в общей
CI-очереди с подготовленными JAR и изолированной PostgreSQL.
