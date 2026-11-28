package ru.creditcabinet.financing.scenario

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
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
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.integration.domain.CompanySources
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.ProductCatalog
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Clock
import java.util.Date
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["cabinet.outbox.worker-enabled=false"])
@AutoConfigureMockMvc
class FinancingScenarioIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var applications: ApplicationRepository

    @Autowired private lateinit var documents: DocumentRepository

    @Autowired private lateinit var journal: ApplicationJournal

    @Autowired private lateinit var files: FileStore

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: Clock

    @Autowired private lateinit var products: ProductCatalog

    @Autowired private lateinit var infoRequests: InfoRequests

    @MockitoBean private lateinit var mandates: AgentAccessPort

    @MockitoBean private lateinit var sources: CompanySources

    private val company = CompanySnapshot(UUID.randomUUID(), "000100000004", "Синтетическая компания", LegalForm.ENTREPRENEUR)
    private val agentId = UUID.randomUUID()
    private val agent by lazy { token(Roles.AGENT, agentId) }
    private val client by lazy { token(Roles.CLIENT, companyId = company.id) }
    private val bank by lazy { token(Roles.UNDERWRITER) }

    @Test
    fun `preview compares the fifth and sixth days without writing draft or packet`() {
        val draft = draft()
        val path = agentPath(draft.id)
        val previewPath = "$path/financing-scenario/preview"
        val original = applications.find(draft.id)
        val originalJournal = journal.list(draft.id)
        val originalDocuments = documents.list(draft.id)
        val originalDeliveryCount = jdbc.queryForObject("select count(*) from delivery_outbox", Long::class.java)

        fun web(delay: Int, amount: Long = 2_000_000) = """
            {"mode":"WEB_FORM","openingBalance":150000,"expectedReceiptAmount":140000,
             "expectedReceiptDate":"2026-12-10","paymentDate":"2026-12-15","delayDays":$delay,"alternativeTermMonths":24,
             "draftTermsOverride":{"amount":$amount,"baseTermMonths":12}}
        """.trimIndent()

        val dayFive = snapshot(post(previewPath, agent, web(5)))
        val daySix = snapshot(post(previewPath, agent, web(6)))
        assertTrue(dayFive.at("/inputsUsed/receiptIncludedByPayment").asBoolean())
        assertFalse(daySix.at("/inputsUsed/receiptIncludedByPayment").asBoolean())
        assertEquals(5, daySix.at("/inputsUsed/lastIncludedDelayDays").asInt())
        assertTrue(daySix["preview"].asBoolean())
        assertEquals("credit", daySix["source"].asText())
        assertEquals("/agent/applications/${draft.id}#draft-financing", daySix["cabinetPath"].asText())
        assertTrue(daySix.at("/basis/savedScenarioRevision").isNull)
        val rate = checkNotNull(products.find(draft.terms.productCode)).baseRate
        for ((i, term) in listOf(12, 24).withIndex()) {
            val calculation = LoanMath.calculate(2_000_000, rate, term, false)
            val before = dayFive["options"][i]
            val after = daySix["options"][i]
            assertEquals(term, after["termMonths"].asInt())
            assertEquals(calculation.monthlyPayment.toPlainString(), after["monthlyPaymentRub"].asText())
            assertEquals(calculation.overpayment.toPlainString(), after["totalInterestRub"].asText())
            assertEquals(BigDecimal("150000").subtract(calculation.monthlyPayment).toPlainString(), after["balanceOnPaymentDateRub"].asText())
            assertEquals(BigDecimal("290000").subtract(calculation.monthlyPayment).toPlainString(), before["balanceOnPaymentDateRub"].asText())
        }
        assertEquals("2100000", snapshot(post(previewPath, agent, web(6, 2_100_000))).at("/inputsUsed/loanAmountRub").asText())
        assertEquals(400, post(previewPath, agent, web(366)).status)
        assertEquals(403, post(previewPath, client, web(6)).status)
        assertEquals(404, post(previewPath, token(Roles.AGENT), web(6)).status)
        assertEquals(204, get("$path/financing-scenario", agent).status)
        assertEquals(original, applications.find(draft.id))
        assertEquals(originalJournal, journal.list(draft.id))
        assertEquals(originalDocuments, documents.list(draft.id))
        assertEquals(originalDeliveryCount, jdbc.queryForObject("select count(*) from delivery_outbox", Long::class.java))

        val savedRequest = """{"mode":"SAVED_SCENARIO","delayDays":6,"alternativeTermMonths":24}"""
        assertEquals(409, post(previewPath, agent, savedRequest).status)
        assertEquals(400, post(previewPath, agent, web(6).replace("WEB_FORM", "SAVED_SCENARIO")).status)
        val saved = snapshot(putScenario(path, delayDays = 5))
        val fromSaved = snapshot(post(previewPath, agent, savedRequest))
        assertEquals("1", fromSaved.at("/basis/savedScenarioRevision").asText())
        assertEquals(daySix["inputsUsed"], fromSaved["inputsUsed"])
        assertEquals(daySix["options"], fromSaved["options"])
        assertEquals(saved, snapshot(get("$path/financing-scenario", agent)))
        assertEquals(200, post("$path/request-confirmation", agent).status)
        assertEquals(409, post(previewPath, agent, savedRequest).status)
    }

    @Test
    fun `agent MCP views respect revoked access and only report statement readiness`() {
        val draft = draft()
        whenever(mandates.listCompanyIds(agentId)).thenReturn(listOf(company.id))
        val summaryPath = "/api/v1/agent/mcp/applications/${draft.id}/summary"
        val prioritiesPath = "/api/v1/agent/mcp/priorities"
        val summary = snapshot(get(summaryPath, agent))
        assertEquals("NOT_REQUESTED", summary["statementRequirement"].asText())
        assertEquals(summary["application"], snapshot(get("$prioritiesPath?limit=1", agent))["items"].single())
        assertEquals(400, get("$prioritiesPath?limit=21", agent).status)
        assertEquals(403, get(summaryPath, bank).status)
        assertEquals(404, get(summaryPath, token(Roles.AGENT)).status)

        val now = clock.instant()
        applications.save(
            draft.copy(
                status = ApplicationStatus.INFO_REQUESTED,
                submittedAt = now,
                statusChangedAt = now,
                productCatalogSnapshot = mapOf(draft.terms.productCode to draft.productVersion),
            ),
        )
        infoRequests.save(
            InfoRequest(
                applicationId = draft.id,
                items = listOf(InfoItem(documentType = DocumentType.BANK_STATEMENT)),
                comment = "synthetic bank comment",
                deadline = now.plusSeconds(3600),
                requestedBy = Actor(ActorType.UNDERWRITER, UUID.randomUUID()),
                createdAt = now.minusSeconds(1),
            ),
        )
        assertEquals("OPEN", snapshot(get(summaryPath, agent))["statementRequirement"].asText())
        val documentId = document(draft.id, privateToBank = true)
        val ready = snapshot(get(summaryPath, agent))
        assertEquals("SATISFIED", ready["statementRequirement"].asText())
        assertFalse(ready.toString().contains(documentId.toString()))
        assertFalse(ready.toString().contains("synthetic.pdf"))
        assertFalse(ready.toString().contains("synthetic bank comment"))

        whenever(mandates.checkAccess(agentId, company.id)).thenReturn(AgentAccessDecision(allowed = false))
        whenever(mandates.listCompanyIds(agentId)).thenReturn(emptyList())
        assertEquals(404, get(summaryPath, agent).status)
        assertTrue(snapshot(get(prioritiesPath, agent))["items"].isEmpty)
    }

    @Test
    fun `the client sees only handed revisions and an exact confirmation creates one submission`() {
        val application = draft()
        val agentPath = agentPath(application.id)
        val clientPath = clientPath(application.id)
        val bankPath = bankPath(application.id)
        assertEquals(204, get("$agentPath/financing-scenario", agent).status)
        val missingOpeningBalance = """
            {"expectedReceiptAmount":140000,"expectedReceiptDate":"2026-12-10","paymentDate":"2026-12-15",
             "delayDays":14,"alternativeTermMonths":24,"selectedTermMonths":24}
        """.trimIndent()
        assertEquals(400, put("$agentPath/financing-scenario", missingOpeningBalance).status)
        val denied = putScenario(agentPath, auth = token(Roles.AGENT))
        assertEquals(404, denied.status)
        val deniedBody = mapper.readTree(denied.contentAsByteArray)
        assertEquals(404, deniedBody["status"].asInt())
        assertEquals("Not Found", deniedBody["error"].asText())
        assertEquals("Заявка не найдена", deniedBody["message"].asText())
        assertEquals("$agentPath/financing-scenario", deniedBody["path"].asText())
        val first = snapshot(putScenario(agentPath))
        assertEquals(1L, first["revision"].asLong())
        assertEquals("DRAFT", first["status"].asText())
        assertEquals(listOf(12, 24), first["options"].map { it["termMonths"].asInt() })
        val catalogueRate = checkNotNull(products.find(application.terms.productCode)).baseRate
        assertEquals(0, catalogueRate.compareTo(first["annualRatePercent"].decimalValue()))
        for (option in first["options"]) {
            val payment = LoanMath.annuityPayment(2_000_000, catalogueRate, option["termMonths"].asInt())
            assertEquals(0, payment.compareTo(option["monthlyPayment"].decimalValue()))
            assertEquals(0, BigDecimal("150000").subtract(payment).compareTo(option["balanceOnPaymentDate"].decimalValue()))
        }
        assertEquals(404, get("$clientPath/financing-scenario", client).status)
        assertEquals(404, get(bankPath, bank).status)
        assertEquals(404, get("$agentPath/financing-scenario", token(Roles.AGENT)).status)

        assertEquals(200, post("$agentPath/request-confirmation", agent).status)
        val firstHandoff = snapshot(get("$clientPath/financing-scenario", client))
        assertEquals("AWAITING_CLIENT", firstHandoff["status"].asText())
        assertEquals(1L, firstHandoff["revision"].asLong())
        assertEquals(24, applications.find(application.id)?.terms?.termMonths)
        val afterHandoff = putScenario(agentPath)
        assertEquals(409, afterHandoff.status)
        assertEquals("Сценарий можно изменить только в черновике", mapper.readTree(afterHandoff.contentAsByteArray)["message"].asText())
        assertEquals(404, get(bankPath, bank).status)
        assertEquals(404, get("$clientPath/financing-scenario", token(Roles.CLIENT, companyId = UUID.randomUUID())).status)
        assertEquals(409, confirm(clientPath, revision = null).status)
        assertEquals(400, confirm(clientPath, revision = 1, consent = false).status)
        assertEquals("AWAITING_CLIENT", snapshot(get("$clientPath/financing-scenario", client))["status"].asText())

        assertEquals(200, post("$clientPath/return-to-agent", client, """{"comment":"Уточните задержку"}""").status)
        val publicDocument = document(application.id, privateToBank = false)
        val privateDocument = document(application.id, privateToBank = true)
        val secondDraft = snapshot(putScenario(agentPath, openingBalance = 180_000, delayDays = 0, alternative = 12))
        assertEquals(2L, secondDraft["revision"].asLong())
        val option = secondDraft["options"][0]
        val withReceipt = BigDecimal("320000").subtract(option["monthlyPayment"].decimalValue())
        assertEquals(0, withReceipt.compareTo(option["balanceOnPaymentDate"].decimalValue()))
        assertEquals(firstHandoff, snapshot(get("$clientPath/financing-scenario", client)))
        assertEquals(409, confirm(clientPath, revision = 1).status)
        assertEquals(200, post("$agentPath/request-confirmation", agent).status)
        val secondHandoff = snapshot(get("$clientPath/financing-scenario", client))
        assertEquals(2L, secondHandoff["revision"].asLong())
        assertTrue(secondHandoff["changes"].map(JsonNode::asText).containsAll(listOf("Исходный остаток", "Задержка поступления", "Документы")))
        assertFalse(secondHandoff.toString().contains(privateDocument.toString()))
        val frozenDocumentIds = jdbc.queryForObject(
            """
            select document_ids::text from financing_scenario_publications
            where application_id = ? and revision = 2 and status = 'AWAITING_CLIENT'
            """.trimIndent(),
            String::class.java,
            application.id,
        )
        assertEquals(listOf(publicDocument.toString()), mapper.readTree(frozenDocumentIds).map(JsonNode::asText))
        assertEquals(409, confirm(clientPath, revision = 1).status)

        val concurrent = List(2) { CompletableFuture.supplyAsync { confirm(clientPath, revision = 2) } }
        for (response in concurrent) {
            val result = response.get(30, TimeUnit.SECONDS)
            assertEquals(200, result.status, result.contentAsString)
        }
        val confirmed = snapshot(get(bankPath, bank))
        assertEquals("CONFIRMED", confirmed["status"].asText())
        assertEquals(2L, confirmed["revision"].asLong())
        assertEquals(confirmed, snapshot(get("$clientPath/financing-scenario", client)))
        assertEquals(1, submissionCount(application.id))
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from application_events where application_id = ? and event = 'CONFIRM_AND_SUBMIT'",
                Int::class.java,
                application.id,
            ),
        )
    }

    @Test
    fun `revoked agent cannot reopen a returned draft and the client submits the last handed packet`() {
        val application = draft()
        val agentPath = agentPath(application.id)
        val clientPath = clientPath(application.id)
        val handedDocument = document(application.id, privateToBank = false)
        noteDocument(application, handedDocument)
        assertEquals(200, putScenario(agentPath).status)
        assertEquals(200, post("$agentPath/request-confirmation", agent).status)
        assertEquals(200, post("$clientPath/return-to-agent", client).status)
        val unhandedDocument = document(application.id, privateToBank = false)
        noteDocument(application, unhandedDocument)
        assertEquals(List(2) { mapper.createObjectNode() }, documentNotes(agentPath, agent))
        assertEquals(200, get("$agentPath/documents/$unhandedDocument", agent).status)
        assertPacketNotes(clientPath, client, handedDocument)
        val remove = mvc.delete("$agentPath/documents/$handedDocument") {
            header("Authorization", "Bearer $agent")
        }.andReturn().response
        assertEquals(204, remove.status)
        assertEquals(listOf(handedDocument.toString()), snapshot(get(clientPath, client))["documents"].map { it["id"].asText() })
        assertEquals(200, get("$clientPath/documents/$handedDocument", client).status)
        assertEquals(404, get("$clientPath/documents/$unhandedDocument", client).status)
        val changed = put(
            agentPath,
            """
            {"productCode":"WORKING_CAPITAL","purpose":"CASH_GAP","amount":3000000,"termMonths":18,
             "declaredRevenue12m":36000000}
            """.trimIndent(),
        )
        assertEquals(200, changed.status, changed.contentAsString)
        assertEquals(200, putScenario(agentPath, alternative = 12, selected = 18).status)
        whenever(mandates.checkAccess(agentId, company.id)).thenReturn(AgentAccessDecision(allowed = false))
        assertEquals(404, get("$agentPath/financing-scenario", agent).status)
        assertEquals(404, putScenario(agentPath, alternative = 12, selected = 18).status)
        assertEquals(1L, snapshot(get("$clientPath/financing-scenario", client))["revision"].asLong())
        assertEquals(409, confirm(clientPath, revision = 2).status)

        val submitted = snapshot(confirm(clientPath, revision = 1))
        assertEquals(application.id.toString(), submitted["id"].asText())
        assertEquals(agentId.toString(), submitted["agentId"].asText())
        assertTrue(submitted["clientManaged"].asBoolean())
        assertEquals(2_000_000L, submitted["amount"].asLong())
        assertEquals(24, submitted["termMonths"].asInt())
        assertEquals("WORKING_CAPITAL", submitted["purpose"].asText())
        assertEquals(24_000_000L, submitted["declaredRevenue12m"].asLong())
        assertEquals(listOf(handedDocument.toString()), submitted["documents"].map { it["id"].asText() })
        assertEquals("CONFIRMED", snapshot(get(bankPath(application.id), bank))["status"].asText())
        assertEquals(200, get("$clientPath/documents/$handedDocument", bank).status)
        assertEquals(404, get("$clientPath/documents/$unhandedDocument", bank).status)
        assertPacketNotes(clientPath, bank, handedDocument)
        assertEquals(1, submissionCount(application.id))

        fun sendAttachment(token: String, documentId: UUID) = mvc.post("$clientPath/messages") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"text":"Приложен синтетический файл","documentIds":["$documentId"]}"""
        }.andReturn().response

        for (participant in listOf(client, bank)) {
            assertEquals(404, sendAttachment(participant, unhandedDocument).status)
            assertEquals(404, get("$clientPath/documents/$unhandedDocument", participant).status)
        }
        val rejectedReferences = jdbc.queryForObject(
            "select count(*) from message_attachments where document_id = ?",
            Int::class.java,
            unhandedDocument,
        )
        assertEquals(0, rejectedReferences)
        val visibleDocument = document(application.id, privateToBank = false)
        val accepted = sendAttachment(client, visibleDocument)
        assertEquals(200, accepted.status, accepted.contentAsString)
        assertEquals(visibleDocument.toString(), snapshot(accepted).at("/attachments/0/id").asText())
        assertEquals(200, get("$clientPath/documents/$visibleDocument", bank).status)
    }

    @Test
    fun `a legacy handed application without a scenario keeps the existing confirmation contract`() {
        val application = draft()
        val agentPath = agentPath(application.id)
        val clientPath = clientPath(application.id)
        val legacyDocument = document(application.id, privateToBank = false)
        noteDocument(application, legacyDocument)
        assertEquals(200, post("$agentPath/request-confirmation", agent).status)
        assertEquals(legacyDocument.toString(), documentNotes(clientPath, client).single()["documentId"].asText())
        assertEquals(204, get("$clientPath/financing-scenario", client).status)
        assertEquals(200, confirm(clientPath, revision = null).status)
        assertEquals(204, get(bankPath(application.id), bank).status)
        assertEquals(1, submissionCount(application.id))
    }

    @Test
    fun `the source must be an ordinary attachment with the exact selected facts`() {
        val application = draft()
        val path = "${agentPath(application.id)}/financing-scenario"
        val regular = document(application.id, privateToBank = false)
        val privateDoc = document(application.id, privateToBank = true)
        val bytes = invoicePdf()
        files.put("$regular.pdf", bytes.inputStream(), bytes.size.toLong())

        fun scenario(documentId: UUID, quote: String) =
            put(
                path,
                """
                {"openingBalance":150000,"expectedReceiptAmount":140000,"expectedReceiptDate":"2026-12-10",
                 "paymentDate":"2026-12-15","delayDays":14,"alternativeTermMonths":24,"selectedTermMonths":24,
                 "source":{"page":1,"amountQuote":"Amount due: 140000 RUB","dateQuote":"$quote","documentId":"$documentId"}}
                """.trimIndent(),
            )

        assertEquals(409, scenario(privateDoc, "Due date: 10.12.2026").status)
        assertEquals(409, scenario(UUID.randomUUID(), "Due date: 10.12.2026").status)
        assertEquals(400, scenario(regular, "Due date: 24.12.2026").status)
        val result = snapshot(scenario(regular, "Due date: 10.12.2026"))
        assertEquals(regular.toString(), result.at("/inputs/source/documentId").asText())
    }

    private fun invoicePdf(): ByteArray {
        val output = ByteArrayOutputStream()
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                stream.newLineAtOffset(40f, 700f)
                stream.showText("Amount due: 140000 RUB")
                stream.newLineAtOffset(0f, -24f)
                stream.showText("Due date: 10.12.2026")
                stream.endText()
            }
            document.save(output)
        }
        return output.toByteArray()
    }

    private fun draft(): LoanApplication {
        whenever(mandates.checkAccess(agentId, company.id)).thenReturn(AgentAccessDecision(allowed = true, company = company))
        whenever(sources.bank(company.inn)).thenReturn(BankInfo(client = true, settlementAccount = null, monthlyInflows = emptyList()))
        return applications.save(
            LoanApplication(
                company = company,
                createdBy = agentId,
                terms = ApplicationTerms(
                    "WORKING_CAPITAL",
                    FinancingPurpose.WORKING_CAPITAL,
                    amount = 2_000_000,
                    termMonths = 12,
                    declaredRevenue12m = 24_000_000,
                ),
                createdAt = clock.instant(),
                agentId = agentId,
            ),
        )
    }

    private fun document(applicationId: UUID, privateToBank: Boolean): UUID {
        val id = UUID.randomUUID()
        val document = ApplicationDocument(
            id = id,
            applicationId = applicationId,
            type = if (privateToBank) DocumentType.BANK_STATEMENT else DocumentType.FINANCIAL_STATEMENTS,
            fileName = "synthetic.pdf",
            storedName = "$id.pdf",
            sizeBytes = 1,
            uploadedBy = agentId,
            uploadedAt = clock.instant(),
            privateToBank = privateToBank,
        )
        files.put(document.storedName, byteArrayOf(1).inputStream(), document.sizeBytes)
        documents.save(document)
        return id
    }

    private fun noteDocument(application: LoanApplication, documentId: UUID) {
        journal.append(
            application.note(
                JournalNote.DOCUMENT_ADDED,
                Actor(ActorType.AGENT, agentId),
                clock.instant(),
                mapOf("documentId" to documentId, "fileName" to "synthetic.pdf"),
            ),
        )
    }

    private fun documentNotes(path: String, auth: String): List<JsonNode> =
        snapshot(get("$path/events", auth)).filter { it["event"].asText() == JournalNote.DOCUMENT_ADDED.name }.map { it["payload"] }

    private fun assertPacketNotes(path: String, auth: String, handedDocument: UUID) {
        val notes = documentNotes(path, auth)
        assertEquals(
            listOf(mapper.valueToTree(mapOf("documentId" to handedDocument.toString(), "fileName" to "synthetic.pdf")), mapper.createObjectNode()),
            notes,
        )
    }

    private fun putScenario(
        path: String,
        openingBalance: Long = 150_000,
        delayDays: Int = 14,
        alternative: Int = 24,
        selected: Int = 24,
        auth: String = agent,
    ) =
        put(
            "$path/financing-scenario",
            """
            {"openingBalance":$openingBalance,"expectedReceiptAmount":140000,"expectedReceiptDate":"2026-12-10",
             "paymentDate":"2026-12-15","delayDays":$delayDays,"alternativeTermMonths":$alternative,"selectedTermMonths":$selected}
            """.trimIndent(),
            auth,
        )

    private fun confirm(path: String, revision: Long?, consent: Boolean = true) =
        post("$path/confirm", client, """{"bureauConsent":$consent${revision?.let { ""","acceptedScenarioRevision":$it""" }.orEmpty()}}""")

    private fun submissionCount(id: UUID): Int? =
        jdbc.queryForObject(
            "select count(*) from pipeline_jobs where application_id = ? and step = 'DATA_CHECK'",
            Int::class.java,
            id,
        )

    private fun agentPath(id: UUID) = "/api/v1/agent/applications/$id"
    private fun clientPath(id: UUID) = "/api/v1/applications/$id"
    private fun bankPath(id: UUID) = "/api/v1/underwriting/applications/$id/financing-scenario"

    private fun get(path: String, auth: String) =
        mvc.get(path) { header("Authorization", "Bearer $auth") }.andReturn().response

    private fun post(path: String, auth: String, body: String? = null) =
        mvc.post(path) {
            header("Authorization", "Bearer $auth")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }.andReturn().response

    private fun put(path: String, body: String, auth: String = agent) = mvc.put(path) {
        header("Authorization", "Bearer $auth")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }.andReturn().response

    private fun snapshot(response: MockHttpServletResponse): JsonNode {
        assertEquals(200, response.status, response.contentAsString)
        return mapper.readTree(response.contentAsByteArray)
    }

    private fun token(role: String, userId: UUID = UUID.randomUUID(), companyId: UUID? = null) =
        Jwts.builder().subject(userId.toString()).claim(TokenClaims.ROLE, role)
            .apply { companyId?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, companyId != null)
            .expiration(Date(clock.millis() + 600_000))
            .signWith(verifier.signingKey).compact()
}
