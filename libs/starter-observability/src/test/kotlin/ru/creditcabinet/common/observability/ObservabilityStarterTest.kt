package ru.creditcabinet.common.observability

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import kotlin.test.assertEquals

class ObservabilityStarterTest {
    @Test
    fun `servlet starter registers one filter and removes raw URLs`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TelemetryPrivacy::class.java))
            .run { context ->
                assertEquals(1, context.getBeansOfType(RequestIdFilter::class.java).size)
                val observation = Observation.Context()
                    .addHighCardinalityKeyValue(KeyValue.of("url.full", "http://internal/test"))
                    .addHighCardinalityKeyValue(KeyValue.of("http.route", "/applications/{id}"))
                val filtered = context.getBean("hideRawRequestUrls", ObservationFilter::class.java).map(observation)
                assertEquals(listOf("http.route"), filtered.highCardinalityKeyValues.map(KeyValue::getKey))
            }
    }

    @Test
    fun `non servlet app can still use privacy filtering`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TelemetryPrivacy::class.java))
            .run { context ->
                assertEquals(0, context.getBeansOfType(RequestIdFilter::class.java).size)
                assertEquals(1, context.getBeansOfType(ObservationFilter::class.java).size)
            }
    }

    @Test
    fun `service configuration precedes starter defaults`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", mapOf("management.tracing.sampling.probability" to 0.2)))
        PlatformDefaults().postProcessEnvironment(environment, SpringApplication())
        assertEquals("0.2", environment.getProperty("management.tracing.sampling.probability"))
        assertEquals("health,info,prometheus", environment.getProperty("management.endpoints.web.exposure.include"))
    }
}
