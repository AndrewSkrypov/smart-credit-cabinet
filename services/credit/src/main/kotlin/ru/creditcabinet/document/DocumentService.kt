package ru.creditcabinet.document

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.FileContent
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.document.infrastructure.UploadThrottle
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
    private val journal: ApplicationJournal,
    private val throttle: UploadThrottle,
    private val clock: Clock,
    @Value("\${cabinet.storage.max-size-bytes:20971520}") private val maxSizeBytes: Long,
) {
    fun list(applicationId: UUID): List<ApplicationDocument> = documents.list(applications.get(applicationId).id)

    /** A client adds documents while preparing the application or when the bank asked for them. */
    @Transactional
    fun upload(applicationId: UUID, type: DocumentType, file: MultipartFile): ApplicationDocument {
        val (claims, application) = applications.own(applicationId)
        if (application.status !in UPLOADABLE) throw ConflictException("Документы добавляются в черновик или по запросу банка")
        throttle.acquire(claims.userId)
        if (file.isEmpty) throw BadRequestException("Файл пустой")
        if (file.size > maxSizeBytes) throw BadRequestException("Файл больше ${maxSizeBytes / (1024 * 1024)} МБ")
        val fileName = file.originalFilename?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }?.take(MAX_NAME) ?: "document"
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) throw BadRequestException("Подходят PDF, JPG, PNG, Word и Excel, а это .$extension")
        val bytes = file.bytes
        if (!FileContent.matches(extension, bytes)) throw BadRequestException("Содержимое файла не похоже на .$extension — проверьте, что выбран нужный файл")
        val now = clock.instant()
        val document = ApplicationDocument(
            applicationId = application.id,
            type = type,
            fileName = fileName,
            storedName = "${UUID.randomUUID()}.$extension",
            sizeBytes = bytes.size.toLong(),
            uploadedBy = claims.userId,
            uploadedAt = now,
        )
        store.put(document.storedName, bytes.inputStream(), document.sizeBytes)
        documents.save(document)
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
        return document
    }

    /** Only from a draft: once sent, what the bank received stays in the application. */
    @Transactional
    fun delete(applicationId: UUID, documentId: UUID) {
        val (claims, application) = applications.own(applicationId)
        if (application.status != ApplicationStatus.DRAFT) throw ConflictException("Отправленные документы удалить нельзя — загрузите новую версию")
        val document = documents.find(application.id, documentId) ?: throw NotFoundException("Документ не найден")
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

    fun open(applicationId: UUID, documentId: UUID): Pair<ApplicationDocument, StoredFile> {
        val application = applications.get(applicationId)
        val document = documents.find(application.id, documentId) ?: throw NotFoundException("Документ не найден")
        val file = store.get(document.storedName) ?: throw NotFoundException("Файл документа не найден в хранилище")
        return document to file
    }

    private companion object {
        val UPLOADABLE = setOf(ApplicationStatus.DRAFT, ApplicationStatus.INFO_REQUESTED)
        val ALLOWED_EXTENSIONS = setOf("pdf", "jpg", "jpeg", "png", "doc", "docx", "xls", "xlsx")
        const val MAX_NAME = 255
    }
}
