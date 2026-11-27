package ru.creditcabinet.documents.grpc

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.ByteString
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.TlsServerCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import ru.creditcabinet.contracts.documents.v1.Content
import ru.creditcabinet.contracts.documents.v1.DocumentsServiceGrpc
import ru.creditcabinet.contracts.documents.v1.Empty
import ru.creditcabinet.contracts.documents.v1.ObjectRequest
import ru.creditcabinet.contracts.documents.v1.PutRequest
import ru.creditcabinet.contracts.documents.v1.Record
import ru.creditcabinet.contracts.documents.v1.Records
import ru.creditcabinet.contracts.documents.v1.Scope
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.DocumentConflict
import ru.creditcabinet.documents.DocumentRecord
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.documents.MAX_DOCUMENT_BYTES
import ru.creditcabinet.documents.application.DocumentsStorageRejected
import ru.creditcabinet.documents.application.DocumentsStorageUnavailable
import ru.creditcabinet.documents.application.DocumentsStore
import ru.creditcabinet.documents.sha256
import java.io.File
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.UUID

@Component
class CreditPeer(@Value("\${cabinet.documents.grpc.credit-peer}") private val peer: String) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val certificate = runCatching { call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)?.peerCertificates?.firstOrNull() as? X509Certificate }.getOrNull()
        val allowed = certificate?.subjectAlternativeNames.orEmpty().any { it.size == 2 && it[0] == 6 && it[1] == peer }
        if (!allowed) {
            call.close(Status.PERMISSION_DENIED, Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        return next.startCall(call, headers)
    }
}

@Component
class DocumentsRpc(private val store: DocumentsStore, private val mapper: ObjectMapper) : DocumentsServiceGrpc.DocumentsServiceImplBase() {
    private fun scope(scope: Scope): DocumentScope = DocumentScope(UUID.fromString(scope.companyId), UUID.fromString(scope.applicationId))
    private fun record(
        scope: Scope,
        value: Any,
    ): Record = Record.newBuilder().setScope(scope).setMetadataVersion(1).setMetadataJson(mapper.writeValueAsString(value)).build()
    private fun <T> reply(scope: Scope, observer: StreamObserver<T>, action: () -> T) {
        try {
            val result = store.owned(scope.epoch) {
                scope(scope)
                action().also { check(!io.grpc.Context.current().isCancelled) }
            }
            observer.onNext(result)
            observer.onCompleted()
        } catch (_: DocumentConflict) {
            observer.onError(Status.ALREADY_EXISTS.asRuntimeException())
        } catch (failure: ru.creditcabinet.common.exception.ApiException) {
            observer.onError(
                (
                    if (failure.httpStatus ==
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                    ) {
                        Status.UNAVAILABLE
                    } else {
                        Status.INVALID_ARGUMENT
                    }
                    ).asRuntimeException(),
            )
        } catch (
            _: IllegalArgumentException,
        ) {
            observer.onError(Status.INVALID_ARGUMENT.asRuntimeException())
        } catch (
            _: NoSuchElementException,
        ) {
            observer.onError(Status.NOT_FOUND.asRuntimeException())
        } catch (failure: DocumentsStorageUnavailable) {
            observer.onError(Status.UNAVAILABLE.withCause(failure).asRuntimeException())
        } catch (failure: DocumentsStorageRejected) {
            observer.onError(Status.FAILED_PRECONDITION.withCause(failure).asRuntimeException())
        } catch (failure: Exception) {
            observer.onError(Status.FAILED_PRECONDITION.withCause(failure).asRuntimeException())
        }
    }
    private fun content(bytes: ByteArray): Content = Content.newBuilder().setContent(ByteString.copyFrom(bytes)).setSha256(sha256(bytes)).build()
    override fun listDocuments(request: Scope, observer: StreamObserver<Records>) = reply(request, observer) {
        Records.newBuilder().addAllRecords(store.list(scope(request)).map { record(request, it) }).build()
    }
    override fun putDocument(request: PutRequest, observer: StreamObserver<Empty>) = reply(request.record.scope, observer) {
        require(
            request.record.metadataVersion == 1 &&
                request.record.metadataJson.toByteArray().size <= 1024 * 1024 &&
                request.content.size() in 1..MAX_DOCUMENT_BYTES,
        )
        val document = mapper.readValue(request.record.metadataJson, DocumentRecord::class.java)
        require(document.scope == scope(request.record.scope))
        store.put(document, request.content.toByteArray())
        Empty.getDefaultInstance()
    }
    override fun readDocument(
        request: ObjectRequest,
        observer: StreamObserver<Content>,
    ) = reply(request.scope, observer) { content(store.read(scope(request.scope), UUID.fromString(request.id), request.context)) }
    override fun deleteDocument(request: ObjectRequest, observer: StreamObserver<Empty>) = reply(request.scope, observer) {
        store.delete(scope(request.scope), UUID.fromString(request.id), request.context, Instant.parse(request.deletedAt))
        Empty.getDefaultInstance()
    }
    override fun listArtifacts(request: Scope, observer: StreamObserver<Records>) = reply(request, observer) {
        Records.newBuilder().addAllRecords(store.artifacts(scope(request)).map { record(request, it) }).build()
    }
    override fun prepareArtifact(request: Record, observer: StreamObserver<Record>) = reply(request.scope, observer) {
        require(request.metadataVersion == 1 && request.metadataJson.toByteArray().size <= 1024 * 1024)
        val artifact = mapper.readValue(request.metadataJson, ArtifactRecord::class.java)
        require(artifact.scope == scope(request.scope))
        record(request.scope, store.prepare(artifact))
    }
    override fun retryArtifact(
        request: ObjectRequest,
        observer: StreamObserver<Record>,
    ) = reply(request.scope, observer) { record(request.scope, store.retry(scope(request.scope), UUID.fromString(request.id))) }
    override fun readArtifact(
        request: ObjectRequest,
        observer: StreamObserver<Content>,
    ) = reply(request.scope, observer) { content(store.artifactBytes(scope(request.scope), UUID.fromString(request.id))) }
}

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["cabinet.documents.grpc.enabled"], havingValue = "true", matchIfMissing = true)
class DocumentsGrpcServer(
    private val service: DocumentsRpc,
    private val peer: CreditPeer,
    private val admission: DocumentsAdmission,
    @Value("\${cabinet.documents.grpc.port}") private val port: Int,
    @Value("\${cabinet.documents.grpc.host:127.0.0.1}") private val host: String,
    @Value("\${cabinet.documents.grpc.certificate}") private val certificate: File,
    @Value("\${cabinet.documents.grpc.key}") private val key: File,
    @Value("\${cabinet.documents.grpc.ca}") private val ca: File,
) : SmartLifecycle {
    private var server: Server? = null
    private var executor: java.util.concurrent.ExecutorService? = null
    override fun start() {
        val credentials = TlsServerCredentials.newBuilder().keyManager(
            certificate,
            key,
        ).trustManager(ca).clientAuth(TlsServerCredentials.ClientAuth.REQUIRE).build()
        executor = java.util.concurrent.ThreadPoolExecutor(2, 2, 0, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(8))
        server =
            NettyServerBuilder.forAddress(
                java.net.InetSocketAddress(host, port),
                credentials,
            ).maxInboundMessageSize(MAX_DOCUMENT_BYTES + 1024 * 1024).maxConcurrentCallsPerConnection(2)
                .executor(executor)
                .addService(ServerInterceptors.intercept(service, admission, peer)).build().start()
    }
    override fun stop() {
        server?.shutdownNow()
        executor?.shutdownNow()
        server = null
    }
    override fun isRunning(): Boolean = server != null
}
