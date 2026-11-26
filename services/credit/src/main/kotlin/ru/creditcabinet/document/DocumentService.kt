package ru.creditcabinet.document

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.InfoRequest
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.chat.domain.ChatUpload
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.application.ApplicationDocuments
import ru.creditcabinet.document.application.DocumentUpload
import ru.creditcabinet.document.application.DocumentUploadLimit
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
class DocumentService(
    private val applications: ApplicationService,
    private val documents: DocumentRepository,
    private val store: FileStore,
    private val scanner: DocumentScanner,
    private val journal: ApplicationJournal,
    private val infoRequests: InfoRequests,
    private val throttle: DocumentUploadLimit,
    private val clock: Clock,
    @Value("\${cabinet.storage.max-size-bytes:20971520}") private val maxSizeBytes: Long,
    @Value("\${cabinet.storage.uploads-enabled:true}") private val uploadsEnabled: Boolean,
    private val boundary: ru.creditcabinet.document.infrastructure.DocumentsBoundary? = null,
) : ApplicationDocuments {
    override fun list(applicationId: UUID): List<ApplicationDocument> = list(applications.get(applicationId))

    override fun list(authorizedApplication: LoanApplication): List<ApplicationDocument> {
        val caller = CurrentCaller.claims()
        val uploaded = if (caller.isAgent) {
            documents.list(authorizedApplication.id)
        } else {
            documents.listForPacket(authorizedApplication.id, authorizedApplication.submittedAt)
        }
        return uploaded.filter { it.visibleTo(caller, authorizedApplication) }
    }

    override fun satisfiedDocumentTypes(request: InfoRequest): Set<DocumentType> =
        documents.list(request.applicationId)
            .filter { !it.uploadedAt.isBefore(request.createdAt) }
            .map { it.type }
            .toSet()

    /** A client adds documents while preparing the application or when the bank asked for them. */
    @Transactional
    override fun upload(applicationId: UUID, type: DocumentType, file: DocumentUpload, privateToBank: Boolean): ApplicationDocument {
        val (claims, application) = ownForDocumentWrite(applicationId, forUpdate = privateToBank)
        if (application.status !in UPLOADABLE) throw ConflictException("Документы добавляются в черновик или по запросу банка")
        if (privateToBank) {
            if (type != DocumentType.BANK_STATEMENT) throw BadRequestException("Приватная загрузка доступна для выписки по счёту")
            val requested = application.status == ApplicationStatus.INFO_REQUESTED &&
                application.submittedAt != null &&
                infoRequests.open(application.id)?.items?.any { it.documentType == type } == true
            if (!requested) throw ConflictException("Банк сейчас не запрашивает выписку по счёту")
        }
        return persist(application, claims, type, file.fileName, file.size, file.read, DocumentContext.BUSINESS, privateToBank)
    }

    /** An agent adds documents to their own mandate-verified application in DRAFT or INFO_REQUESTED. */
    @Transactional
    fun uploadForAgent(application: LoanApplication, claims: TokenClaims, type: DocumentType, file: MultipartFile): ApplicationDocument {
        if (application.status !in UPLOADABLE) throw ConflictException("Документы добавляются в черновик или по запросу банка")
        val upload = DocumentUpload(file.originalFilename, file.size) { file.bytes }
        return persist(application, claims, type, upload.fileName, upload.size, upload.read, DocumentContext.BUSINESS)
    }

    /** Chat shares validation and storage, but its files cannot satisfy financial requirements or add process events. */
    @Transactional
    fun uploadChat(application: LoanApplication, caller: TokenClaims, upload: ChatUpload): ApplicationDocument =
        persist(application, caller, DocumentType.OTHER, upload.fileName, upload.bytes.size.toLong(), { upload.bytes }, DocumentContext.CHAT)

    private fun persist(
        application: LoanApplication,
        claims: TokenClaims,
        type: DocumentType,
        originalName: String?,
        size: Long,
        content: () -> ByteArray,
        context: DocumentContext,
        privateToBank: Boolean = false,
    ): ApplicationDocument {
        if (!uploadsEnabled) {
            throw ru.creditcabinet.common.exception.ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "Загрузка файлов временно приостановлена. Попробуйте позже",
            )
        }
        throttle.acquire(claims.userId)
        if (size <= 0) throw BadRequestException("Файл пустой")
        if (size > maxSizeBytes) throw BadRequestException("Файл больше ${maxSizeBytes / (1024 * 1024)} МБ")
        val fileName = originalName?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }?.take(MAX_NAME) ?: "document"
        if ('\u0000' in fileName) throw BadRequestException("Имя файла содержит нулевой символ")
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (privateToBank && extension != "pdf") throw BadRequestException("Приватную выписку загрузите в PDF")
        if (extension !in ALLOWED_EXTENSIONS) throw BadRequestException("Подходят PDF, JPG, PNG, Word и Excel, а это .$extension")
        val bytes = content()
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
            privateToBank = privateToBank,
        )
        if (boundary?.remote == true) {
            boundary.put(document, bytes)
        } else {
            store.put(document.storedName, bytes.inputStream(), document.sizeBytes)
            documents.save(document)
        }
        if (context == DocumentContext.BUSINESS) {
            journal.append(
                application.note(
                    if (privateToBank) JournalNote.PRIVATE_DOCUMENT_ADDED else JournalNote.DOCUMENT_ADDED,
                    claims.toActor()!!,
                    now,
                    if (privateToBank) {
                        emptyMap()
                    } else {
                        mapOf(
                            "documentId" to document.id,
                            "type" to type.name,
                            "fileName" to fileName,
                        )
                    },
                ),
            )
        }
        return document
    }

    /** Only from a draft: once sent, what the bank received stays in the application. */
    @Transactional
    override fun delete(applicationId: UUID, documentId: UUID) {
        val (claims, application) = ownForDocumentWrite(applicationId)
        deleteInternal(application, claims, documentId)
    }

    private fun ownForDocumentWrite(applicationId: UUID, forUpdate: Boolean = false): Pair<TokenClaims, LoanApplication> {
        val (claims, application) = applications.own(applicationId, forUpdate)
        if (application.status == ApplicationStatus.DRAFT && application.activeAgentId != null) {
            throw NotFoundException("Заявка не найдена")
        }
        return claims to application
    }

    /** An agent deletes a document from their own mandate-verified draft. */
    @Transactional
    fun deleteForAgent(application: LoanApplication, claims: TokenClaims, documentId: UUID) {
        deleteInternal(application, claims, documentId)
    }

    private fun deleteInternal(application: LoanApplication, claims: TokenClaims, documentId: UUID) {
        if (application.status != ApplicationStatus.DRAFT) throw ConflictException("Отправленные документы удалить нельзя — загрузите новую версию")
        val document = documents.find(application.id, documentId) ?: throw NotFoundException("Документ не найден")
        if (document.privateToBank) throw NotFoundException("Документ не найден")
        if (documents.isChatAttached(documentId)) throw ConflictException("Вложение отправленного сообщения изменить или удалить нельзя")
        val now = clock.instant()
        documents.markDeleted(application.id, document.id, now)
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
        val document = readableDocument(application, documentId, CurrentCaller.claims())
        val file =
            (if (boundary?.remote == true) boundary.bytes(document) else store.get(document.storedName, document.storageBackend))
                ?: throw NotFoundException("Файл документа не найден в хранилище")
        return document to file
    }

    internal fun chatAttachment(application: LoanApplication, documentId: UUID, caller: TokenClaims): ApplicationDocument =
        readableDocument(application, documentId, caller, attachToChat = true)

    private fun readableDocument(
        application: LoanApplication,
        documentId: UUID,
        caller: TokenClaims,
        attachToChat: Boolean = false,
    ): ApplicationDocument {
        val current = documents.find(application.id, documentId)
        if (attachToChat && (current == null || current.privateToBank)) throw NotFoundException("Документ не найден")
        val readable = when {
            current?.context == DocumentContext.CHAT -> current.takeIf { it.uploadedBy == caller.userId || documents.isChatAttached(it.id) }
            caller.isAgent -> current
            else -> documents.listForPacket(application.id, application.submittedAt, documentId).singleOrNull()
        }
        return readable?.takeIf { it.visibleTo(caller, application) } ?: throw NotFoundException("Документ не найден")
    }

    private fun ApplicationDocument.visibleTo(caller: TokenClaims, application: LoanApplication): Boolean =
        !privateToBank ||
            (caller.isClient && caller.companyId == application.company.id) ||
            (caller.isBankStaff && application.submittedAt != null)

    private companion object {
        val UPLOADABLE = setOf(ApplicationStatus.DRAFT, ApplicationStatus.INFO_REQUESTED)
        val ALLOWED_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png", "doc", "docx", "xls", "xlsx")
        const val MAX_NAME = 255
    }
}
