package ru.creditcabinet.cataloggrpc

import io.grpc.BindableService
import io.grpc.Context
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.Timeout
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.mock.env.MockEnvironment
import ru.creditcabinet.catalog.application.CatalogUseCases
import ru.creditcabinet.catalog.domain.CatalogReader
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.contracts.catalog.v1.CatalogServiceGrpc
import ru.creditcabinet.contracts.catalog.v1.GetSubmissionSnapshotRequest
import ru.creditcabinet.contracts.catalog.v1.GetSubmissionSnapshotResponse
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.SubmissionProductSnapshot
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Timeout(5)
class CatalogGrpcTest {
    private val at = Instant.parse("2026-11-26T10:00:00Z")
    private val current = Product(
        code = "WORKING_CAPITAL",
        name = "Кредит на пополнение оборота",
        description = "Финансирование сезонных расходов",
        purposes = setOf(FinancingPurpose.WORKING_CAPITAL, FinancingPurpose.CASH_GAP),
        minAmount = 300_000,
        maxAmount = 30_000_000,
        minTermMonths = 3,
        maxTermMonths = 36,
        baseRate = BigDecimal("20.25"),
        autoApprovalLimit = 5_000_000,
        revolving = false,
        version = 2,
        effectiveFrom = at,
        effectiveTo = at,
        agentCommissionRateBps = 175,
    )
    private val alternative = current.copy(code = "INVESTMENT", version = 4, agentCommissionRateBps = 0)
    private val archived = current.copy(version = 1, lifecycleStatus = ProductLifecycleStatus.ARCHIVED, agentCommissionRateBps = 150)

    @Test
    fun `gRPC preserves snapshot terms and reads exact archived revision`() {
        withService(catalogService()) { client, _ ->
            val snapshot = assertNotNull(client.snapshotForSubmission(current.code))
            assertEquals(at, snapshot.evaluatedAt)
            assertEquals(current, snapshot.selected)
            assertEquals(listOf(current, alternative), snapshot.products)
            assertEquals(listOf(current, alternative), snapshot.revisions.map(ProductRevision::product))
            assertEquals(mapOf(current.code to 2L, alternative.code to 4L), snapshot.versions)
            assertEquals(archived, client.version(current.code, archived.version))
            assertNull(client.version(current.code, 200))
            assertNull(client.snapshotForSubmission("NOT_EFFECTIVE"))
        }
    }

    @Test
    fun `server rejects malformed product code`() {
        withService(catalogService()) { _, raw ->
            val failure = assertFailsWith<StatusRuntimeException> {
                raw.getSubmissionSnapshot(GetSubmissionSnapshotRequest.newBuilder().setSelectedProductCode("wrong code").build())
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, failure.status.code)
        }
    }

    @Test
    fun `expired deadline maps to unavailable and does not read a local substitute`() {
        val gotDeadline = AtomicBoolean()
        val silent = object : CatalogServiceGrpc.CatalogServiceImplBase() {
            override fun getSubmissionSnapshot(request: GetSubmissionSnapshotRequest, observer: StreamObserver<GetSubmissionSnapshotResponse>) {
                gotDeadline.set(Context.current().deadline != null)
            }
        }
        withService(silent, deadlineMs = 250) { client, _ ->
            val failure = assertFailsWith<ApiException> { client.snapshotForSubmission(current.code) }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.httpStatus)
            assertTrue(gotDeadline.get())
        }
    }

    @Test
    fun `incomplete snapshot maps to unavailable before submission can be pinned`() {
        val incomplete = object : CatalogServiceGrpc.CatalogServiceImplBase() {
            override fun getSubmissionSnapshot(request: GetSubmissionSnapshotRequest, observer: StreamObserver<GetSubmissionSnapshotResponse>) {
                observer.onNext(GetSubmissionSnapshotResponse.getDefaultInstance())
                observer.onCompleted()
            }
        }
        withService(incomplete) { client, _ ->
            val failure = assertFailsWith<ApiException> { client.snapshotForSubmission(current.code) }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.httpStatus)
        }
    }

    private fun catalogService(): CatalogGrpcService {
        val catalog = mock<CatalogReader>()
        val eligible = listOf(current, alternative).map { ProductRevision(it, null, "Синтетика", at) }
        val previous = ProductRevision(archived, null, "Предыдущая редакция", at)
        whenever(catalog.snapshotForSubmission(current.code))
            .thenReturn(SubmissionProductSnapshot(at, current, eligible.map(ProductRevision::product), eligible))
        whenever(catalog.version(archived.code, archived.version)).thenReturn(previous)
        return CatalogGrpcService(
            CatalogUseCases(catalog, mock(), {}, Clock.fixed(at, ZoneOffset.UTC)),
            JwtVerifier(JwtVerifier.DEV_DEFAULT_SECRET, MockEnvironment()),
        )
    }

    private fun withService(
        service: BindableService,
        deadlineMs: Long = 1000,
        action: (CatalogGrpcClient, CatalogServiceGrpc.CatalogServiceBlockingStub) -> Unit,
    ) {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        try {
            action(CatalogGrpcClient(channel, deadlineMs), CatalogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(1, TimeUnit.SECONDS))
        } finally {
            channel.shutdownNow().awaitTermination(1, TimeUnit.SECONDS)
            server.shutdownNow().awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
