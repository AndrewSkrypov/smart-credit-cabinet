package ru.creditcabinet.cataloggrpc

import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import ru.creditcabinet.catalog.application.Catalog
import ru.creditcabinet.catalog.domain.CatalogEditor
import ru.creditcabinet.catalog.domain.CatalogFenced
import ru.creditcabinet.catalog.domain.CatalogUnavailable
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.contracts.catalog.v1.CatalogServiceGrpc
import ru.creditcabinet.contracts.catalog.v1.CreateProductRequest
import ru.creditcabinet.contracts.catalog.v1.GetProductVersionRequest
import ru.creditcabinet.contracts.catalog.v1.GetProductVersionResponse
import ru.creditcabinet.contracts.catalog.v1.GetSubmissionSnapshotRequest
import ru.creditcabinet.contracts.catalog.v1.GetSubmissionSnapshotResponse
import ru.creditcabinet.contracts.catalog.v1.ListCurrentProductsRequest
import ru.creditcabinet.contracts.catalog.v1.ListProductHistoryRequest
import ru.creditcabinet.contracts.catalog.v1.ListProductsResponse
import ru.creditcabinet.contracts.catalog.v1.ProductVersion
import ru.creditcabinet.contracts.catalog.v1.ReplaceProductRequest
import ru.creditcabinet.product.domain.ProductRevision

internal class CatalogGrpcService(
    private val catalog: Catalog,
    private val verifier: JwtVerifier,
) : CatalogServiceGrpc.CatalogServiceImplBase() {
    override fun getSubmissionSnapshot(request: GetSubmissionSnapshotRequest, observer: StreamObserver<GetSubmissionSnapshotResponse>) {
        respond(observer) {
            catalog.snapshotForSubmission(request.selectedProductCode)?.toWire()
                ?: throw NotFoundException("Продукт выключен из каталога")
        }
    }

    override fun getProductVersion(request: GetProductVersionRequest, observer: StreamObserver<GetProductVersionResponse>) {
        respond(observer) {
            val ref = request.ref
            require(request.hasRef()) { "Некорректная редакция продукта" }
            (catalog.version(ref.code, ref.version) ?: throw NotFoundException("Редакция продукта не найдена")).response()
        }
    }

    override fun listCurrentProducts(request: ListCurrentProductsRequest, observer: StreamObserver<ListProductsResponse>) {
        respond(observer) {
            val code = request.productCode.takeIf { request.hasProductCode() }
            products(catalog.current(code, request.effectiveOnly))
        }
    }

    override fun listProductHistory(request: ListProductHistoryRequest, observer: StreamObserver<ListProductsResponse>) {
        respond(observer) {
            val history = catalog.history(request.productCode)
            if (history.isEmpty()) throw NotFoundException("Продукт не найден")
            products(history)
        }
    }

    override fun createProduct(request: CreateProductRequest, observer: StreamObserver<GetProductVersionResponse>) {
        respond(observer) {
            val editor = requireEditor()
            catalog.create(product(request.hasProduct(), request.product), editor, request.reason).response()
        }
    }

    override fun replaceProduct(request: ReplaceProductRequest, observer: StreamObserver<GetProductVersionResponse>) {
        respond(observer) {
            val editor = requireEditor()
            catalog.replace(product(request.hasProduct(), request.product), request.expectedVersion, editor, request.reason).response()
        }
    }

    private fun requireEditor(): CatalogEditor =
        verifier.requireRole(catalogBearer.get(), Roles.RISK_ADMIN).let { CatalogEditor(it.userId, it.fullName) }

    private fun product(present: Boolean, wire: ProductVersion) = wire.also {
        require(present && !it.hasAudit()) { "Требуются условия продукта без аудита" }
    }.toProduct()

    private fun products(revisions: List<ProductRevision>) =
        ListProductsResponse.newBuilder().addAllProducts(revisions.map(ProductRevision::toWire)).build()

    private fun ProductRevision.response() = GetProductVersionResponse.newBuilder().setProduct(toWire()).build()

    private fun <T> respond(observer: StreamObserver<T>, load: () -> T) {
        val response = try {
            load()
        } catch (failure: StatusRuntimeException) {
            return observer.onError(failure)
        } catch (_: CatalogFenced) {
            return observer.onError(Status.UNAVAILABLE.asRuntimeException())
        } catch (_: UnauthorizedException) {
            return observer.onError(Status.UNAUTHENTICATED.asRuntimeException())
        } catch (_: ForbiddenException) {
            return observer.onError(Status.PERMISSION_DENIED.asRuntimeException())
        } catch (_: NotFoundException) {
            return observer.onError(Status.NOT_FOUND.asRuntimeException())
        } catch (failure: ConflictException) {
            return observer.onError(Status.ABORTED.withDescription(failure.message).asRuntimeException())
        } catch (failure: IllegalArgumentException) {
            return observer.onError(Status.INVALID_ARGUMENT.withDescription(failure.message).asRuntimeException())
        } catch (failure: CatalogUnavailable) {
            logger.warn("Catalog database operation failed", failure)
            return observer.onError(Status.UNAVAILABLE.asRuntimeException())
        } catch (failure: RuntimeException) {
            logger.error("Catalog operation failed", failure)
            return observer.onError(Status.INTERNAL.asRuntimeException())
        }
        observer.onNext(response)
        observer.onCompleted()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(CatalogGrpcService::class.java)
    }
}

internal val catalogBearer: Context.Key<String> = Context.key("catalog-bearer")
