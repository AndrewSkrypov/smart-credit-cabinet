package ru.creditcabinet.common.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig(
    @Value("\${cabinet.openapi.title:\${spring.application.name}}") private val title: String,
    @Value("\${cabinet.openapi.version:0.1.0}") private val version: String,
) {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title(title)
                .version(version)
                .description("REST API умного кредитного кабинета предпринимателя"),
        )
}
