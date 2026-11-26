package ru.creditcabinet.common.observability

import io.micrometer.observation.ObservationFilter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

@AutoConfiguration
class TelemetryPrivacy {
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnMissingBean(RequestIdFilter::class)
    fun requestIdFilter(): RequestIdFilter = RequestIdFilter()

    @Bean
    @ConditionalOnMissingBean(name = ["hideRawRequestUrls"])
    fun hideRawRequestUrls(): ObservationFilter = ObservationFilter { context ->
        // Retain route templates and status. A raw URL can contain a user identifier or one-time token.
        for (key in listOf("http.url", "http.target", "url.full")) context.removeHighCardinalityKeyValue(key)
        context
    }
}
