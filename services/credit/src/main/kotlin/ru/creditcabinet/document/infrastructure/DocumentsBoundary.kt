package ru.creditcabinet.document.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.grpc.ManagedChannel
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CreditFailure.Conflict
import ru.creditcabinet.application.application.CreditFailure.DependencyFailure
import ru.creditcabinet.application.application.CreditFailure.InvalidInput
import ru.creditcabinet.application.application.CreditFailure.NotFound
import ru.creditcabinet.document.application.DocumentTransfer
import ru.creditcabinet.document.application.DocumentUploadIntent
import ru.creditcabinet.document.application.UploadOperation
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.documents.DocumentRecord
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.documents.sha256
import ru.creditcabinet.documentsgrpc.DocumentsGrpcClient
import ru.creditcabinet.documentsgrpc.documentsTlsChannel
import java.io.File
import java.time.Instant
import java.util.UUID

/** One switch controls metadata AND bytes; a failed remote never falls back to local. */
@Component
@Primary
class DocumentsBoundary(
    private val local: JdbcDocumentRepository,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    @Value("\${cabinet.documents.mode:local}") private val mode: String,
    @Value("\${cabinet.documents.epoch:1}") private val epoch: Long,
    @Value("\${cabinet.documents.grpc.host:documents}") private val host: String,
    @Value("\${cabinet.documents.grpc.port:9096}") private val port: Int,
    @Value("\${cabinet.documents.grpc.certificate:}") private val certificate: String,
    @Value("\${cabinet.documents.grpc.key:}") private val key: String,
    @Value("\${cabinet.documents.grpc.ca:}") private val ca: String,
    @Value("\${cabinet.documents.grpc.deadline-ms:30000}") private val deadlineMs: Long,
) : DocumentRepository, DocumentTransfer {
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent::class)
    fun verifyOwnership() {
        if (remote) client // Validate required TLS files/channel settings before admitting traffic.
    }
    private var channel: ManagedChannel? = null
    private val client by lazy {
        require(mode == "remote" && listOf(certificate, key, ca).all { it.isNotBlank() })
        channel = documentsTlsChannel(host, port, File(certificate), File(key), File(ca))
        DocumentsGrpcClient(channel!!, mapper, epoch, deadlineMs)
    }
    override val remote: Boolean get() {
        val gate = jdbc.queryForMap("select owner,epoch from document_ownership where singleton")
        if (mode !in setOf("local", "remote") || gate["owner"] != mode.uppercase() || (gate["epoch"] as Number).toLong() != epoch) unavailable()
        return mode == "remote"
    }
    fun client(): DocumentsGrpcClient {
        check(remote)
        return client
    }
    fun scope(
        applicationId: UUID,
    ): DocumentScope = DocumentScope(jdbc.queryForObject("select company_id from applications where id=?", UUID::class.java, applicationId)!!, applicationId)
    override fun put(document: ApplicationDocument, bytes: ByteArray): ApplicationDocument = rpc {
        val scope = scope(document.applicationId)
        client().put(document.record(scope), bytes)
        // A replay returns the original owner-chosen timestamp/backend/path, including after a lost reply.
        client().list(scope).single { it.id == document.id && it.deletedAt == null }.document()
    }
    private var operationPool: com.zaxxer.hikari.HikariDataSource? = null
    private val operationJdbc by lazy {
        val original = jdbc.dataSource!!
        val source = if (original.isWrapperFor(com.zaxxer.hikari.HikariDataSource::class.java)) {
            val configured = original.unwrap(com.zaxxer.hikari.HikariDataSource::class.java)
            com.zaxxer.hikari.HikariDataSource(
                com.zaxxer.hikari.HikariConfig().apply {
                    jdbcUrl = configured.jdbcUrl
                    username = configured.username
                    password = configured.password
                    configured.driverClassName?.let { driverClassName = it }
                    dataSourceProperties = configured.dataSourceProperties
                    maximumPoolSize = 2
                    minimumIdle = 0
                    connectionTimeout = 3000
                    validationTimeout = 1000
                    poolName = "documents-upload-operations"
                },
            ).also { operationPool = it }
        } else {
            original
        }
        org.springframework.jdbc.core.JdbcTemplate(source).apply { queryTimeout = 5 }
    }
    override fun reserveUpload(id: UUID, intent: DocumentUploadIntent, backend: StorageBackend, at: Instant): UploadOperation {
        val applicationId = intent.applicationId
        val actor = intent.actor
        val requestHash = sha256(
            jacksonObjectMapper().writeValueAsBytes(
                listOf(
                    "credit-upload-v1", intent.companyId, applicationId, actor, intent.context.name,
                    intent.type.name, intent.fileName, intent.size, intent.privateToBank, intent.bytesHash,
                ),
            ),
        )
        val company = scope(applicationId).companyId
        val transaction = org.springframework.transaction.support.TransactionTemplate(
            org.springframework.jdbc.datasource.DataSourceTransactionManager(operationJdbc.dataSource!!),
        ).apply { propagationBehavior = org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW }
        return transaction.execute {
            operationJdbc.update(
                "insert into document_upload_operations(operation_id,application_id,company_id,actor_id,request_hash,requested_at,requested_backend) values(?,?,?,?,?,?,?) on conflict do nothing",
                id,
                applicationId,
                company,
                actor,
                requestHash,
                java.sql.Timestamp.from(at.truncatedTo(java.time.temporal.ChronoUnit.MICROS)),
                backend.name,
            )
            val operation = operationJdbc.queryForMap("select * from document_upload_operations where operation_id=?", id)
            if (operation["application_id"] != applicationId ||
                operation["company_id"] != company ||
                operation["actor_id"] != actor ||
                operation["request_hash"] != requestHash
            ) {
                throw Conflict("Ключ загрузки уже использован для другого файла")
            }
            UploadOperation((operation["requested_at"] as java.sql.Timestamp).toInstant(), StorageBackend.valueOf(operation["requested_backend"] as String))
        }!!
    }
    override fun lockUpload(id: UUID) {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", { _, _ -> true }, id.toString())
    }
    override fun committedUpload(applicationId: UUID, id: UUID): ApplicationDocument? {
        val committed = jdbc.queryForObject(
            "select exists(select 1 from document_references where document_id=? and application_id=?)",
            Boolean::class.java,
            id,
            applicationId,
        )!!
        if (!committed) return null
        val existing = if (remote) all(applicationId).singleOrNull { it.id == id && it.deletedAt == null }?.document() else local.find(applicationId, id)
        return existing ?: throw Conflict("Документ с этим ключом уже удалён")
    }
    override fun commitReference(document: ApplicationDocument): Boolean {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        val inserted = jdbc.update(
            "insert into document_references(document_id,application_id) values(?,?) on conflict do nothing",
            document.id,
            document.applicationId,
        )
        check(
            jdbc.queryForObject("select application_id from document_references where document_id=?", UUID::class.java, document.id) == document.applicationId,
        )
        return inserted == 1
    }
    override fun bytes(document: ApplicationDocument): StoredFile = rpc {
        val bytes = client().read(scope(document.applicationId), document.id, document.context.name)
        require(bytes.size.toLong() == document.sizeBytes && ru.creditcabinet.documents.sha256(bytes) == document.checksumSha256)
        StoredFile(bytes.size.toLong(), bytes.inputStream())
    }
    fun all(applicationId: UUID): List<DocumentRecord> {
        val committed = jdbc.query("select document_id from document_references where application_id=?", { rs, _ ->
            rs.getObject(1, UUID::class.java)
        }, applicationId).toSet()
        if (committed.isEmpty()) return emptyList()
        val deleted = jdbc.query("select document_id,deleted_at from document_deletion_intents where application_id=?", { rs, _ ->
            rs.getObject(1, UUID::class.java) to rs.getTimestamp(2).toInstant()
        }, applicationId).toMap()
        return rpc {
            client().list(scope(applicationId)).filter { it.id in committed }.map { record ->
                deleted[record.id]?.let { record.copy(deletedAt = it) } ?: record
            }
        }
    }
    override fun list(applicationId: UUID): List<ApplicationDocument> = if (!remote) {
        local.list(applicationId)
    } else {
        all(applicationId).filter { it.context == "BUSINESS" && it.deletedAt == null }.map { it.document() }.sortedBy { it.uploadedAt }
    }
    override fun listForPacket(applicationId: UUID, submittedAt: Instant?, documentId: UUID?): List<ApplicationDocument> {
        if (!remote) return local.listForPacket(applicationId, submittedAt, documentId)
        val packet = jdbc.query(
            "select document_ids from financing_scenario_publications where application_id=? and status='AWAITING_CLIENT' order by revision desc limit 1",
            {
                    rs,
                    _,
                ->
                mapper.readValue<List<UUID>>(rs.getString(1))
            },
            applicationId,
        ).firstOrNull()
        if (documentId != null && documentId !in packet.orEmpty() && removed(documentId)) return emptyList()
        return all(applicationId).filter {
            it.context == "BUSINESS" &&
                (documentId == null || it.id == documentId) &&
                ((it.deletedAt == null && (packet == null || (submittedAt != null && !it.uploadedAt.isBefore(submittedAt)))) || it.id in packet.orEmpty())
        }
            .map { it.document() }.sortedBy { it.uploadedAt }
    }
    override fun find(applicationId: UUID, documentId: UUID): ApplicationDocument? = if (!remote) {
        local.find(applicationId, documentId)
    } else if (removed(documentId)) {
        null
    } else {
        all(applicationId).firstOrNull {
            it.id ==
                documentId &&
                it.deletedAt == null
        }?.document()
    }
    override fun save(document: ApplicationDocument) {
        check(!remote)
        local.save(document)
    }
    override fun markDeleted(documentId: UUID, at: Instant) {
        check(!remote)
        local.markDeleted(documentId, at)
    }
    override fun markDeleted(applicationId: UUID, documentId: UUID, at: Instant) {
        if (!remote) {
            local.markDeleted(documentId, at)
            return
        }
        val document = find(applicationId, documentId) ?: throw NotFound("Документ не найден")
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        jdbc.update(
            "insert into document_deletion_intents(document_id,application_id,deleted_at) values(?,?,?) on conflict do nothing",
            document.id,
            applicationId,
            java.sql.Timestamp.from(at),
        )
    }
    private fun removed(
        id: UUID,
    ): Boolean = jdbc.queryForObject("select exists(select 1 from document_deletion_intents where document_id=?)", Boolean::class.java, id)!!
    override fun isChatAttached(documentId: UUID): Boolean = local.isChatAttached(documentId)
    fun <T> rpc(
        action: () -> T,
    ): T = try {
        action()
    } catch (failure: io.grpc.StatusRuntimeException) {
        val causes = generateSequence(failure.status.cause ?: failure.cause) { it.cause }.take(12)
        if (causes.any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }) {
            throw DependencyFailure("Ошибка доверия сервиса документов")
        }
        when (failure.status.code) {
            io.grpc.Status.Code.NOT_FOUND, io.grpc.Status.Code.PERMISSION_DENIED, io.grpc.Status.Code.UNAUTHENTICATED ->
                throw NotFound("Файл не найден")
            io.grpc.Status.Code.ALREADY_EXISTS -> throw Conflict("Ключ загрузки уже использован для другого файла")
            io.grpc.Status.Code.INVALID_ARGUMENT -> throw InvalidInput("Некорректные данные документа")
            io.grpc.Status.Code.UNAVAILABLE,
            io.grpc.Status.Code.DEADLINE_EXCEEDED,
            io.grpc.Status.Code.RESOURCE_EXHAUSTED,
            io.grpc.Status.Code.CANCELLED,
            -> unavailable()
            else -> throw DependencyFailure("Ошибка обработки документа")
        }
    } catch (_: IllegalArgumentException) {
        throw DependencyFailure("Некорректный ответ сервиса документов")
    }
    private fun unavailable(): Nothing = throw ru.creditcabinet.document.application.DocumentsUnavailable()

    @PreDestroy fun close() {
        channel?.shutdownNow()
        operationPool?.close()
    }
}

private fun ApplicationDocument.record(scope: DocumentScope): DocumentRecord = DocumentRecord(
    id,
    scope,
    type.name,
    fileName,
    storedName,
    sizeBytes,
    uploadedBy,
    uploadedAt,
    context.name,
    storageBackend.name,
    checksumSha256!!,
    privateToBank,
)
private fun DocumentRecord.document(): ApplicationDocument = ApplicationDocument(
    id, scope.applicationId,
    DocumentType.valueOf(
        type,
    ),
    fileName, storedName, sizeBytes, uploadedBy, uploadedAt, DocumentContext.valueOf(context), StorageBackend.valueOf(backend), checksum, privateToBank,
)
