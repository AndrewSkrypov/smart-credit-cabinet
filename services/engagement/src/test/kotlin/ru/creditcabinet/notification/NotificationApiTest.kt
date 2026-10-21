package ru.creditcabinet.notification

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.tokenFor
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class NotificationApiTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @MockitoBean private lateinit var people: PeopleDirectory

    private val companyId = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val accountant = UUID.randomUUID()
    private val underwriter = UUID.randomUUID()

    @BeforeTest
    fun people() {
        whenever(people.ofCompany(companyId)).thenReturn(listOf(owner, accountant))
        whenever(people.withRole("UNDERWRITER")).thenReturn(listOf(underwriter))
    }

    private fun deliver(body: String) =
        mockMvc.post("/internal/v1/notifications") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn().response

    private fun item(address: String, dedup: String, link: String = "/client/applications/1") =
        """{"$address","type":"ACTION_REQUIRED","title":"Банк просит дополнить заявку № 1","body":"Откройте заявку","link":"$link","dedupKey":"$dedup"}"""

    private fun unread(user: UUID, role: String) =
        mapper.readTree(
            mockMvc.get("/api/v1/notifications/unread-count") {
                header("Authorization", "Bearer ${verifier.tokenFor(user, role)}")
            }.andReturn().response.contentAsString,
        )["unread"].asInt()

    @Test
    fun `a company address reaches each of its people once, however often it is delivered`() {
        val dedup = "event-${UUID.randomUUID()}"
        val first = deliver("""{"items":[${item("recipientCompanyId\":\"$companyId", dedup)}]}""")
        assertEquals(200, first.status, first.contentAsString)
        assertEquals(2, mapper.readTree(first.contentAsString)["stored"].asInt())
        assertEquals(0, mapper.readTree(deliver("""{"items":[${item("recipientCompanyId\":\"$companyId", dedup)}]}""").contentAsString)["stored"].asInt())
        assertEquals(1, unread(owner, "CLIENT"))
        assertEquals(1, unread(accountant, "CLIENT"))
    }

    @Test
    fun `a role address reaches the bank staff, each reads only their own`() {
        deliver("""{"items":[${item("recipientRole\":\"UNDERWRITER", "review-${UUID.randomUUID()}", "/bank/applications/1")}]}""")
        val token = verifier.tokenFor(underwriter, "UNDERWRITER")
        val page = mapper.readTree(
            mockMvc.get("/api/v1/notifications") {
                header("Authorization", "Bearer $token")
            }.andReturn().response.getContentAsString(Charsets.UTF_8),
        )
        val id = page["items"][0]["id"].asText()
        assertEquals("/bank/applications/1", page["items"][0]["link"].asText())

        val stranger = verifier.tokenFor(UUID.randomUUID(), "UNDERWRITER")
        mockMvc.post("/api/v1/notifications/$id/read") { header("Authorization", "Bearer $stranger") }.andExpect { status { isNotFound() } }
        mockMvc.post("/api/v1/notifications/$id/read") { header("Authorization", "Bearer $token") }.andExpect { status { isNoContent() } }
        assertEquals(0, unread(underwriter, "UNDERWRITER"))
        mockMvc.get("/api/v1/notifications").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a link outside the cabinet or an address with two recipients is refused`() {
        assertEquals(400, deliver("""{"items":[${item("recipientId\":\"$owner", "x-${UUID.randomUUID()}", "https://evil.example")}]}""").status)
        assertEquals(
            400,
            deliver("""{"items":[{"recipientId":"$owner","recipientRole":"UNDERWRITER","type":"NEW_REVIEW","title":"t","body":"b","dedupKey":"y"}]}""").status,
        )
    }
}
