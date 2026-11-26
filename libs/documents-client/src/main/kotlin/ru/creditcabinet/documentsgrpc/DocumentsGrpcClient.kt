package ru.creditcabinet.documentsgrpc

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.TlsChannelCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import ru.creditcabinet.contracts.documents.v1.DocumentsServiceGrpc
import ru.creditcabinet.contracts.documents.v1.ObjectRequest
import ru.creditcabinet.contracts.documents.v1.PutRequest
import ru.creditcabinet.contracts.documents.v1.Record
import ru.creditcabinet.contracts.documents.v1.Scope
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.DocumentRecord
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.documents.DocumentsPort
import ru.creditcabinet.documents.MAX_DOCUMENT_BYTES
import ru.creditcabinet.documents.sha256
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

fun documentsTlsChannel(host: String, port: Int, certificate: File, key: File, ca: File): ManagedChannel =
    NettyChannelBuilder.forAddress(host, port, TlsChannelCredentials.newBuilder().keyManager(certificate, key).trustManager(ca).build())
        .maxInboundMessageSize(MAX_DOCUMENT_BYTES + 1024 * 1024).disableRetry().build()

class DocumentsGrpcClient(channel: Channel, private val mapper: ObjectMapper, private val epoch: Long, private val deadlineMs: Long = 30000) : DocumentsPort {
    private val stub = DocumentsServiceGrpc.newBlockingStub(channel)
    init { require(epoch > 0 && deadlineMs in 1..180000) }
    private fun stub() = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
    private fun scope(scope: DocumentScope): Scope = Scope.newBuilder().setCompanyId(scope.companyId.toString()).setApplicationId(scope.applicationId.toString()).setEpoch(epoch).build()
    private fun objectRequest(scope: DocumentScope, id: UUID, context: String = "", at: Instant? = null): ObjectRequest =
        ObjectRequest.newBuilder().setScope(scope(scope)).setId(id.toString()).setContext(context).apply { at?.let { setDeletedAt(it.toString()) } }.build()
    private fun record(scope: DocumentScope, value: Any): Record = Record.newBuilder().setScope(scope(scope)).setMetadataJson(mapper.writeValueAsString(value)).build()
    private fun document(scope: DocumentScope, wire: Record): DocumentRecord = mapper.readValue(wire.metadataJson, DocumentRecord::class.java).also {
        require(it.scope == scope && wire.scope == scope(scope)); it.validate()
    }
    private fun artifact(scope: DocumentScope, wire: Record): ArtifactRecord = mapper.readValue(wire.metadataJson, ArtifactRecord::class.java).also {
        require(it.scope == scope && wire.scope == scope(scope))
    }
    override fun list(scope: DocumentScope): List<DocumentRecord> = stub().listDocuments(scope(scope)).recordsList.map { document(scope, it) }
    override fun put(record: DocumentRecord, bytes: ByteArray) {
        record.validate(); require(bytes.size.toLong() == record.sizeBytes && sha256(bytes) == record.checksum)
        stub().putDocument(PutRequest.newBuilder().setRecord(record(record.scope, record)).setContent(ByteString.copyFrom(bytes)).build())
    }
    private fun content(response: ru.creditcabinet.contracts.documents.v1.Content): ByteArray = response.content.toByteArray().also {
        require(it.size in 1..MAX_DOCUMENT_BYTES && sha256(it) == response.sha256)
    }
    override fun read(scope: DocumentScope, id: UUID, context: String): ByteArray = content(stub().readDocument(objectRequest(scope, id, context)))
    override fun delete(scope: DocumentScope, id: UUID, context: String, at: Instant) { stub().deleteDocument(objectRequest(scope, id, context, at)) }
    override fun artifacts(scope: DocumentScope): List<ArtifactRecord> = stub().listArtifacts(scope(scope)).recordsList.map { artifact(scope, it) }
    override fun prepare(record: ArtifactRecord): ArtifactRecord = artifact(record.scope, stub().prepareArtifact(record(record.scope, record))).also {
        require(it.offerId == record.offerId && it.kind == record.kind && it.phase == record.phase && it.templateVersion == record.templateVersion && it.inputHash == record.inputHash && it.inputJson == record.inputJson)
    }
    override fun retry(scope: DocumentScope, id: UUID): ArtifactRecord = artifact(scope, stub().retryArtifact(objectRequest(scope, id))).also { require(it.id == id) }
    override fun artifactBytes(scope: DocumentScope, id: UUID): ByteArray = content(stub().readArtifact(objectRequest(scope, id)))
}
