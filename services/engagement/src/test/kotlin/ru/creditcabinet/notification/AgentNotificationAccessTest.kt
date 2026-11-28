package ru.creditcabinet.notification

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.notification.application.AgentNotificationAccess
import ru.creditcabinet.notification.application.AgentNotificationAccessPort
import ru.creditcabinet.notification.application.Audience
import ru.creditcabinet.tokenFor
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
class AgentNotificationAccessTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var notifications: NotificationService

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var access: AgentNotificationAccessPort

    private val user = UUID.randomUUID()
    private val agentToken by lazy { authorization("AGENT") }
    private var createdAt = Instant.parse("2026-11-26T12:00:00Z")

    @Test
    fun `agent visibility is applied before pagination and read operations and follows the current grant`() {
        val granted = UUID.randomUUID()
        val revoked = UUID.randomUUID()
        val ordinary = notification("/client/applications/1")
        val readable = notification(agentLink(granted))
        val inaccessible = notification(agentLink(revoked))
        val malformed = notification("${agentLink(granted)}/extra")
        val query = notification("${agentLink(granted)}?extra=true")
        whenever(access.check(granted, agentToken)).thenReturn(AgentNotificationAccess.ALLOWED)
        whenever(access.check(revoked, agentToken)).thenReturn(AgentNotificationAccess.DENIED)

        val first = page(agentToken, page = 0, size = 1)
        assertEquals(listOf(readable.toString()), first["items"].map { it["id"].asText() })
        assertEquals(2, first["total"].asInt())
        assertEquals(2, first["unread"].asInt())
        val second = page(agentToken, page = 1, size = 1)
        assertEquals(listOf(ordinary.toString()), second["items"].map { it["id"].asText() })
        assertEquals(2, unread(agentToken))
        for (id in listOf(inaccessible, malformed, query)) {
            mvc.post("/api/v1/notifications/$id/read") { header(HttpHeaders.AUTHORIZATION, agentToken) }.andExpect { status { isNotFound() } }
        }
        mvc.post("/api/v1/notifications/$readable/read") { header(HttpHeaders.AUTHORIZATION, agentToken) }.andExpect { status { isNoContent() } }
        assertEquals(1, readAll(agentToken))
        assertEquals(0, unread(agentToken))
        assertFalse(listOf(inaccessible, malformed, query).any(::isRead))

        whenever(access.check(granted, agentToken)).thenReturn(AgentNotificationAccess.DENIED)
        assertEquals(listOf(ordinary.toString()), page(agentToken)["items"].map { it["id"].asText() })
        mvc.post("/api/v1/notifications/$readable/read") { header(HttpHeaders.AUTHORIZATION, agentToken) }.andExpect { status { isNotFound() } }

        whenever(access.check(revoked, agentToken)).thenReturn(AgentNotificationAccess.ALLOWED)
        assertEquals(1, unread(agentToken))
        assertEquals(1, readAll(agentToken))
        assertEquals(true, isRead(inaccessible))
    }

    @Test
    fun `unavailable Credit suppresses all agent notifications and preserves unrelated rows`() {
        val allowed = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val unavailable = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val unqueried = UUID.fromString("30000000-0000-0000-0000-000000000001")
        val ordinary = notification(null)
        val previouslyAllowed = notification(agentLink(allowed))
        val hidden = notification(agentLink(unavailable))
        notification(agentLink(unqueried))
        whenever(access.check(allowed, agentToken)).thenReturn(AgentNotificationAccess.ALLOWED)
        whenever(access.check(unavailable, agentToken)).thenReturn(AgentNotificationAccess.UNAVAILABLE)
        whenever(access.check(unqueried, agentToken)).thenReturn(AgentNotificationAccess.ALLOWED)

        val list = page(agentToken)
        assertEquals(listOf(ordinary.toString()), list["items"].map { it["id"].asText() })
        assertEquals(1, list["total"].asInt())
        assertEquals(1, list["unread"].asInt())
        assertEquals(1, unread(agentToken))
        assertEquals(1, readAll(agentToken))
        mvc.post("/api/v1/notifications/$hidden/read") { header(HttpHeaders.AUTHORIZATION, agentToken) }.andExpect { status { isNotFound() } }
        assertFalse(isRead(previouslyAllowed))
        assertFalse(isRead(hidden))
    }

    @Test
    fun `client and bank roles keep ordinary notifications and never request access to agent data`() {
        val client = authorization("CLIENT")
        val bank = authorization("MANAGER")
        val ordinary = notification("/bank/applications/1")
        val protected = notification(agentLink(UUID.randomUUID()))
        for (caller in listOf(client, bank)) {
            assertEquals(listOf(ordinary.toString()), page(caller)["items"].map { it["id"].asText() })
            mvc.post("/api/v1/notifications/$protected/read") { header(HttpHeaders.AUTHORIZATION, caller) }.andExpect { status { isNotFound() } }
        }
        assertEquals(1, readAll(client))
        assertEquals(0, unread(bank))
        verifyNoInteractions(access)
        assertFalse(isRead(protected))
    }

    private fun notification(link: String?): UUID {
        val dedup = "mandate-${UUID.randomUUID()}"
        notifications.deliver(listOf(NewNotification(Audience(userId = user), NotificationType.APPLICATION_STATUS, "Статус", "Сведения", link, dedup)))
        createdAt = createdAt.plusSeconds(1)
        jdbc.update("update notifications set created_at = ? where dedup_key = ?", Timestamp.from(createdAt), dedup)
        return jdbc.queryForObject("select id from notifications where dedup_key = ?", UUID::class.java, dedup)!!
    }

    private fun isRead(id: UUID): Boolean = jdbc.queryForObject("select read_at is not null from notifications where id = ?", Boolean::class.java, id)!!

    private fun authorization(role: String): String = "Bearer ${verifier.tokenFor(user, role)}"

    private fun page(caller: String, page: Int = 0, size: Int = 20): JsonNode = mapper.readTree(
        mvc.get("/api/v1/notifications?page=$page&size=$size") { header(HttpHeaders.AUTHORIZATION, caller) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun unread(caller: String): Int = mapper.readTree(
        mvc.get("/api/v1/notifications/unread-count") { header(HttpHeaders.AUTHORIZATION, caller) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )["unread"].asInt()

    private fun readAll(caller: String): Int = mapper.readTree(
        mvc.post("/api/v1/notifications/read-all") { header(HttpHeaders.AUTHORIZATION, caller) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )["updated"].asInt()

    private fun agentLink(application: UUID): String = "/agent/applications/$application"
}
