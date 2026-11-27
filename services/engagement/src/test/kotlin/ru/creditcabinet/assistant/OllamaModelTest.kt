package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.web.client.RestClient
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OllamaModelTest {
    @Test
    fun `preloading submits no prompt and chat keeps the same model warm`() {
        val mapper = ObjectMapper()
        val requests = LinkedBlockingQueue<JsonNode>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            exchange.reply("""{"models":[{"name":"qwen2.5:3b"}]}""")
        }
        server.createContext("/api/chat") { exchange ->
            requests.add(exchange.requestBody.use(mapper::readTree))
            exchange.reply("""{"message":{"role":"assistant","content":"Справка по кредиту","thinking":"private trace"}}""")
        }
        server.start()
        try {
            val model = model("http://127.0.0.1:${server.address.port}")
            assertTrue(model.warmupOnce())
            assertEquals("Справка по кредиту", model.chat(listOf(ChatMessage("user", "Справка"))))
            val warmup = requests.remove()
            assertEquals("qwen2.5:3b", warmup.path("model").asText())
            assertEquals("15m", warmup.path("keep_alive").asText())
            assertEquals(0, warmup.path("messages").size())
            val chat = requests.remove()
            assertEquals("15m", chat.path("keep_alive").asText())
            assertEquals("Справка", chat.path("messages").path(0).path("content").asText())
            assertFalse(chat.path("stream").asBoolean())
            assertFalse(chat.path("think").asBoolean())
            assertTrue(chat.path("options").path("num_predict").asInt() in 1..300)
            assertTrue(requests.isEmpty())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rules only requires no model connection`() {
        val model = model("")
        assertFalse(model.warmupOnce())
        assertFalse(model.available())
        assertNull(model.chat(listOf(ChatMessage("user", "Справка"))))
    }

    private fun model(url: String) =
        OllamaModel(url, "qwen2.5:3b", Duration.ofSeconds(3), "15m", false, RestClient.builder(), Clock.systemUTC())

    private fun HttpExchange.reply(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
