package ru.creditcabinet.common.observability

import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ObservabilityStarterTest {
    private val servlet = WebApplicationContextRunner().withUserConfiguration(Consumer::class.java)
    private val nonServlet = ApplicationContextRunner().withUserConfiguration(Consumer::class.java)

    @Test
    fun `servlet application discovers exactly one request ID filter through auto configuration imports`() {
        servlet.run { context ->
            assertEquals(setOf("requestIdFilter"), context.getBeansOfType(RequestIdFilter::class.java).keys)
        }
    }

    @Test
    fun `auto configuration backs off in favor of a user filter`() {
        servlet.withUserConfiguration(CustomFilter::class.java).run { context ->
            val userFilter = context.getBean("customRequestIdFilter")
            assertSame(userFilter, context.getBeansOfType(RequestIdFilter::class.java).values.single())
        }
    }

    @Test
    fun `filter returns the local trace ID and clears its MDC entry after the chain`() {
        servlet.run { context ->
            val filter = context.getBean(RequestIdFilter::class.java)
            val request = MockHttpServletRequest().apply { addHeader(RequestIds.HEADER, "untrusted-client-value") }
            val response = MockHttpServletResponse()

            MDC.putCloseable("traceId", "b6247d26af7b4518a9b071af069ac320").use {
                filter.doFilter(request, response) { _, _ ->
                    assertEquals(RequestIds.current(), response.getHeader(RequestIds.HEADER))
                    assertEquals(RequestIds.current(), MDC.get("requestId"))
                }
                assertEquals("b6247d26af7b4518a9b071af069ac320", response.getHeader(RequestIds.HEADER))
                assertNull(MDC.get("requestId"))
            }

            val requestWithoutTrace = MockHttpServletRequest().apply { addHeader(RequestIds.HEADER, "untrusted-client-value") }
            val responseWithoutTrace = MockHttpServletResponse()
            filter.doFilter(requestWithoutTrace, responseWithoutTrace) { _, _ -> assertNull(MDC.get("requestId")) }
            assertNull(responseWithoutTrace.getHeader(RequestIds.HEADER))
        }
    }

    @Test
    fun `non servlet application still discovers privacy filtering and removes every raw URL key`() {
        nonServlet.run { context ->
            assertEquals(emptySet<String>(), context.getBeansOfType(RequestIdFilter::class.java).keys)
            val privacy = context.getBean("hideRawRequestUrls", ObservationFilter::class.java)
            val observation = Observation.Context()
                .addHighCardinalityKeyValue(KeyValue.of("url.full", "https://example.test/applications/private?token=synthetic"))
                .addHighCardinalityKeyValue(KeyValue.of("http.url", "https://example.test/applications/private?token=synthetic"))
                .addHighCardinalityKeyValue(KeyValue.of("http.target", "/applications/private?token=synthetic"))
                .addLowCardinalityKeyValue(KeyValue.of("http.route", "/applications/{id}"))
                .addLowCardinalityKeyValue(KeyValue.of("http.status_code", "401"))
                .addHighCardinalityKeyValue(KeyValue.of("server.address", "example.test"))

            val filtered = privacy.map(observation)

            assertEquals(listOf(KeyValue.of("server.address", "example.test")), filtered.highCardinalityKeyValues.toList())
            assertEquals(
                setOf(KeyValue.of("http.route", "/applications/{id}"), KeyValue.of("http.status_code", "401")),
                filtered.lowCardinalityKeyValues.toSet(),
            )
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Consumer

    @Configuration(proxyBeanMethods = false)
    class CustomFilter {
        @Bean
        fun customRequestIdFilter() = RequestIdFilter()
    }
}
