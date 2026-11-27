package ru.creditcabinet.cataloggrpc

import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.TlsChannelCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.contracts.catalog.v1.CatalogServiceGrpc
import ru.creditcabinet.contracts.catalog.v1.CreateProductRequest
import ru.creditcabinet.contracts.catalog.v1.GetProductVersionRequest
import ru.creditcabinet.contracts.catalog.v1.GetProductVersionResponse
import ru.creditcabinet.contracts.catalog.v1.GetSubmissionSnapshotRequest
import ru.creditcabinet.contracts.catalog.v1.ListCurrentProductsRequest
import ru.creditcabinet.contracts.catalog.v1.ListProductHistoryRequest
import ru.creditcabinet.contracts.catalog.v1.ReplaceProductRequest
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.SubmissionProductCatalog
import ru.creditcabinet.product.domain.SubmissionProductSnapshot
import java.io.File
import java.util.concurrent.TimeUnit

val catalogAuthorization: Metadata.Key<String> = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

fun catalogTlsChannel(host: String, port: Int, certificate: File, key: File, serverCa: File): ManagedChannel {
    val credentials = TlsChannelCredentials.newBuilder().keyManager(certificate, key).trustManager(serverCa).build()
    return NettyChannelBuilder.forAddress(host, port, credentials).disableRetry().build()
}

class CatalogGrpcClient(channel: Channel, private val deadlineMs: Long) : SubmissionProductCatalog {
    private val stub = CatalogServiceGrpc.newBlockingStub(channel)

    init {
        require(deadlineMs > 0) { "Catalog gRPC deadline must be positive" }
    }

    override fun snapshotForSubmission(selectedProductCode: String): SubmissionProductSnapshot? = read {
        val request = GetSubmissionSnapshotRequest.newBuilder().setSelectedProductCode(selectedProductCode).build()
        withDeadline().getSubmissionSnapshot(request).toSnapshot(selectedProductCode)
    }

    fun version(code: String, version: Long): Product? = read {
        val request = GetProductVersionRequest.newBuilder().setRef(productRef(code, version)).build()
        val response = withDeadline().getProductVersion(request)
        require(response.hasProduct() && response.product.ref == request.ref)
        response.product.toProduct()
    }

    fun current(code: String? = null, effectiveOnly: Boolean = false): List<ProductRevision> = read {
        val request = ListCurrentProductsRequest.newBuilder().setEffectiveOnly(effectiveOnly)
            .apply { code?.let { setProductCode(it) } }.build()
        val products = withDeadline().listCurrentProducts(request).productsList.map { it.toRevision() }
        require(products.distinctBy { it.product.code }.size == products.size)
        require(code == null || products.all { it.product.code == code })
        require(!effectiveOnly || products.all { it.product.active })
        products
    } ?: throw unavailable()

    fun history(code: String): List<ProductRevision> = read {
        val request = ListProductHistoryRequest.newBuilder().setProductCode(code).build()
        val history = withDeadline().listProductHistory(request).productsList.map { it.toRevision() }
        require(history.all { it.product.code == code })
        require(history.zipWithNext().all { (newer, older) -> newer.product.version > older.product.version })
        history
    } ?: throw NotFoundException("Продукт не найден")

    fun create(product: Product, reason: String, authorization: String): ProductRevision = write(product.code, 1) {
        val request = CreateProductRequest.newBuilder().setProduct(product.copy(version = 1).toWire()).setReason(reason).build()
        withAuthorization(authorization).createProduct(request)
    }

    fun replace(product: Product, expectedVersion: Long, reason: String, authorization: String): ProductRevision =
        write(product.code, expectedVersion + 1) {
            val request = ReplaceProductRequest.newBuilder().setProduct(product.copy(version = expectedVersion).toWire())
                .setExpectedVersion(expectedVersion).setReason(reason).build()
            withAuthorization(authorization).replaceProduct(request)
        }

    private fun withDeadline() = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

    private fun withAuthorization(authorization: String) = withDeadline().withInterceptors(
        MetadataUtils.newAttachHeadersInterceptor(Metadata().apply { put(catalogAuthorization, authorization) }),
    )

    private fun <T> read(readResponse: () -> T): T? = try {
        readResponse()
    } catch (failure: StatusRuntimeException) {
        when (failure.status.code) {
            Status.Code.NOT_FOUND -> null
            Status.Code.INVALID_ARGUMENT -> throw BadRequestException("Некорректный запрос к каталогу")
            else -> throw unavailable(failure)
        }
    } catch (failure: IllegalArgumentException) {
        throw unavailable(failure)
    }

    private fun write(code: String, version: Long, action: () -> GetProductVersionResponse): ProductRevision = try {
        val response = action()
        require(response.hasProduct() && response.product.ref == productRef(code, version))
        response.product.toRevision()
    } catch (failure: StatusRuntimeException) {
        val message = failure.status.description
        throw when (failure.status.code) {
            Status.Code.INVALID_ARGUMENT -> BadRequestException(message ?: "Некорректные условия продукта")
            Status.Code.NOT_FOUND -> NotFoundException("Продукт не найден")
            Status.Code.ALREADY_EXISTS, Status.Code.ABORTED -> ConflictException(message ?: "Условия продукта изменились")
            Status.Code.UNAUTHENTICATED -> UnauthorizedException()
            Status.Code.PERMISSION_DENIED -> ForbiddenException("Недостаточно прав для этого действия")
            else -> unavailable(failure)
        }
    } catch (failure: IllegalArgumentException) {
        throw unavailable(failure)
    }

    private fun unavailable(failure: Throwable? = null): ApiException {
        if (failure is StatusRuntimeException) logger.warn("Catalog RPC returned {}", failure.status.code)
        if (failure is IllegalArgumentException) logger.warn("Catalog RPC response did not match the contract")
        return ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Каталог временно недоступен — попробуйте позже")
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CatalogGrpcClient::class.java)
    }
}
