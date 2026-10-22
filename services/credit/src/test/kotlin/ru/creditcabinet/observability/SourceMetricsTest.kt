package ru.creditcabinet.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.kotlin.mock
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import ru.creditcabinet.integration.ExternalCalls
import ru.creditcabinet.integration.SourceUnavailableException
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SourceMetricsTest {
    @Test
    fun `source outcomes and breaker gauges follow failures recovery and ignored client errors`() {
        val registry = SimpleMeterRegistry()
        val metrics = PipelineMetrics(registry, mock<DataSource>(), Clock.systemUTC())
        val calls = ExternalCalls("http://localhost", "synthetic-key", Duration.ofSeconds(1), RestClient.builder(), metrics)
        assertEquals("ok", calls.call("ФНС") { "ok" })
        assertEquals(
            null,
            calls.call<String>("БКИ") {
                throw HttpClientErrorException.create(HttpStatus.NOT_FOUND, "missing", HttpHeaders(), byteArrayOf(), null)
            },
        )
        repeat(3) {
            assertFailsWith<SourceUnavailableException> {
                calls.call<String>("БКИ") { throw ResourceAccessException("slow", SocketTimeoutException("synthetic")) }
            }
        }
        assertEquals(3L, registry.get("cabinet.source.call").tags("source", "bureau", "result", "timeout").timer().count())
        assertEquals(1.0, registry.get("cabinet.source.breaker").tags("source", "bureau", "state", "OPEN").gauge().value())
        assertFailsWith<SourceUnavailableException> { calls.call("БКИ") { "blocked" } }
        assertEquals(1L, registry.get("cabinet.source.call").tags("source", "bureau", "result", "circuit_open").timer().count())
        calls.breaker("БКИ").transitionToHalfOpenState()
        assertEquals("recovered", calls.call("БКИ") { "recovered" })
        assertEquals(1.0, registry.get("cabinet.source.breaker").tags("source", "bureau", "state", "CLOSED").gauge().value())
        assertEquals(1L, registry.get("cabinet.source.call").tags("source", "bureau", "result", "success").timer().count())
    }
}
