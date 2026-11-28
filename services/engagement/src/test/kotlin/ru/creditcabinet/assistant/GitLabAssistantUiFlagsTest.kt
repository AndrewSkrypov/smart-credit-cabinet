package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import ru.creditcabinet.assistant.infrastructure.GitLabAssistantUiFlags
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitLabAssistantUiFlagsTest {
    @Test
    fun `sync authenticates to GitLab and fails closed on expiry disable missing flag and failed fetch`() {
        val user = UUID.randomUUID()
        val otherUser = UUID.randomUUID()
        val devUserStrategy = """{"name":"userWithId","parameters":{"userIds":"$user"}}"""
        val defaultStrategy = """{"name":"default","parameters":{}}"""
        val received = LinkedBlockingQueue<Pair<String, String>>()
        val response = AtomicReference(200 to flags(devUserStrategy))
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v4/feature_flags/unleash/42/client/features") { exchange ->
            received.add(
                exchange.requestHeaders.getFirst("UNLEASH-INSTANCEID") to exchange.requestHeaders.getFirst("UNLEASH-APPNAME"),
            )
            val (status, body) = response.get()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        var now = Instant.EPOCH
        val clock = object : Clock() {
            override fun instant() = now

            override fun getZone(): ZoneId = ZoneOffset.UTC

            override fun withZone(zone: ZoneId) = Clock.fixed(now, zone)
        }
        val url = "http://127.0.0.1:${server.address.port}/api/v4/feature_flags/unleash/42/"
        val adapter = GitLabAssistantUiFlags(url, "test-instance", "dev", ObjectMapper(), clock)
        try {
            assertFalse(adapter.isKeshaEnabled(user))
            adapter.refresh()
            assertEquals("test-instance" to "dev", received.remove())
            assertTrue(adapter.isKeshaEnabled(user))
            assertFalse(adapter.isKeshaEnabled(otherUser))
            now = now.plusSeconds(59)
            assertTrue(adapter.isKeshaEnabled(user))
            now = now.plusSeconds(1)
            assertFalse(adapter.isKeshaEnabled(user))

            val disabledResponses = listOf(
                200 to flags(defaultStrategy, "false"),
                200 to flags(defaultStrategy, """"true""""),
                200 to flags("""{"name":"flexibleRollout","parameters":{"rollout":"100"}}"""),
                200 to """{"features":[]}""",
                200 to "malformed",
                302 to flags(defaultStrategy),
                503 to "unavailable",
            )
            for (disabled in disabledResponses) {
                response.set(200 to flags(defaultStrategy))
                adapter.refresh()
                assertTrue(adapter.isKeshaEnabled(otherUser))
                response.set(disabled)
                adapter.refresh()
                assertFalse(adapter.isKeshaEnabled(user), "Unexpected flag for $disabled")
            }
        } finally {
            server.stop(0)
        }
    }

    private fun flags(strategy: String, enabled: String = "true") =
        """{"features":[{"name":"assistant_launcher_fantech","enabled":$enabled,"strategies":[$strategy]}]}"""
}
