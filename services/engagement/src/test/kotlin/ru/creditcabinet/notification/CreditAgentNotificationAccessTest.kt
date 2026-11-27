package ru.creditcabinet.notification

import com.sun.net.httpserver.HttpServer
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import ru.creditcabinet.notification.application.AgentNotificationAccess
import ru.creditcabinet.notification.infrastructure.CreditAgentNotificationAccess
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class CreditAgentNotificationAccessTest {
    @Test
    fun `private Credit check forwards the same Bearer and does not follow redirects`() {
        val app = UUID.randomUUID()
        val path = "/api/v1/agent/applications/$app/notification-access"
        val authorization = "Bearer synthetic-agent-token"
        val requests = ConcurrentLinkedQueue<Pair<String, String?>>()
        val response = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(exchange.requestURI.path to exchange.requestHeaders.getFirst(HttpHeaders.AUTHORIZATION))
            exchange.responseHeaders.set(HttpHeaders.LOCATION, "/redirect-target")
            exchange.sendResponseHeaders(response.get(), -1)
            exchange.close()
        }
        server.start()
        try {
            val statuses = mapOf(
                204 to AgentNotificationAccess.ALLOWED,
                404 to AgentNotificationAccess.DENIED,
                503 to AgentNotificationAccess.UNAVAILABLE,
                302 to AgentNotificationAccess.UNAVAILABLE,
            )
            for ((status, expected) in statuses) {
                response.set(status)
                val adapter = CreditAgentNotificationAccess("http://127.0.0.1:${server.address.port}", RestClient.builder())
                assertEquals(expected, adapter.check(app, authorization))
            }
            assertEquals(List<Pair<String, String?>>(statuses.size) { path to authorization }, requests.toList())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `missing private Credit URL fails closed`() {
        val adapter = CreditAgentNotificationAccess("", RestClient.builder())
        assertEquals(AgentNotificationAccess.UNAVAILABLE, adapter.check(UUID.randomUUID(), "Bearer synthetic-agent-token"))
    }

    @Test
    fun `answers are remembered briefly per token and outages are not`() {
        val app = UUID.randomUUID()
        val calls = AtomicInteger()
        val response = AtomicInteger(503)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.sendResponseHeaders(response.get(), -1)
            exchange.close()
        }
        server.start()
        try {
            val now = AtomicReference(Instant.parse("2026-11-28T10:00:00Z"))
            val clock = object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC
                override fun withZone(zone: ZoneId?): Clock = this
                override fun instant(): Instant = now.get()
            }
            val adapter = CreditAgentNotificationAccess("http://127.0.0.1:${server.address.port}", RestClient.builder(), clock)

            assertEquals(AgentNotificationAccess.UNAVAILABLE, adapter.check(app, "Bearer a"))
            response.set(204)
            assertEquals(AgentNotificationAccess.ALLOWED, adapter.check(app, "Bearer a"))
            response.set(404)
            assertEquals(AgentNotificationAccess.ALLOWED, adapter.check(app, "Bearer a"))
            assertEquals(AgentNotificationAccess.DENIED, adapter.check(app, "Bearer b"))
            now.set(now.get().plusSeconds(16))
            assertEquals(AgentNotificationAccess.DENIED, adapter.check(app, "Bearer a"))
            assertEquals(4, calls.get())
        } finally {
            server.stop(0)
        }
    }
}
