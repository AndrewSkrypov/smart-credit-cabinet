package ru.creditcabinet.document.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.grpc.ManagedChannel
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.documents.DocumentRecord
import ru.creditcabinet.documents.DocumentScope
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
) : DocumentRepository {
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
    val remote: Boolean get() {
        val gate = jdbc.queryForMap("select owner,epoch from document_ownership where singleton")
        if (mode !in setOf("local", "remote") || gate["owner"] != mode.uppercase() || (gate["epoch"] as Number).toLong() != epoch) unavailable()
        return mode == "remote"
    }
    fun client(): DocumentsGrpcClient { check(remote); return client }
    fun scope(applicationId: UUID): DocumentScope = DocumentScope(jdbc.queryForObject("select company_id from applications where id=?", UUID::class.java, applicationId)!!, applicationId)
    fun put(document: ApplicationDocument, bytes: ByteArray) = rpc { client().put(document.record(scope(document.applicationId)), bytes) }
    fun bytes(document: ApplicationDocument): StoredFile = rpc {
        val bytes = client().read(scope(document.applicationId), document.id, document.context.name)
        require(bytes.size.toLong() == document.sizeBytes && ru.creditcabinet.documents.sha256(bytes) == document.checksumSha256)
        StoredFile(bytes.size.toLong(), bytes.inputStream())
    }
    fun all(applicationId: UUID): List<DocumentRecord> = rpc { client().list(scope(applicationId)) }
    override fun list(applicationId: UUID): List<ApplicationDocument> = if (!remote) local.list(applicationId) else
        all(applicationId).filter { it.context == "BUSINESS" && it.deletedAt == null }.map { it.document() }.sortedBy { it.uploadedAt }
    override fun listForPacket(applicationId: UUID, submittedAt: Instant?, documentId: UUID?): List<ApplicationDocument> {
        if (!remote) return local.listForPacket(applicationId, submittedAt, documentId)
        val packet = jdbc.query("select document_ids from financing_scenario_publications where application_id=? and status='AWAITING_CLIENT' order by revision desc limit 1", { rs, _ -> mapper.readValue<List<UUID>>(rs.getString(1)) }, applicationId).firstOrNull()
        return all(applicationId).filter { it.context == "BUSINESS" && (documentId == null || it.id == documentId) &&
            ((it.deletedAt == null && (packet == null || (submittedAt != null && !it.uploadedAt.isBefore(submittedAt)))) || it.id in packet.orEmpty()) }
            .map { it.document() }.sortedBy { it.uploadedAt }
    }
    override fun find(applicationId: UUID, documentId: UUID): ApplicationDocument? = if (!remote) local.find(applicationId, documentId) else all(applicationId).firstOrNull { it.id == documentId && it.deletedAt == null }?.document()
    override fun save(document: ApplicationDocument) { check(!remote); local.save(document) }
    override fun markDeleted(documentId: UUID, at: Instant) { check(!remote); local.markDeleted(documentId, at) }
    override fun markDeleted(applicationId: UUID, documentId: UUID, at: Instant) {
        if (!remote) { local.markDeleted(documentId, at); return }
        val document = find(applicationId, documentId) ?: throw ru.creditcabinet.common.exception.NotFoundException("Документ не найден")
        rpc { client().delete(scope(applicationId), documentId, document.context.name, at) }
    }
    override fun isChatAttached(documentId: UUID): Boolean = local.isChatAttached(documentId)
    fun <T> rpc(action: () -> T): T = try { action() }
        catch (_: io.grpc.StatusRuntimeException) { unavailable() }
        catch (_: IllegalArgumentException) { unavailable() }
    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Документы временно недоступны")
    @PreDestroy fun close() { channel?.shutdownNow() }
}

private fun ApplicationDocument.record(scope: DocumentScope): DocumentRecord = DocumentRecord(id, scope, type.name, fileName, storedName, sizeBytes, uploadedBy, uploadedAt, context.name, storageBackend.name, checksumSha256!!, privateToBank)
private fun DocumentRecord.document(): ApplicationDocument = ApplicationDocument(id, scope.applicationId, DocumentType.valueOf(type), fileName, storedName, sizeBytes, uploadedBy, uploadedAt, DocumentContext.valueOf(context), StorageBackend.valueOf(backend), checksum, privateToBank)
