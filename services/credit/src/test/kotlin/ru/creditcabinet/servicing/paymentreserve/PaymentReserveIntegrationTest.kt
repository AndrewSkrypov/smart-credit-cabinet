package ru.creditcabinet.servicing.paymentreserve

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.pipeline.MutableClock
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStore
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveInputs
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["DEMO_ACCOUNTS=true"])
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
class PaymentReserveIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var applications: ApplicationRepository

    @Autowired private lateinit var loanStore: LoanAccountStore

    @MockitoBean private lateinit var agentAccess: AgentAccessPort

    private val agentId = UUID.randomUUID()
    private val createdLoans = mutableListOf<LoanAccount>()
    private val today get() = clock.instant().atZone(ZoneId.of("Europe/Moscow")).toLocalDate()

    @AfterTest
    fun tearDown() {
        createdLoans.forEach {
            jdbc.update("delete from loan_accounts where id = ?", it.id)
            jdbc.update("delete from applications where id = ?", it.applicationId)
        }
    }

    @Test
    fun `receipt and low limit produce reproducible outcomes and cannot issue a tranche`() {
        val account = line(limit = 160_000L)
        val client = token(Roles.CLIENT, account.companyId)
        val path = path(account)
        assertEquals(204, get(path, client).status)

        val initial = json(put(path, client, inputs()), 200)
        assertEquals("PENDING", initial["receiptStatus"].asText())
        assertFalse(initial["canDraw"].asBoolean())
        assertEquals(409, confirm(path, client, initial["version"].asLong()).status)

        val delayed = json(receipt(path, client, "bank-1", "DELAYED"), 200)
        assertEquals(-100_000L, delayed["projectedBalance"].asLong())
        assertEquals(130_000L, delayed["requiredAmount"].asLong())
        assertEquals(80_000L, delayed["remainingLimit"].asLong())
        assertEquals(50_000L, delayed["shortfall"].asLong())
        assertFalse(delayed["canDraw"].asBoolean())
        assertEquals(409, confirm(path, client, delayed["version"].asLong()).status)

        val received = json(receipt(path, client, "bank-2", "RECEIVED"), 200)
        assertEquals(40_000L, received["projectedBalance"].asLong())
        assertEquals(0L, received["requiredAmount"].asLong())
        assertEquals(409, confirm(path, client, received["version"].asLong()).status)
        assertEquals(409, receipt(path, client, "bank-3", "DELAYED").status)
        assertEquals(received, json(get(path, client), 200))
        assertEquals(1, trancheCount(account))
    }

    @Test
    fun `event retries and concurrent confirmations create only one atomic draw`() {
        val account = line()
        val client = token(Roles.CLIENT, account.companyId)
        val path = path(account)
        val initial = json(put(path, client, inputs()), 200)
        val delayed = json(receipt(path, client, "bank-1", "DELAYED"), 200)
        val version = delayed["version"].asLong()
        assertTrue(delayed["canDraw"].asBoolean())
        assertEquals(delayed, json(receipt(path, client, "bank-1", "DELAYED"), 200))
        assertEquals(409, receipt(path, client, "bank-1", "RECEIVED").status)
        assertEquals(409, confirm(path, client, initial["version"].asLong()).status)

        val barrier = CyclicBarrier(2)
        val workers = Executors.newFixedThreadPool(2)
        val confirmations = try {
            List(2) {
                workers.submit<MockHttpServletResponse> {
                    barrier.await(5, TimeUnit.SECONDS)
                    confirm(path, client, version)
                }
            }.map { json(it.get(10, TimeUnit.SECONDS), 200) }
        } finally {
            workers.shutdownNow()
        }
        assertEquals(confirmations[0], confirmations[1])
        assertEquals(130_000L, confirmations[0]["drawnAmount"].asLong())
        assertEquals(30_000L, confirmations[0]["projectedBalance"].asLong())
        assertEquals(2, trancheCount(account))
        assertEquals(1, jdbc.queryForObject("select count(*) from payment_reserve_receipt_events where loan_id = ?", Int::class.java, account.id))
        assertEquals(confirmations[0], json(confirm(path, client, version), 200))
        assertEquals(409, put(path, client, inputs().copy(targetBuffer = 31_000L)).status)
        assertEquals(2, trancheCount(account))
    }

    @Test
    fun `owner and synthetic company gates protect the plan and agent sees only a summary with active access`() {
        val account = line()
        val client = token(Roles.CLIENT, account.companyId)
        val other = token(Roles.CLIENT, UUID.randomUUID())
        val agent = token(Roles.AGENT, userId = agentId)
        val path = path(account)
        val agentPath = "/api/v1/agent/applications/${account.applicationId}/payment-reserve"
        val company = requireNotNull(applications.find(account.applicationId)).company
        whenever(agentAccess.checkAccess(any(), any())).thenReturn(AgentAccessDecision(true, company))

        assertEquals(204, get(agentPath, agent).status)
        assertEquals(404, put(path, other, inputs()).status)
        assertEquals(200, put(path, client, inputs()).status)
        assertEquals(403, receipt(path, agent, "other-1", "DELAYED").status)
        assertEquals(404, receipt(path, other, "other-1", "DELAYED").status)
        assertEquals(404, get(path, other).status)
        json(receipt(path, client, "bank-1", "DELAYED"), 200)

        val summary = json(get(agentPath, agent), 200)
        assertEquals("CLIENT_CONFIRM_DRAW", summary["action"].asText())
        assertEquals(setOf("version", "receiptStatus", "paymentDate", "action"), summary.fieldNames().asSequence().toSet())
        whenever(agentAccess.checkAccess(any(), any())).thenReturn(AgentAccessDecision(false, null))
        assertEquals(404, get(agentPath, agent).status)

        val realAccount = line(inn = "7701234567")
        assertEquals(404, put(path(realAccount), token(Roles.CLIENT, realAccount.companyId), inputs()).status)
        assertEquals(1, trancheCount(account))
    }

    private fun line(limit: Long = 2_000_000L, inn: String = "0001001014"): LoanAccount {
        val company = CompanySnapshot(UUID.randomUUID(), inn, "ООО Тест", LegalForm.LEGAL_ENTITY)
        val now = clock.instant()
        val application = applications.save(
            LoanApplication(
                company = company,
                createdBy = agentId,
                terms = ApplicationTerms("OVERDRAFT", FinancingPurpose.CASH_GAP, amount = limit, termMonths = 12, bureauConsent = true),
                status = ApplicationStatus.DISBURSED,
                submittedAt = now,
                productCatalogSnapshot = mapOf("OVERDRAFT" to 1L),
                statusChangedAt = now,
                createdAt = now,
                updatedAt = now,
                agentId = agentId,
                agentName = "Тест",
            ),
        )
        return LoanAccount.open(
            applicationId = application.id,
            companyId = company.id,
            contractNumber = "RES-${application.number}",
            approvedLimit = limit,
            termMonths = 12,
            rate = BigDecimal("20"),
            revolving = true,
            disbursedAt = now,
            maturityDate = today.plusMonths(12),
            firstPaymentBaseDate = today,
        ).also {
            loanStore.create(it)
            createdLoans += it
        }
    }

    private fun inputs() = PaymentReserveInputs(1_900_000L, 140_000L, 2_000_000L, 30_000L, today, today.plusDays(1))

    private fun path(account: LoanAccount) = "/api/v1/loans/${account.id}/payment-reserve"

    private fun token(role: String, company: UUID? = null, userId: UUID = UUID.randomUUID()): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()

    private fun get(path: String, token: String) =
        mockMvc.get(path) { header("Authorization", "Bearer $token") }.andReturn().response

    private fun put(path: String, token: String, inputs: PaymentReserveInputs) =
        mockMvc.put(path) {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(inputs)
        }.andReturn().response

    private fun receipt(path: String, token: String, eventId: String, status: String) =
        post("$path/receipt-event", token, mapOf("eventId" to eventId, "status" to status))

    private fun confirm(path: String, token: String, version: Long) =
        post("$path/confirm-draw", token, mapOf("expectedVersion" to version))

    private fun post(path: String, token: String, body: Map<String, Any>) =
        mockMvc.post(path) {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
        }.andReturn().response

    private fun json(response: MockHttpServletResponse, expectedStatus: Int): JsonNode {
        val body = response.getContentAsString(Charsets.UTF_8)
        assertEquals(expectedStatus, response.status, body)
        return mapper.readTree(body)
    }

    private fun trancheCount(account: LoanAccount): Int =
        requireNotNull(jdbc.queryForObject("select count(*) from loan_tranches where loan_account_id = ?", Int::class.java, account.id))
}
