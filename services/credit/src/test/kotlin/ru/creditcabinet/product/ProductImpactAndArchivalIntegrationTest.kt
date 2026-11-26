package ru.creditcabinet.product

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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.product.domain.FinancingPurpose
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class ProductImpactAndArchivalIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var applications: ApplicationRepository

    @MockitoBean private lateinit var companies: CompanyDirectory

    private val company1 = UUID.randomUUID()
    private val company2 = UUID.randomUUID()
    private val company3 = UUID.randomUUID()
    private val snap1 = CompanySnapshot(company1, "7701000011", "ООО Импакт Один", LegalForm.LEGAL_ENTITY)
    private val snap2 = CompanySnapshot(company2, "7701000012", "ООО Импакт Два", LegalForm.LEGAL_ENTITY)
    private val snap3 = CompanySnapshot(company3, "7701000013", "ООО Импакт Три", LegalForm.LEGAL_ENTITY)

    private val riskToken by lazy { token(Roles.RISK_ADMIN) }
    private val client1Token by lazy { token(Roles.CLIENT, company = company1) }
    private val client3Token by lazy { token(Roles.CLIENT, company = company3) }
    private val agentToken by lazy { token(Roles.AGENT) }

    private val createdProductCodes = mutableListOf<String>()
    private val createdApplicationIds = mutableListOf<UUID>()

    @BeforeTest
    fun setUp() {
        whenever(companies.find(any())).thenAnswer { inv ->
            when (inv.getArgument<UUID>(0)) {
                company1 -> snap1
                company2 -> snap2
                company3 -> snap3
                else -> null
            }
        }
    }

    @AfterTest
    fun tearDown() {
        for (appId in createdApplicationIds) {
            jdbc.update("delete from pipeline_jobs where application_id = ?", appId)
            jdbc.update("delete from applications where id = ?", appId)
        }
        createdApplicationIds.clear()
        createdProductCodes.clear()
    }

    @Test
    fun `impact reports affected drafts and in-progress applications, and archival preserves existing submitted applications while blocking new ones`() {
        val code = "IMP_${UUID.randomUUID().toString().replace("-", "").take(8).uppercase()}"
        createdProductCodes += code

        // Create DRAFT product and publish to ACTIVE
        val createResp = mockMvc.post("/api/v1/bank/products") {
            header("Authorization", "Bearer $riskToken")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "code": "$code",
                  "name": "Тестовый продукт Импакт",
                  "description": "Проверка влияния архивации",
                  "purposes": ["WORKING_CAPITAL"],
                  "minAmount": 100000,
                  "maxAmount": 5000000,
                  "minTermMonths": 3,
                  "maxTermMonths": 24,
                  "baseRate": 19.50,
                  "autoApprovalLimit": 2000000,
                  "revolving": false,
                  "agentCommissionRateBps": 150,
                  "reason": "Создание тестового продукта"
                }
            """.trimIndent()
        }.andReturn().response
        assertEquals(201, createResp.status, createResp.getContentAsString(Charsets.UTF_8))

        val pubResp = mockMvc.post("/api/v1/bank/products/$code/publish") {
            header("Authorization", "Bearer $riskToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":1,"reason":"Публикация"}"""
        }.andReturn().response
        assertEquals(200, pubResp.status, pubResp.getContentAsString(Charsets.UTF_8))

        val now = Instant.now()
        val draftApp = applications.save(
            LoanApplication(
                company = snap1,
                createdBy = UUID.randomUUID(),
                terms = ApplicationTerms(
                    productCode = code,
                    purpose = FinancingPurpose.WORKING_CAPITAL,
                    amount = 500_000L,
                    termMonths = 12,
                    declaredRevenue12m = 10_000_000L,
                    declaredMonthlyDebtPayments = 0L,
                    bureauConsent = true,
                ),
                status = ApplicationStatus.DRAFT,
                productVersion = 2,
                createdAt = now,
                updatedAt = now,
                statusChangedAt = now,
            ),
        )
        val submittedApp = applications.save(
            LoanApplication(
                company = snap2,
                createdBy = UUID.randomUUID(),
                terms = ApplicationTerms(
                    productCode = code,
                    purpose = FinancingPurpose.WORKING_CAPITAL,
                    amount = 1_000_000L,
                    termMonths = 12,
                    declaredRevenue12m = 20_000_000L,
                    declaredMonthlyDebtPayments = 0L,
                    bureauConsent = true,
                ),
                status = ApplicationStatus.MANUAL_REVIEW,
                productVersion = 2,
                createdAt = now,
                updatedAt = now,
                statusChangedAt = now,
            ),
        )
        createdApplicationIds += listOf(draftApp.id, submittedApp.id)

        // Check impact preview
        val impactResp = mockMvc.get("/api/v1/bank/products/$code/impact") {
            header("Authorization", "Bearer $riskToken")
        }.andReturn().response
        assertEquals(200, impactResp.status, impactResp.getContentAsString(Charsets.UTF_8))
        val impact = json(impactResp)
        assertEquals(1L, impact["draftApplicationsCount"].asLong())
        assertEquals(1L, impact["inProgressApplicationsCount"].asLong())
        assertTrue(impact["archivalRule"].asText().contains("зафиксированной версии продукта"))

        // Archive product (version 2 -> 3)
        val archiveResp = mockMvc.post("/api/v1/bank/products/$code/archive") {
            header("Authorization", "Bearer $riskToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"expectedVersion":2,"reason":"Снятие с продаж"}"""
        }.andReturn().response
        assertEquals(200, archiveResp.status, archiveResp.getContentAsString(Charsets.UTF_8))

        // Existing submitted application remains readable with its pinned version 2
        val existingRead = mockMvc.get("/api/v1/applications/${submittedApp.id}") {
            header("Authorization", "Bearer $riskToken")
        }.andReturn().response
        assertEquals(200, existingRead.status, existingRead.getContentAsString(Charsets.UTF_8))
        assertEquals(2L, json(existingRead)["product"]["version"].asLong())

        // New draft on archived product is rejected
        val newDraftResp = mockMvc.post("/api/v1/applications") {
            header("Authorization", "Bearer $client3Token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"productCode":"$code","purpose":"WORKING_CAPITAL","amount":500000,"termMonths":12,"declaredRevenue12m":10000000}"""
        }.andReturn().response
        assertTrue(
            newDraftResp.status in listOf(400, 404, 409),
            "Expected 4xx for new application on archived product, got ${newDraftResp.status}",
        )

        // RBAC: client and agent get 403 on impact endpoint
        assertEquals(
            403,
            mockMvc.get("/api/v1/bank/products/$code/impact") {
                header("Authorization", "Bearer $client1Token")
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.get("/api/v1/bank/products/$code/impact") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
    }

    private fun json(response: MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun token(role: String, userId: UUID = UUID.randomUUID(), company: UUID? = null): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Тест Продуктов")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()
}
