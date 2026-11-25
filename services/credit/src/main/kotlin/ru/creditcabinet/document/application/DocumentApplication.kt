package ru.creditcabinet.document.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.application.ApplicationAccess
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.FileContent
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StoredFile
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/**
 * Documents of an application. Unlike public file links, every download checks that the caller
 * may see the application: financial documents of a company go to its people and to the bank only.
 */
@Service
class DocumentApplication(
    private val applications: ApplicationAccess,
    private val documents: DocumentRepository,
    private val store: FileStore,
    private val scanner: DocumentScanner,
    private val journal: ApplicationJournal,
    private val throttle: DocumentUploadLimit,
    private val clock: Clock,
    @Value("\${cabinet.storage.max-size-bytes:20971520}") private val maxSizeBytes: Long,
    @Value("\${cabinet.storage.uploads-enabled:true}") private val uploadsEnabled: Boolean,
) : ApplicationDocuments {
    override fun list(applicationId: UUID): List<ApplicationDocument> = documents.list(applications.get(applicationId).id)

    /** Lists documents for an already-authorized application aggregate (e.g. right after a transition). */
    override fun list(authorizedApplication: LoanApplication): List<ApplicationDocument> = documents.list(authorizedApplication.id)

    /** A client adds documents while preparing the application or when the bank asked for them. */
    @Transactional
    override fun upload(applicationId: UUID, type: DocumentType, file: DocumentUpload): ApplicationDocument {
        val (claims, application) = applications.own(applicationId)
        if (application.status !in UPLOADABLE) throw ConflictException("Документы добавляются в черновик или по запросу банка")
        return persist(application, claims, type, file, DocumentContext.BUSINESS)
    }

    /** An agent adds documents to their own mandate-verified application in DRAFT or INFO_REQUESTED. */
    @Transactional
    fun uploadForAgent(application: LoanApplication, claims: TokenClaims, type: DocumentType, file: DocumentUpload): ApplicationDocument {
        if (application.status !in UPLOADABLE) throw ConflictException("Документы добавляются в черновик или по запросу банка")
        return persist(application, claims, type, file, DocumentContext.BUSINESS)
    }

    /** Chat shares validation and storage, but its files cannot satisfy financial requirements or add process events. */
    @Transactional
    fun uploadChat(application: LoanApplication, caller: TokenClaims, upload: DocumentUpload): ApplicationDocument =
        persist(application, caller, DocumentType.OTHER, upload, DocumentContext.CHAT)

    private fun persist(
        application: LoanApplication,
        claims: TokenClaims,
        type: DocumentType,
        upload: DocumentUpload,
        context: DocumentContext,
    ): ApplicationDocument {
        if (!uploadsEnabled) {
            throw ru.creditcabinet.common.exception.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Загрузка файлов временно приостановлена. Попробуйте позже",
            )
        }
        throttle.acquire(claims.userId)
        val size = upload.size
        if (size <= 0) throw BadRequestException("Файл пустой")
        if (size > maxSizeBytes) throw BadRequestException("Файл больше ${maxSizeBytes / (1024 * 1024)} МБ")
        val fileName = upload.fileName?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }?.take(MAX_NAME) ?: "document"
        if ('\u0000' in fileName) throw BadRequestException("Имя файла содержит нулевой символ")
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) throw BadRequestException("Подходят PDF, JPG, PNG, Word и Excel, а это .$extension")
        val bytes = upload.read()
        if (bytes.size.toLong() != size) throw BadRequestException("Размер файла изменился при загрузке — выберите файл заново")
        if (!FileContent.matches(extension, bytes)) throw BadRequestException("Содержимое файла не похоже на .$extension — проверьте, что выбран нужный файл")
        scanner.scan(bytes)
        val now = clock.instant()
        val document = ApplicationDocument(
            applicationId = application.id,
            type = type,
            fileName = fileName,
            storedName = "${UUID.randomUUID()}.$extension",
            sizeBytes = bytes.size.toLong(),
            uploadedBy = claims.userId,
            uploadedAt = now,
            context = context,
            storageBackend = store.writeBackend,
            checksumSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        )
        store.put(document.storedName, bytes.inputStream(), document.sizeBytes)
        documents.save(document)
        if (context == DocumentContext.BUSINESS) {
            journal.append(
                application.note(
                    JournalNote.DOCUMENT_ADDED,
                    claims.toActor()!!,
                    now,
                    mapOf(
                        "documentId" to document.id,
                        "type" to type.name,
                        "fileName" to fileName,
                    ),
                ),
            )
        }
        return document
    }

    /** Only from a draft: once sent, what the bank received stays in the application. */
    @Transactional
    override fun delete(applicationId: UUID, documentId: UUID) {
        val (claims, application) = applications.own(applicationId)
        deleteInternal(application, claims, documentId)
    }

    /** An agent deletes a document from their own mandate-verified draft. */
    @Transactional
    fun deleteForAgent(application: LoanApplication, claims: TokenClaims, documentId: UUID) {
        deleteInternal(application, claims, documentId)
    }

    private fun deleteInternal(application: LoanApplication, claims: TokenClaims, documentId: UUID) {
        if (application.status != ApplicationStatus.DRAFT) throw ConflictException("Отправленные документы удалить нельзя — загрузите новую версию")
        val document = documents.find(application.id, documentId) ?: throw NotFoundException("Документ не найден")
        if (documents.isChatAttached(documentId)) throw ConflictException("Вложение отправленного сообщения изменить или удалить нельзя")
        val now = clock.instant()
        documents.markDeleted(document.id, now)
        journal.append(
            application.note(
                JournalNote.DOCUMENT_REMOVED,
                claims.toActor()!!,
                now,
                mapOf(
                    "documentId" to document.id,
                    "fileName" to document.fileName,
                ),
            ),
        )
    }

    override fun open(applicationId: UUID, documentId: UUID): Pair<ApplicationDocument, StoredFile> {
        val application = applications.get(applicationId)
        val document = documents.find(application.id, documentId) ?: throw NotFoundException("Документ не найден")
        if (document.context == DocumentContext.CHAT && document.uploadedBy != CurrentCaller.claims().userId && !documents.isChatAttached(documentId)) {
            throw NotFoundException("Документ не найден")
        }
        val file = store.get(document.storedName, document.storageBackend) ?: throw NotFoundException("Файл документа не найден в хранилище")
        return document to file
    }

    private companion object {
        val UPLOADABLE = setOf(ApplicationStatus.DRAFT, ApplicationStatus.INFO_REQUESTED)
        val ALLOWED_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png", "doc", "docx", "xls", "xlsx")
        const val MAX_NAME = 255
    }
}
