package ru.creditcabinet.assistant

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OllamaModelTest {
    @Test
    fun `native thinking model returns only its final answer in a bounded non streaming request`() {
        val mapper = jacksonObjectMapper()
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            val bytes = """{"models":[{"name":"qwen3:1.7b"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/chat") { exchange ->
            requests.add(exchange.requestBody.bufferedReader().use { it.readText() })
            val bytes = """{"message":{"role":"assistant","content":"Лимит возобновляется.","thinking":"private trace"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val model = OllamaModel(
                "http://127.0.0.1:${server.address.port}",
                "qwen3:1.7b",
                Duration.ofSeconds(2),
                RestClient.builder(),
                Clock.systemUTC(),
            )
            assertTrue(model.available())
            assertEquals("Лимит возобновляется.", model.chat(listOf(ChatMessage("user", "Как работает лимит?"))))
            val request = mapper.readTree(requests.single())
            assertFalse(request["think"].asBoolean())
            assertFalse(request["stream"].asBoolean())
            assertEquals("qwen3:1.7b", request["model"].asText())
            assertEquals("Как работает лимит?", request["messages"][0]["content"].asText())
            assertTrue(request["options"]["num_predict"].asInt() in 1..300)
        } finally {
            server.stop(0)
        }
    }
}
