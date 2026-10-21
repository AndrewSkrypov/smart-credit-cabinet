package ru.creditcabinet.common.notifications

import org.springframework.http.HttpMethod
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestClient
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class EngagementNotificationsClientTest {
    private val item =
        PlatformNotification(
            UUID.randomUUID(),
            PlatformNotification.ACTION_REQUIRED,
            "Нужны документы",
            "Загрузите декларацию",
            "/client/applications/1",
            "info-requested:1",
        )
    private val requests = AtomicInteger()

    private fun client(configure: (MockRestServiceServer) -> Unit): Pair<EngagementNotificationsClient, MockRestServiceServer> {
        val builder = RestClient.builder().baseUrl("http://engagement")
            .requestInterceptor { request, body, execution -> requests.incrementAndGet().let { execution.execute(request, body) } }
        val server = MockRestServiceServer.bindTo(builder).build()
        configure(server)
        return EngagementNotificationsClient(builder.build()) to server
    }

    @Test
    fun `posts after the commit, not before`() {
        val (client, server) = client {
            it.expect(ExpectedCount.once(), requestTo("http://engagement/internal/v1/notifications")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.items[0].dedupKey").value("info-requested:1"))
                .andRespond(withSuccess())
        }
        TransactionSynchronizationManager.initSynchronization()
        try {
            client.send(listOf(item))
            Thread.sleep(200)
            assertEquals(0, requests.get(), "nothing goes out while the transaction is open")
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        // Waits for the background sender to finish.
        client.destroy()
        server.verify()
        assertEquals(1, requests.get())
    }

    @Test
    fun `a engagement outage is logged, never thrown at the caller`() {
        val (client, server) = client { it.expect(requestTo("http://engagement/internal/v1/notifications")).andRespond(withServerError()) }
        client.send(listOf(item))
        client.destroy()
        server.verify()
    }
}
