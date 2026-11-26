package ru.creditcabinet.analytics

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import ru.creditcabinet.analytics.infrastructure.HttpCurrentStatusesProjection
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpCurrentStatusesProjectionTest {
    private val token = "test-only-alpha-analytics-service-token"
    private val topic = "test-projection-generation"

    @Test
    fun `disabled producer or read mode does not check outbox or connect`() {
        for ((readEnabled, captureEnabled) in listOf(false to true, true to false)) {
            val jdbc = Mockito.mock(NamedParameterJdbcTemplate::class.java)
            projection(jdbc, enabled = readEnabled, captureEnabled = captureEnabled) { reader, metrics ->
                assertNull(reader.readWhenReady())
                val reason = if (readEnabled) "producer_disabled" else "disabled"
                assertEquals(1.0, metrics.get(READ_METRIC).tags("source", "sql", "reason", reason).counter().count())
            }
            Mockito.verifyNoInteractions(jdbc)
        }
    }

    @Test
    fun `checks outbox before and after remote read with dedicated service identity`() {
        val calls = mutableListOf<String?>()
        server { request ->
            synchronized(calls) {
                calls.add(request.requestHeaders.getFirst("X-Analytics-Read-Token"))
            }
            request.reply(200, """{"generation":"$topic","currentStatuses":{"SUBMITTED":3}}""")
        }.use { peer ->
            val outbox = StubOutbox(true, true, true, false, false)
            projection(outbox, peer.url) { reader, metrics ->
                assertEquals(mapOf("SUBMITTED" to 3), reader.readWhenReady())
                assertNull(reader.readWhenReady())
                assertNull(reader.readWhenReady())
                assertEquals(1.0, metrics.get(READ_METRIC).tags("source", "projection", "reason", "ready").counter().count())
                assertEquals(2.0, metrics.get(READ_METRIC).tags("source", "sql", "reason", "outbox_pending").counter().count())
            }
            synchronized(calls) { assertEquals(listOf(token, token), calls) }
        }
    }

    @Test
    fun `rejects a ready response from a different generation`() {
        server { it.reply(200, """{"generation":"previous","currentStatuses":{"SUBMITTED":3}}""") }.use { peer ->
            projection(StubOutbox(true), peer.url) { reader, metrics ->
                assertNull(reader.readWhenReady())
                assertEquals(1.0, metrics.get(READ_METRIC).tags("source", "sql", "reason", "invalid_projection").counter().count())
            }
        }
    }

    @Test
    fun `remote lag and slow body fall back to SQL`() {
        server {
            it.responseHeaders.add("X-Analytics-Readiness", "lag")
            it.reply(503, "")
        }.use { peer ->
            projection(StubOutbox(true), peer.url) { reader, metrics ->
                assertNull(reader.readWhenReady())
                assertEquals(1.0, metrics.get(READ_METRIC).tags("source", "sql", "reason", "lag").counter().count())
            }
        }
        val release = CountDownLatch(1)
        server {
            it.sendResponseHeaders(200, 2)
            try {
                release.await(5, TimeUnit.SECONDS)
                it.responseBody.write("{}".toByteArray())
            } finally {
                it.close()
            }
        }.use { peer ->
            try {
                projection(StubOutbox(true), peer.url, timeoutMs = 250) { reader, metrics ->
                    assertNull(reader.readWhenReady())
                    assertEquals(1.0, metrics.get(READ_METRIC).tags("source", "sql", "reason", "timeout").counter().count())
                }
            } finally {
                release.countDown()
            }
        }
    }

    private fun projection(
        jdbc: NamedParameterJdbcTemplate,
        url: String = "",
        enabled: Boolean = true,
        captureEnabled: Boolean = true,
        timeoutMs: Long = 2_000,
        check: (HttpCurrentStatusesProjection, SimpleMeterRegistry) -> Unit,
    ) {
        val metrics = SimpleMeterRegistry()
        val reader = HttpCurrentStatusesProjection(jdbc, ObjectMapper(), metrics, enabled, captureEnabled, true, topic, url, token, timeoutMs)
        try {
            check(reader, metrics)
        } finally {
            reader.close()
            metrics.close()
        }
    }

    private class StubOutbox(vararg drained: Boolean) : NamedParameterJdbcTemplate(Mockito.mock(JdbcOperations::class.java)) {
        private val states = drained.iterator()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> queryForObject(sql: String, paramMap: Map<String, *>, requiredType: Class<T>): T? {
            assertEquals(mapOf("generation" to "test-projection-generation"), paramMap)
            return states.next() as T
        }
    }

    private class Peer(private val server: HttpServer) : AutoCloseable {
        val url = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }

    private fun server(handle: (HttpExchange) -> Unit): Peer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/v1/analytics/current-statuses", handle)
        server.start()
        return Peer(server)
    }

    private fun HttpExchange.reply(status: Int, body: String) {
        val data = body.toByteArray()
        sendResponseHeaders(status, data.size.toLong())
        responseBody.use { it.write(data) }
    }

    private companion object {
        const val READ_METRIC = "cabinet.analytics.current.statuses.read"
    }
}
