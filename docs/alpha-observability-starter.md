# Стартер наблюдаемости Alpha

`libs:starter-observability` выделяет техническую обвязку из `platform-common`.
Он возвращает `X-Request-Id` из текущего trace context, удаляет необработанные
URL из наблюдений Micrometer и задаёт низкоприоритетные настройки health,
Prometheus и трассировки.
В Gradle-зависимостях и API стартера нет моделей заявок, начислений и каталогов.

Работающие сервисы получают стартер через существующий `platform-common`.
Пакеты публичных классов сохраняются. Новый сервис, которому не нужны JWT,
уведомления и доступ к БД платформы, может подключить модуль отдельно.

```kotlin
dependencies {
    implementation(project(":libs:starter-observability"))
}
```

Spring Boot находит конфигурацию через `AutoConfiguration.imports`. Фильтр
добавляется в servlet-приложении при отсутствии собственного `RequestIdFilter`.
Обработчик конфиденциальности работает и без servlet. Настройки в
`application.yml` и переменные окружения имеют приоритет над defaults.

Приёмка этой части R4 проверяет единственный servlet-фильтр, сохранение
route template при исключении полного URL, приоритет настройки сервиса,
существующий контракт `X-Request-Id` и отсутствие новых доменных зависимостей.
Общий Gradle-шаблон сервиса сохраняет сборку JAR, Flyway для служб с БД
и проверки репозитория.

## Подключение Catalog

При сборке через `cabinet.service-conventions` стартер уже входит в зависимости
через `platform-common`. Для отдельного подключения достаточно
`implementation(project(":libs:starter-observability"))`. Трассировка HTTP
в стандартном сервисе настраивается convention через Actuator и
`micrometer-tracing-bridge-otel`.

Публичный API находится в пакете `ru.creditcabinet.common.observability`.
`RequestIds.HEADER` содержит имя `X-Request-Id`, а `RequestIds.current()`
возвращает trace ID из MDC или `null`. Servlet-фильтр берёт ответный ID
из текущей трассировки. Объявленный сервисом bean типа `RequestIdFilter`
подменяет стандартный bean. Перед передачей наблюдения обработчикам удаляются high-cardinality теги
`http.url`, `http.target` и `url.full`. Шаблон маршрута, HTTP-статус и остальные теги
сохраняются.

[Генерация и проверка нового модуля](../templates/backend-service/README.md)
