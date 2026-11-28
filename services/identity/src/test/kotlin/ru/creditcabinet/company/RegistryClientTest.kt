package ru.creditcabinet.company

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.springframework.web.client.RestClient
import ru.creditcabinet.company.domain.RegistryAnswer
import ru.creditcabinet.company.infrastructure.RegistryClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

class RegistryClientTest {
    @Test
    fun `only both confirmed records produce a verified name`() {
        val inn = "0001001014"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val taxCheck = AtomicReference(200 to """{"registered":true}""")
        server.createContext("/ext/registry/v1/companies/$inn") { exchange ->
            exchange.respond(200, """{"shortName":"Тестовая компания"}""")
        }
        server.createContext("/ext/registry/v1/taxpayer-registration") { exchange ->
            val (status, body) = taxCheck.get()
            if (exchange.requestURI.rawQuery == "inn=$inn") exchange.respond(status, body)
            else exchange.respond(400, "{}")
        }
        server.start()
        try {
            val registry = RegistryClient("http://127.0.0.1:${server.address.port}", "test-key", RestClient.builder())
            assertEquals(RegistryAnswer.Found("Тестовая компания"), registry.lookup(inn))

            taxCheck.set(200 to """{"registered":false}""")
            assertEquals(RegistryAnswer.TaxpayerNotFound, registry.lookup(inn))
            assertEquals(RegistryAnswer.NotFound, registry.lookup("0000000018"))

            for ((status, body) in listOf(503 to "{}", 404 to "{}", 200 to "{}")) {
                taxCheck.set(status to body)
                assertEquals(RegistryAnswer.Unavailable, registry.lookup(inn))
            }
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(status: Int, json: String) {
        val bytes = json.toByteArray()
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
