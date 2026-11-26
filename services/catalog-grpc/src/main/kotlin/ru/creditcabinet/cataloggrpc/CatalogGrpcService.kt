package ru.creditcabinet.cataloggrpc

import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.catalogjdbc.JdbcProductCatalog
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
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import ru.creditcabinet.product.domain.ProductRevision
import java.time.Clock

internal class CatalogGrpcService(
    private val catalog: JdbcProductCatalog,
    private val jdbc: JdbcTemplate,
    private val transactions: TransactionTemplate,
    private val verifier: JwtVerifier,
    private val clock: Clock,
) : CatalogServiceGrpc.CatalogServiceImplBase() {
    override fun getSubmissionSnapshot(request: GetSubmissionSnapshotRequest, observer: StreamObserver<GetSubmissionSnapshotResponse>) {
        respond(observer) {
            require(validProductCode(request.selectedProductCode)) { "Некорректный код продукта" }
            requireAuthority()
            catalog.snapshotForSubmission(request.selectedProductCode)?.toWire()
                ?: throw NotFoundException("Продукт выключен из каталога")
        }
    }

    override fun getProductVersion(request: GetProductVersionRequest, observer: StreamObserver<GetProductVersionResponse>) {
        respond(observer) {
            val ref = request.ref
            require(request.hasRef() && validProductCode(ref.code) && ref.version > 0) { "Некорректная редакция продукта" }
            (catalog.revision(ref.code, ref.version) ?: throw NotFoundException("Редакция продукта не найдена")).response()
        }
    }

    override fun listCurrentProducts(request: ListCurrentProductsRequest, observer: StreamObserver<ListProductsResponse>) {
        respond(observer) {
            val code = request.productCode.takeIf { request.hasProductCode() }
            require(code == null || validProductCode(code)) { "Некорректный код продукта" }
            requireAuthority()
            val at = clock.instant().takeIf { request.effectiveOnly }
            products(catalog.currentRevisions(code, at))
        }
    }

    override fun listProductHistory(request: ListProductHistoryRequest, observer: StreamObserver<ListProductsResponse>) {
        respond(observer) {
            require(validProductCode(request.productCode)) { "Некорректный код продукта" }
            val history = catalog.history(request.productCode)
            if (history.isEmpty()) throw NotFoundException("Продукт не найден")
            products(history)
        }
    }

    override fun createProduct(request: CreateProductRequest, observer: StreamObserver<GetProductVersionResponse>) {
        respond(observer) {
            val author = requireEditor()
            val candidate = product(request.hasProduct(), request.product)
            require(candidate.version == 1L && candidate.lifecycleStatus == ProductLifecycleStatus.DRAFT) {
                "Новый продукт должен быть черновиком первой версии"
            }
            val reason = reason(request.reason)
            checkNotNull(
                transactions.execute {
                    requireAuthority()
                    catalog.create(candidate, author.userId, reason, clock.instant(), author.fullName, author.role)
                },
            ).response()
        }
    }

    override fun replaceProduct(request: ReplaceProductRequest, observer: StreamObserver<GetProductVersionResponse>) {
        respond(observer) {
            val author = requireEditor()
            val candidate = product(request.hasProduct(), request.product)
            val expected = request.expectedVersion
            require(expected in 1 until Long.MAX_VALUE && expected == candidate.version) { "Укажите текущую версию продукта" }
            val reason = reason(request.reason)
            checkNotNull(
                transactions.execute {
                    requireAuthority()
                    catalog.replace(candidate, expected, author.userId, reason, clock.instant(), author.fullName, author.role)
                },
            ).response()
        }
    }

    private fun requireAuthority() {
        val enabled = jdbc.queryForObject("select writable from catalog_cutover.authority where singleton", Boolean::class.java)
        if (enabled != true) throw Status.UNAVAILABLE.withDescription("Catalog authority is fenced").asRuntimeException()
    }

    private fun requireEditor() = verifier.requireRole(catalogBearer.get(), Roles.RISK_ADMIN)

    private fun product(present: Boolean, wire: ProductVersion) = wire.also {
        require(present && !it.hasAudit()) { "Требуются условия продукта без аудита" }
    }.toProduct()

    private fun reason(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 1000) { "Опишите причину изменения — до 1000 символов" }
        return raw.trim()
    }

    private fun products(revisions: List<ProductRevision>) =
        ListProductsResponse.newBuilder().addAllProducts(revisions.map(ProductRevision::toWire)).build()

    private fun ProductRevision.response() = GetProductVersionResponse.newBuilder().setProduct(toWire()).build()

    private fun <T> respond(observer: StreamObserver<T>, load: () -> T) {
        val response = try {
            load()
        } catch (failure: StatusRuntimeException) {
            return observer.onError(failure)
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
        } catch (failure: DataAccessException) {
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
