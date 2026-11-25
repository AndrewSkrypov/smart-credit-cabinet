package ru.creditcabinet.common.observability

import io.micrometer.observation.ObservationFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class TelemetryPrivacy {
    @Bean
    fun hideRawRequestUrls(): ObservationFilter = ObservationFilter { context ->
        // Retain route templates and status. A raw URL can contain a user identifier or one-time token.
        for (key in listOf("http.url", "http.target", "url.full")) context.removeHighCardinalityKeyValue(key)
        context
    }
}
