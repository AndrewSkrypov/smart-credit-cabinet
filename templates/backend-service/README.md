# Шаблон backend-сервиса

Из корня репозитория с Python 3 и JDK 21:

```bash
python3 templates/backend-service/generate.py report-service
./gradlew :services:report-service:test :services:report-service:ktlintCheck :services:report-service:bootJar
```

Генератор создаёт `services/<имя>` и добавляет модуль в `settings.gradle.kts`.
Он переименовывает класс приложения и тест запуска, подключает существующие
Gradle conventions и копирует `gradle.lockfile`. Для имени подходит, например, `report-service`. Занятые имена
отклоняются. `SERVICE_NAME` и `JWT_SECRET` задаются конфигурацией контура.

Задание `backend-template` в стадии `quality` проверяет шаблон на копии
`git archive` точного SHA. Тот же сценарий запускается локально с новым
каталогом для результатов:

```bash
bash ci/gitlab/quality/backend-template.sh HEAD /tmp/cabinet-template-report --offline
```

Сценарий записывает полный SHA и JUnit XML. Он использует текущий Gradle-кэш,
а `CABINET_TEMPLATE_GRADLE` позволяет указать уже установленный Gradle 8.10.2.
Тест запуска поднимает HTTP на случайном локальном порту и проверяет health,
`X-Request-Id` и JSON ошибки. Артефакт
`services/report-service/build/libs/app.jar` использует стандартный
[docker/service/Dockerfile](../../docker/service/Dockerfile).

После изменения зависимостей обновите lock-файл нового модуля командой
`./gradlew :services:report-service:resolveAndLockAll --write-locks`.
При изменении самого шаблона перенесите пересозданный lock-файл в этот каталог.
Сборка с `--offline` использует уже доступный кэш зависимостей.

Вводите прикладные операции и интерфейсы портов в `application`, правила
и инварианты в `domain`. Входной адаптер преобразует HTTP, Protobuf или
событие в команду приложения. Выходные адаптеры реализуют порты хранения,
межсервисных вызовов и публикации. Схема контракта хранится в `contracts/`.
Для сервиса со своей БД используйте существующий JPA/Flyway convention
и отдельные роли приложения и мигратора.
