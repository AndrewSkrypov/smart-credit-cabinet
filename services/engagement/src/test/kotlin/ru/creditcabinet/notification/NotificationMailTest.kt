package ru.creditcabinet.notification

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import ru.creditcabinet.common.mail.MailTransport
import ru.creditcabinet.common.mail.OutgoingMail
import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.notifications.NotificationEmailKind
import ru.creditcabinet.common.outbox.DurableOutbox
import ru.creditcabinet.common.outbox.OutboxSettings
import ru.creditcabinet.common.outbox.OutboxWorker
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.tokenFor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["cabinet.mail.mode=on", "cabinet.outbox.worker-enabled=false"])
@AutoConfigureMockMvc
class NotificationMailTest {
    @Autowired private lateinit var service: NotificationService

    @Autowired private lateinit var preferences: NotificationPreferences

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var outbox: DurableOutbox

    @Autowired private lateinit var handler: NotificationMailHandler

    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @MockitoBean private lateinit var recipients: MailRecipientDirectory

    @MockitoBean private lateinit var transport: MailTransport

    @MockitoBean private lateinit var people: PeopleDirectory

    private val user = UUID.randomUUID()
    private val key = "mail-${UUID.randomUUID()}"
    private val link = "/client/applications/${UUID.randomUUID()}"

    private fun notification() = NewNotification(
        Audience(userId = user),
        NotificationType.ACTION_REQUIRED,
        "ИП Секретов: 1000000",
        "R_SCORE и персональные данные",
        link,
        key,
        NotificationEmail(NotificationEmailKind.OFFER_READY, "2026-11-01T10:00:00Z"),
    )

    private fun state(): String? = jdbc.query(
        "select state from delivery_outbox where event_key=?",
        { rs, _ -> rs.getString(1) },
        NotificationEmailDelivery.eventKey(key, user),
    ).firstOrNull()

    private fun work(at: Instant) = OutboxWorker(outbox, listOf(handler), OutboxSettings(), Clock.fixed(at, ZoneOffset.UTC)).runDue()

    @Test
    fun `email is opt-in and failure never rolls back the in-app notification`() {
        preferences.save(user, NotificationPreference(true))
        whenever(recipients.email(user)).thenReturn("synthetic@example.test")
        doThrow(IllegalStateException("SMTP unavailable; secret must not be stored")).whenever(transport).send(any())
        val now = Instant.now().plusSeconds(1)
        assertEquals(1, service.deliver(listOf(notification())))
        work(now)
        assertEquals("PENDING", state())
        assertEquals(1, jdbc.queryForObject("select count(*) from notifications where recipient_id=?", Int::class.java, user))
        doNothing().whenever(transport).send(any())
        work(now.plusSeconds(30))
        assertEquals("DELIVERED", state())
        val mail = argumentCaptor<OutgoingMail>()
        verify(transport, times(2)).send(mail.capture())
        val sent = mail.lastValue
        assertTrue(sent.text.contains("Срок ответа:"))
        assertTrue(sent.text.contains(link))
        assertFalse((sent.subject + sent.text).contains("1000000"))
        assertFalse((sent.subject + sent.text).contains("Секретов"))
        assertFalse((sent.subject + sent.text).contains("R_SCORE"))
        assertEquals(0, service.deliver(listOf(notification())))
    }

    @Test
    fun `default preference creates no email job and a failed batch rolls back both channels`() {
        assertEquals(NotificationPreference(), preferences.get(user))
        assertEquals(1, service.deliver(listOf(notification())))
        assertEquals(null, state())
        val second = UUID.randomUUID()
        preferences.save(second, NotificationPreference(true))
        val valid = notification().copy(audience = Audience(userId = second), dedupKey = "valid-${UUID.randomUUID()}")
        assertFailsWith<IllegalArgumentException> {
            service.deliver(listOf(valid, notification().copy(link = "https://evil.example", dedupKey = "bad-${UUID.randomUUID()}")))
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from notifications where recipient_id=?", Int::class.java, second))
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from delivery_outbox where event_key=?",
                Int::class.java,
                NotificationEmailDelivery.eventKey(valid.dedupKey, second),
            ),
        )
    }

    @Test
    fun `preferences belong to the caller and stale updates conflict`() {
        val token = verifier.tokenFor(user, "CLIENT")
        mvc.get("/api/v1/notifications/settings") { header("Authorization", "Bearer $token") }
            .andExpect {
                status { isOk() }
                jsonPath("$.emailAvailable") { value(true) }
                jsonPath("$.emailEnabled") { value(false) }
            }
        val content = """{"emailEnabled":true,"version":0}"""
        mvc.put("/api/v1/notifications/settings") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            this.content = content
        }.andExpect {
            status { isOk() }
            jsonPath("$.version") { value(1) }
        }
        mvc.put("/api/v1/notifications/settings") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            this.content = content
        }.andExpect { status { isConflict() } }
        val other = verifier.tokenFor(UUID.randomUUID(), "CLIENT")
        mvc.get("/api/v1/notifications/settings") { header("Authorization", "Bearer $other") }
            .andExpect {
                status { isOk() }
                jsonPath("$.emailEnabled") { value(false) }
            }
        mvc.get("/api/v1/notifications/settings").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a legacy receipt with no optional email field remains replayable`() {
        val item = notification().copy(email = null)
        val tree = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(item)
        tree.remove("email")
        jdbc.update("insert into notification_receipts(event_key,payload) values (?,?::jsonb)", key, tree.toString())
        assertEquals(0, service.deliver(listOf(item)))
    }
}
