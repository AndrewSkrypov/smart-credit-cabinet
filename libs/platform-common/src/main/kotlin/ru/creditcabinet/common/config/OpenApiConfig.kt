package ru.creditcabinet.common.config

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.AntPathMatcher
import ru.creditcabinet.common.dto.ErrorResponse

@Configuration
class OpenApiConfig(
    @Value("\${cabinet.openapi.title:\${spring.application.name}}") private val title: String,
    @Value("\${cabinet.openapi.version:0.1.0}") private val version: String,
) {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title(title)
                    .version(version)
                    .description("REST API умного кредитного кабинета предпринимателя. Данные демо-стенда синтетические."),
            ).components(
                Components()
                    .addSecuritySchemes("bearerAuth", SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                    .addSecuritySchemes("systemKey", SecurityScheme().type(SecurityScheme.Type.APIKEY).`in`(SecurityScheme.In.HEADER).name("X-Api-Key")),
            )

    @Bean
    fun responseContracts(properties: PlatformSecurityProperties): OpenApiCustomizer = OpenApiCustomizer { api ->
        val components = api.components ?: Components().also { api.components = it }
        ModelConverters.getInstance().read(ErrorResponse::class.java).forEach { (name, schema) -> components.addSchemas(name, schema) }
        components.addSchemas(
            "SystemKeyError",
            ObjectSchema()
                .addProperty("status", Schema<Int>().type("integer").example(401))
                .addProperty("error", StringSchema().example("Unauthorized"))
                .addProperty("message", StringSchema().example("Нужен ключ системы-клиента в заголовке X-Api-Key"))
                .required(listOf("status", "error", "message")),
        )
        val matcher = AntPathMatcher()
        api.paths?.forEach { (path, item) ->
            item.readOperationsMap().forEach { (method, operation) ->
                val public = properties.publicEndpoints.any { entry ->
                    val parts = entry.trim().split(Regex("\\s+"), limit = 2)
                    parts.size == 2 && parts[0].equals(method.name, true) && matcher.match(parts[1], path)
                }
                val system = path.startsWith("/ext/")
                operation.security = when {
                    system -> listOf(SecurityRequirement().addList("systemKey"))
                    public -> emptyList()
                    else -> listOf(SecurityRequirement().addList("bearerAuth"))
                }
                val responses = operation.responses ?: ApiResponses().also { operation.responses = it }
                fun error(code: Int, description: String, message: String) {
                    val keyError = system && code == 401
                    val example = linkedMapOf<String, Any>(
                        "status" to code,
                        "error" to ERROR_NAMES.getValue(code),
                        "message" to message,
                    )
                    if (!keyError) {
                        example["timestamp"] = "2026-01-01T00:00:00Z"
                        example["path"] = path
                        example["requestId"] = "example-request-id"
                    }
                    responses.addApiResponse(
                        code.toString(),
                        ApiResponse().description(description).content(
                            Content().addMediaType(
                                "application/json",
                                MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/${if (keyError) "SystemKeyError" else "ErrorResponse"}"))
                                    .example(example),
                            ),
                        ),
                    )
                }
                if (system || !public) {
                    error(
                        401,
                        if (system) "Нет корректного ключа системы" else "Нет корректного токена",
                        if (system) "Нужен ключ системы-клиента в заголовке X-Api-Key" else "Требуется вход в систему",
                    )
                }
                if (!public || (path.startsWith("/sim/control") && method.name != "GET")) {
                    error(403, "Недостаточно прав или действие выключено в окружении", "Недостаточно прав для этого действия")
                }
                if (path.contains("{")) error(404, "Объект не найден или принадлежит другой компании", "Объект не найден")
                if (method.name in setOf("POST", "PUT", "PATCH", "DELETE")) {
                    error(400, "Некорректные данные запроса", "Проверьте поля запроса")
                    if (!system && !path.startsWith("/sim/")) {
                        error(409, "Конфликт состояния, версии или повторной операции", "Данные только что изменились — обновите страницу и повторите действие")
                    }
                }
                if (path.contains("/auth/") || path.contains("/documents") || path.contains("/messages")) {
                    error(429, "Превышено ограничение частоты запросов", "Слишком много запросов — повторите позже")
                }
                if ((!public && !system) || path == "/api/v1/auth/password-recovery") {
                    error(503, "Проверка сессии или почтовый канал временно недоступны", "Сервис временно недоступен — попробуйте позже")
                }
                if (path in setOf("/api/v1/auth/password-reset", "/api/v1/auth/invitations/inspect", "/api/v1/auth/invitations/accept")) {
                    error(
                        410,
                        "Одноразовая ссылка истекла, отменена, использована или не найдена",
                        "Ссылка недействительна или срок её действия истёк. Запросите новую.",
                    )
                }
            }
        }
    }

    private companion object {
        val ERROR_NAMES = mapOf(
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            409 to "Conflict",
            410 to "Gone",
            429 to "Too Many Requests",
            503 to "Service Unavailable",
        )
    }
}
