package ru.creditcabinet.document

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.InfoItem
import ru.creditcabinet.application.domain.InfoRequest
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.integration.CompanySources
import ru.creditcabinet.product.domain.FinancingPurpose
import java.time.Clock
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["cabinet.outbox.worker-enabled=false"])
@AutoConfigureMockMvc
class PrivateBankStatementIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var applications: ApplicationRepository

    @Autowired private lateinit var infoRequests: InfoRequests

    @Autowired private lateinit var documents: DocumentRepository

    @Autowired private lateinit var journal: ApplicationJournal

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: Clock

    @MockitoBean private lateinit var agentAccess: AgentAccessPort

    @MockitoBean private lateinit var scanner: DocumentScanner

    @MockitoBean private lateinit var sources: CompanySources

    private val customer = CompanySnapshot(UUID.randomUUID(), "000100000004", "Синтетическая компания", LegalForm.ENTREPRENEUR)
    private val otherCompany = UUID.randomUUID()
    private val agentId = UUID.randomUUID()
    private val clientToken by lazy { token(Roles.CLIENT, company = customer.id) }
    private val otherClientToken by lazy { token(Roles.CLIENT, company = otherCompany) }
    private val agentToken by lazy { token(Roles.AGENT, user = agentId) }
    private val bankToken by lazy { token(Roles.MANAGER) }
    private val pdf = "%PDF-1.4\n% synthetic private statement\n%%EOF".toByteArray()
    private val fileName = "private-statement-synthetic.pdf"

    @Test
    fun `client and bank can read private statement and agent gets only requirement progress`() {
        val application = prepare()
        val agentPath = "/api/v1/agent/applications/${application.id}"
        val path = "/api/v1/applications/${application.id}"
        val before = get(agentPath, agentToken)
        assertEquals(200, before.status, before.contentAsString)
        assertFalse(requirementSatisfied(json(before)))

        val uploaded = upload(path, clientToken)
        assertEquals(201, uploaded.status, uploaded.getContentAsString(Charsets.UTF_8))
        val documentId = json(uploaded)["id"].asText()
        assertTrue(documents.find(application.id, UUID.fromString(documentId))!!.privateToBank)
        val privateAudit = journal.list(application.id).single { it.event == JournalNote.PRIVATE_DOCUMENT_ADDED.name }
        assertTrue(privateAudit.payload.isEmpty())

        for (reader in listOf(clientToken, bankToken)) {
            val detail = get(path, reader)
            assertEquals(200, detail.status, detail.contentAsString)
            assertTrue(json(detail)["documents"].any { it["id"].asText() == documentId && it["fileName"].asText() == fileName })
            val download = get("$path/documents/$documentId", reader)
            assertEquals(200, download.status)
            assertContentEquals(pdf, download.contentAsByteArray)
        }
        val agentDetail = get(agentPath, agentToken)
        assertEquals(200, agentDetail.status, agentDetail.contentAsString)
        assertTrue(requirementSatisfied(json(agentDetail)))
        assertEquals(0, json(agentDetail)["documents"].size())
        val agentDocuments = get("$agentPath/documents", agentToken)
        assertEquals(200, agentDocuments.status, agentDocuments.contentAsString)
        assertEquals(0, json(agentDocuments).size())
        assertEquals(404, get("$agentPath/documents/$documentId", agentToken).status)
        assertEquals(403, get("$path/documents/$documentId", agentToken).status)
        assertEquals(404, get("$path/documents/$documentId", otherClientToken).status)
        val events = get("$agentPath/events", agentToken)
        assertEquals(200, events.status, events.contentAsString)
        assertFalse(json(events).any { it["event"].asText() == "PRIVATE_DOCUMENT_ADDED" })

        val attemptToShare = mvc.post("$path/messages") {
            header("Authorization", "Bearer $clientToken")
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"text":"Synthetic test attachment","documentIds":["$documentId"]}"""
        }.andReturn().response
        assertEquals(404, attemptToShare.status)
        val chat = get("$agentPath/messages", agentToken)
        assertEquals(200, chat.status, chat.contentAsString)
        for (response in listOf(agentDetail, events, chat, attemptToShare)) {
            assertFalse(response.contentAsString.contains(documentId))
            assertFalse(response.contentAsString.contains(fileName))
        }

        val requestId = checkNotNull(infoRequests.open(application.id)).id
        assertEquals(409, provideInfo(agentPath, agentToken).status)
        assertEquals(requestId, infoRequests.open(application.id)?.id)
        assertEquals(200, provideInfo(path, clientToken).status)
        assertEquals(
            0,
            jdbc.queryForObject(
                "select count(*) from delivery_outbox where payload::text like ? or payload::text like ?",
                Int::class.java,
                "%$documentId%",
                "%$fileName%",
            ),
        )
    }

    @Test
    fun `private upload requires same company client requested bank statement and PDF`() {
        val application = prepare()
        val path = "/api/v1/applications/${application.id}"
        val agentPath = "/api/v1/agent/applications/${application.id}"
        assertEquals(404, upload(path, otherClientToken).status)
        assertEquals(403, upload(path, bankToken).status)
        assertEquals(403, upload(agentPath, agentToken).status)
        assertEquals(400, upload(path, clientToken, type = DocumentType.OTHER).status)
        assertEquals(400, upload(path, clientToken, name = "synthetic.png").status)
        assertEquals(400, upload(path, clientToken, bytes = "not a PDF".toByteArray()).status)
        val request = checkNotNull(infoRequests.open(application.id))
        infoRequests.resolve(request.id, clock.instant(), null)
        assertEquals(409, upload(path, clientToken).status)
        infoRequests.save(request.copy(id = UUID.randomUUID(), items = listOf(InfoItem(documentType = DocumentType.OTHER))))
        assertEquals(409, upload(path, clientToken).status)
        assertTrue(documents.list(application.id).isEmpty())
    }

    @Test
    fun `client can read a returned draft while its agent manages ordinary documents`() {
        val application = prepare(ApplicationStatus.DRAFT)
        val agentPath = "/api/v1/agent/applications/${application.id}"
        val path = "/api/v1/applications/${application.id}"
        assertEquals(200, get(path, clientToken).status)
        val agentUpload = upload(agentPath, agentToken, DocumentType.OTHER, privateToBank = false)
        assertEquals(201, agentUpload.status)
        val documentId = json(agentUpload)["id"].asText()

        assertEquals(404, upload(path, clientToken, DocumentType.OTHER, privateToBank = false).status)
        val attemptedRemoval = mvc.delete("$path/documents/$documentId") {
            header("Authorization", "Bearer $clientToken")
        }.andReturn().response
        assertEquals(404, attemptedRemoval.status)
        assertEquals(200, get("$agentPath/documents/$documentId", agentToken).status)
    }

    private fun prepare(status: ApplicationStatus = ApplicationStatus.INFO_REQUESTED): LoanApplication {
        val now = clock.instant()
        val application = applications.save(
            LoanApplication(
                company = customer,
                createdBy = agentId,
                terms = ApplicationTerms(
                    productCode = "WORKING_CAPITAL",
                    purpose = FinancingPurpose.WORKING_CAPITAL,
                    amount = 2_000_000L,
                    termMonths = 12,
                ),
                status = status,
                submittedAt = now.minusSeconds(20).takeIf { status == ApplicationStatus.INFO_REQUESTED },
                productCatalogSnapshot = mapOf("WORKING_CAPITAL" to 1L),
                clientHandoffAt = now.minusSeconds(25),
                createdAt = now.minusSeconds(30),
                statusChangedAt = now.minusSeconds(10),
                updatedAt = now.minusSeconds(10),
                agentId = agentId,
                agentName = "Синтетический агент",
            ),
        )
        whenever(agentAccess.checkAccess(agentId, customer.id)).thenReturn(AgentAccessDecision(allowed = true, company = customer))
        whenever(sources.bank(customer.inn)).thenReturn(BankInfo(client = true, settlementAccount = null, monthlyInflows = emptyList()))
        if (status == ApplicationStatus.INFO_REQUESTED) {
            infoRequests.save(
                InfoRequest(
                    applicationId = application.id,
                    items = listOf(InfoItem(documentType = DocumentType.BANK_STATEMENT)),
                    comment = null,
                    deadline = now.plusSeconds(3600),
                    requestedBy = Actor.SYSTEM,
                    createdAt = now.minusSeconds(10),
                ),
            )
        }
        return application
    }

    private fun requirementSatisfied(response: JsonNode): Boolean =
        response["process"]["infoRequest"]["items"][0]["satisfied"].asBoolean()

    private fun upload(
        path: String,
        auth: String,
        type: DocumentType = DocumentType.BANK_STATEMENT,
        name: String = fileName,
        bytes: ByteArray = pdf,
        privateToBank: Boolean = true,
    ) = mvc.multipart("$path/documents") {
        header("Authorization", "Bearer $auth")
        param("type", type.name)
        param("privateToBank", privateToBank.toString())
        file(MockMultipartFile("file", name, "application/pdf", bytes))
    }.andReturn().response

    private fun get(path: String, auth: String) =
        mvc.get(path) { header("Authorization", "Bearer $auth") }.andReturn().response

    private fun provideInfo(path: String, auth: String) =
        mvc.post("$path/info-provided") { header("Authorization", "Bearer $auth") }.andReturn().response

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsByteArray)

    private fun token(role: String, company: UUID? = null, user: UUID = UUID.randomUUID()): String =
        Jwts.builder().subject(user.toString()).claim(TokenClaims.ROLE, role)
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .expiration(Date(System.currentTimeMillis() + 600_000))
            .signWith(verifier.signingKey).compact()
}
