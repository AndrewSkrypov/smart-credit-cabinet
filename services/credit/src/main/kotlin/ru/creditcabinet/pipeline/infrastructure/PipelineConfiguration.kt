package ru.creditcabinet.pipeline.infrastructure

import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import ru.creditcabinet.pipeline.application.PipelineSettings

@Configuration(proxyBeanMethods = false)
class PipelineConfiguration {
    @Bean("cabinet.pipeline-ru.creditcabinet.pipeline.PipelineSettings")
    fun pipelineSettings(environment: Environment): PipelineSettings =
        Binder.get(environment).bindOrCreate("cabinet.pipeline", Bindable.of(PipelineSettings::class.java))
}
