package ru.creditcabinet.common.notifications

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.http.HttpMethod
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class EngagementNotificationsClientTest {
    private val mapper = jacksonObjectMapper()
    private val item = PlatformNotification(
        recipientId = UUID.randomUUID(),
        type = PlatformNotification.ACTION_REQUIRED,
        title = "Нужны документы",
        body = "Загрузите декларацию",
        link = "/client/applications/1",
        dedupKey = "info-requested:1",
    )

    @Test
    fun `the worker posts the persisted event with its stable key`() {
        val builder = RestClient.builder().baseUrl("http://engagement")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://engagement/internal/v1/notifications")).andExpect(method(HttpMethod.POST))
            .andExpect(jsonPath("$.items[0].dedupKey").value("info-requested:1")).andRespond(withSuccess())
        EngagementNotificationsClient(builder.build(), mapper).deliver(mapper.writeValueAsString(item))
        server.verify()
    }

    @Test
    fun `an engagement outage reaches the worker so the persisted event can be retried`() {
        val builder = RestClient.builder().baseUrl("http://engagement")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://engagement/internal/v1/notifications")).andRespond(withServerError())
        assertFailsWith<RestClientResponseException> {
            EngagementNotificationsClient(builder.build(), mapper).deliver(mapper.writeValueAsString(item))
        }
        server.verify()
    }
}
