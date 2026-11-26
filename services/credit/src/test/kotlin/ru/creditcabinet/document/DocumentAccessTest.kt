package ru.creditcabinet.document

import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.product.domain.FinancingPurpose
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentAccessTest {
    private val applications = mock<ApplicationService>()
    private val documents = mock<DocumentRepository>()
    private val store = mock<FileStore>()
    private val service = DocumentService(applications, documents, store, mock(), mock(), mock(), mock(), Clock.systemUTC(), 100, true)
    private val now = Instant.parse("2026-11-26T15:00:00Z")
    private val client = TokenClaims(UUID.randomUUID(), Roles.CLIENT, null, null, companyId = UUID.randomUUID())
    private val bank = TokenClaims(UUID.randomUUID(), Roles.UNDERWRITER, null, null)
    private val application = LoanApplication(
        company = CompanySnapshot(checkNotNull(client.companyId), "000100000004", null, LegalForm.ENTREPRENEUR),
        createdBy = client.userId,
        terms = ApplicationTerms("WORKING_CAPITAL", FinancingPurpose.CASH_GAP, amount = 1_000_000, termMonths = 12),
        status = ApplicationStatus.SUBMITTED,
        createdAt = now.minusSeconds(60),
        submittedAt = now,
    )

    @AfterTest
    fun clearCaller() = SecurityContextHolder.clearContext()

    @Test
    fun `business document outside the packet remains unavailable in document and chat views`() {
        val document = document()
        whenever(documents.find(application.id, document.id)).thenReturn(document)
        whenever(documents.isChatAttached(document.id)).thenReturn(true)
        for (caller in listOf(client, bank)) {
            authenticate(caller)
            assertFailsWith<NotFoundException> { service.chatAttachment(application, document.id, caller) }
            assertFailsWith<NotFoundException> { service.open(application.id, document.id) }
        }
        verifyNoInteractions(store)
    }

    @Test
    fun `business document in the packet can be referenced by client and bank`() {
        val document = document()
        whenever(documents.find(application.id, document.id)).thenReturn(document)
        whenever(documents.listForPacket(application.id, application.submittedAt, document.id)).thenReturn(listOf(document))
        for (caller in listOf(client, bank)) {
            authenticate(caller)
            assertEquals(document, service.chatAttachment(application, document.id, caller))
            assertEquals(document, service.open(application.id, document.id).first)
        }
    }

    @Test
    fun `private statement can be read by its client and bank but cannot enter a chat`() {
        val document = document().copy(type = DocumentType.BANK_STATEMENT, privateToBank = true)
        whenever(documents.find(application.id, document.id)).thenReturn(document)
        whenever(documents.listForPacket(application.id, application.submittedAt, document.id)).thenReturn(listOf(document))
        for (caller in listOf(client, bank)) {
            authenticate(caller)
            assertFailsWith<NotFoundException> { service.chatAttachment(application, document.id, caller) }
            assertEquals(document, service.open(application.id, document.id).first)
        }
    }

    @Test
    fun `uploaded chat files are visible to their uploader and become shared on publication`() {
        val document = document(DocumentContext.CHAT)
        whenever(documents.find(application.id, document.id)).thenReturn(document)
        authenticate(client)
        assertEquals(document, service.chatAttachment(application, document.id, client))
        assertEquals(document, service.open(application.id, document.id).first)
        authenticate(bank)
        assertFailsWith<NotFoundException> { service.chatAttachment(application, document.id, bank) }
        assertFailsWith<NotFoundException> { service.open(application.id, document.id) }

        whenever(documents.isChatAttached(document.id)).thenReturn(true)
        assertEquals(document, service.chatAttachment(application, document.id, bank))
        assertEquals(document, service.open(application.id, document.id).first)
    }

    private fun authenticate(caller: TokenClaims) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(caller, null)
        whenever(applications.get(application.id)).thenReturn(application)
    }

    private fun document(context: DocumentContext = DocumentContext.BUSINESS): ApplicationDocument {
        val document = ApplicationDocument(
            applicationId = application.id,
            type = DocumentType.OTHER,
            fileName = "synthetic.pdf",
            storedName = "test.pdf",
            sizeBytes = 1,
            uploadedBy = client.userId,
            uploadedAt = now.minusSeconds(30),
            context = context,
        )
        whenever(store.get(document.storedName, document.storageBackend)).thenAnswer { StoredFile(1, byteArrayOf(1).inputStream()) }
        return document
    }
}
