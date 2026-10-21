package ru.creditcabinet.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import java.util.Date
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Runs against the local Postgres with the Flyway migrations applied (as CI does). identity is
// replaced by a mock of the company directory; tokens are signed with the shared secret.
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationApiIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var verifier: JwtVerifier

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @MockitoBean
    private lateinit var companies: CompanyDirectory

    private val companyId = UUID.randomUUID()
    private val otherCompanyId = UUID.randomUUID()
    private val client by lazy { token("CLIENT", companyId) }
    private val colleague by lazy { token("CLIENT", companyId, canSign = false) }
    private val stranger by lazy { token("CLIENT", otherCompanyId) }
    private val manager by lazy { token("MANAGER") }
    private val risk by lazy { token("RISK_ADMIN") }

    @BeforeTest
    fun companies() {
        whenever(companies.find(any())).thenAnswer { CompanySnapshot(it.getArgument(0), "000100000004", "ИП Тест", LegalForm.ENTREPRENEUR) }
    }

    private fun token(role: String, company: UUID? = null, canSign: Boolean = true): String =
        Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Тест")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null && canSign)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()

    private fun body(product: String = "WORKING_CAPITAL", purpose: String = "WORKING_CAPITAL", amount: Long = 1_500_000, complete: Boolean = true) =
        """{"productCode":"$product","purpose":"$purpose","amount":$amount,"termMonths":12""" +
            (if (complete) ""","declaredRevenue12m":24000000,"declaredMonthlyDebtPayments":50000,"bureauConsent":true}""" else "}")

    private fun json(response: org.springframework.mock.web.MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun create(token: String = client, requestBody: String = body()): JsonNode {
        val response = mockMvc.post("/api/v1/applications") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = requestBody
        }.andReturn().response
        assertEquals(201, response.status, response.getContentAsString(Charsets.UTF_8))
        return json(response)
    }

    private fun submit(id: String, token: String = client, key: String? = null) =
        mockMvc.post("/api/v1/applications/$id/submit") {
            header("Authorization", "Bearer $token")
            key?.let { header("Idempotency-Key", it) }
        }.andReturn().response

    private fun get(id: String, token: String) = mockMvc.get("/api/v1/applications/$id") { header("Authorization", "Bearer $token") }.andReturn().response

    @Test
    fun `a draft is created, completed, sent once despite a repeated request and then withdrawn`() {
        val draft = create(requestBody = body(complete = false))
        val id = draft["id"].asText()
        assertEquals("DRAFT", draft["status"].asText())
        assertTrue(draft["number"].asLong() > 1000)
        assertEquals(listOf("SUBMIT", "WITHDRAW"), draft["allowedActions"].map { it.asText() })
        assertTrue(draft["submissionProblems"].any { it.asText().startsWith("Укажите выручку") })

        val refused = submit(id)
        assertEquals(400, refused.status)
        assertTrue(json(refused)["message"].asText().contains("бюро кредитных историй"))

        mockMvc.put("/api/v1/applications/$id") {
            header("Authorization", "Bearer $client")
            contentType = MediaType.APPLICATION_JSON
            content = body()
        }.andExpect { status { isOk() } }

        val first = submit(id, key = "submit-$id")
        assertEquals(200, first.status, first.contentAsString)
        assertEquals("SUBMITTED", json(first)["status"].asText())
        val again = submit(id, key = "submit-$id")
        assertEquals(200, again.status)
        assertEquals(409, submit(id).status, "without the key a second submit is a conflict")

        val events = json(mockMvc.get("/api/v1/applications/$id/events") { header("Authorization", "Bearer $client") }.andReturn().response)
        assertEquals(listOf("CREATED", "SUBMIT"), events.map { it["event"].asText() })
        assertEquals(1, jdbc.queryForObject("select count(*) from pipeline_jobs where application_id = ?::uuid and step = 'DATA_CHECK'", Int::class.java, id))

        mockMvc.put("/api/v1/applications/$id") {
            header("Authorization", "Bearer $client")
            contentType = MediaType.APPLICATION_JSON
            content = body()
        }.andExpect { status { isConflict() } }

        val withdrawn = json(
            mockMvc.post("/api/v1/applications/$id/withdraw") {
                header("Authorization", "Bearer $colleague")
                contentType = MediaType.APPLICATION_JSON
                content = """{"comment":"Решили отложить покупку"}"""
            }.andReturn().response,
        )
        assertEquals("WITHDRAWN", withdrawn["status"].asText())
        assertEquals(0, jdbc.queryForObject("select count(*) from pipeline_jobs where application_id = ?::uuid and status = 'PENDING'", Int::class.java, id))
    }

    @Test
    fun `one application in progress per product, a new one after the first is withdrawn`() {
        val first = create()["id"].asText()
        mockMvc.post("/api/v1/applications") {
            header("Authorization", "Bearer $client")
            contentType = MediaType.APPLICATION_JSON
            content = body()
        }.andExpect { status { isConflict() } }
        create(requestBody = body(product = "OVERDRAFT", purpose = "CASH_GAP", amount = 500_000))
        mockMvc.post("/api/v1/applications/$first/withdraw") { header("Authorization", "Bearer $client") }.andExpect { status { isOk() } }
        create()
    }

    @Test
    fun `a product for another purpose is refused with the reason`() {
        val response = mockMvc.post("/api/v1/applications") {
            header("Authorization", "Bearer $client")
            contentType = MediaType.APPLICATION_JSON
            content = body(product = "OVERDRAFT", purpose = "EQUIPMENT", amount = 500_000)
        }.andReturn().response
        assertEquals(400, response.status)
        assertEquals("Продукт «Овердрафт» не подходит для этой цели", json(response)["message"].asText())
    }

    @Test
    fun `another company sees nothing, the bank sees no drafts and changes nothing of the client's`() {
        val id = create()["id"].asText()
        assertEquals(404, get(id, stranger).status)
        assertEquals(404, submit(id, stranger).status)
        assertEquals(404, get(id, manager).status, "a draft is the client's own")
        assertTrue(
            json(
                mockMvc.get("/api/v1/applications") {
                    header("Authorization", "Bearer $manager")
                }.andReturn().response,
            ).none { it["id"].asText() == id },
        )

        assertEquals(200, submit(id).status)
        val seen = json(get(id, manager))
        assertEquals("SUBMITTED", seen["status"].asText())
        assertEquals(emptyList(), seen["allowedActions"].map { it.asText() }, "nothing for a manager until manual review")
        assertEquals(200, get(id, risk).status)
        assertEquals(403, submit(id, manager).status)
        mockMvc.post("/api/v1/applications") {
            header("Authorization", "Bearer $manager")
            contentType = MediaType.APPLICATION_JSON
            content = body()
        }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/applications/$id").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `documents are checked by content and given only to the company and the bank`() {
        val id = create(requestBody = body(amount = 4_000_000))["id"].asText()
        val required = json(get(id, client))["requiredDocuments"]
        assertEquals(listOf("TAX_RETURN"), required.map { it["type"].asText() })

        val fake = MockMultipartFile("file", "declaration.pdf", "application/pdf", "MZ not a pdf".toByteArray())
        mockMvc.multipart("/api/v1/applications/$id/documents") {
            file(fake)
            param("type", "TAX_RETURN")
            header("Authorization", "Bearer $client")
        }.andExpect { status { isBadRequest() } }

        val pdf = MockMultipartFile("file", "Декларация 2025.pdf", "application/pdf", "%PDF-1.7 synthetic".toByteArray())
        val uploaded = json(
            mockMvc.multipart("/api/v1/applications/$id/documents") {
                file(pdf)
                param("type", "TAX_RETURN")
                header("Authorization", "Bearer $client")
            }.andReturn().response,
        )
        val documentId = uploaded["id"].asText()
        assertTrue(json(get(id, client))["requiredDocuments"][0]["uploaded"].asBoolean())

        mockMvc.get("/api/v1/applications/$id/documents/$documentId") { header("Authorization", "Bearer $stranger") }.andExpect { status { isNotFound() } }
        assertEquals(200, submit(id).status)
        mockMvc.get("/api/v1/applications/$id/documents/$documentId") { header("Authorization", "Bearer $manager") }
            .andExpect {
                status { isOk() }
                header { string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")) }
            }
        mockMvc.delete("/api/v1/applications/$id/documents/$documentId") { header("Authorization", "Bearer $client") }.andExpect { status { isConflict() } }
    }

    @Test
    fun `the needs wizard suggests products for the purpose`() {
        val matches = json(
            mockMvc.post("/api/v1/products/match") {
                header("Authorization", "Bearer $client")
                contentType = MediaType.APPLICATION_JSON
                content = """{"purpose":"EQUIPMENT","amount":2000000,"termMonths":36}"""
            }.andReturn().response,
        )
        assertEquals(listOf("GOV_PROGRAM", "INVESTMENT"), matches.map { it["product"]["code"].asText() })
    }
}
