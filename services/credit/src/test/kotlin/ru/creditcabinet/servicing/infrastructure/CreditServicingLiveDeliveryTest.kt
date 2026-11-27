@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.*
import ru.creditcabinet.application.service.Transitions
import ru.creditcabinet.integration.CoreBanking
import ru.creditcabinet.integration.LoanBooked
import ru.creditcabinet.offer.*
import ru.creditcabinet.offer.application.ContractTextStore
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.product.domain.*
import ru.creditcabinet.servicing.application.LoanServicingService
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.grpc.*
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlin.test.*

/** Actual Credit outbox -> independent candidate Servicing process -> target PostgreSQL. */
@SpringBootTest
@Import(PipelineFakesConfig::class)
@EnabledIfEnvironmentVariable(named = "ALPHA_SERVICING_CREDIT_LIVE", matches = "true")
class CreditServicingLiveDeliveryTest {
    @Autowired private lateinit var context: org.springframework.context.ApplicationContext

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tx: TransactionTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var applications: ApplicationRepository

    @Autowired private lateinit var transitions: Transitions

    @Autowired private lateinit var jobs: PipelineJobs

    @Autowired private lateinit var products: ProductCatalog

    @Autowired private lateinit var loans: Loans

    @Autowired private lateinit var localOpening: LoanServicingService

    @Autowired private lateinit var clock: Clock

    @Test fun `real grpc delivery survives lost producer commit and completes only one booked issue`() {
        val tls = Path.of(System.getenv("ALPHA_SERVICING_LIVE_TLS"))
        val channel =
            servicingTlsChannel(
                "localhost",
                System.getenv("ALPHA_SERVICING_LIVE_PORT").toInt(),
                tls.resolve("credit.crt").toFile(),
                tls.resolve("credit.key").toFile(),
                tls.resolve("ca.crt").toFile(),
            )
        val operations = ServicingGrpcClient(channel, mapper, { null })
        try {
            assertEquals("REMOTE", jdbc.queryForObject("select writer from servicing_writer_fence where singleton", String::class.java))
            val app = applications.save(
                LoanApplication(
                    company = CompanySnapshot(UUID.randomUUID(), "0012345678", "Synthetic live", LegalForm.LEGAL_ENTITY),
                    createdBy = UUID.randomUUID(),
                    terms = ApplicationTerms("OVERDRAFT", FinancingPurpose.CASH_GAP, amount = 1_000_000, termMonths = 12),
                    status = ApplicationStatus.DISBURSING,
                    createdAt = clock.instant(),
                    submittedAt = clock.instant(),
                    productCatalogSnapshot = mapOf("OVERDRAFT" to 1L),
                ),
            )
            val cycle = UUID.randomUUID()
            val text = "Immutable contract terms for live transport $cycle"
            val snapshot = ContractTextSnapshot(UUID.randomUUID(), app.id, cycle, 1, text, ContractTexts.sha256(text), clock.instant())
            val snapshots = mock<ContractTextStore> { on { find(cycle) } doReturn snapshot }
            val signed =
                SigningSessionRecord(
                    applicationId = app.id, offerId = cycle, externalSessionId = UUID.randomUUID(), code = "123456",
                    expiresAt = clock.instant().plusSeconds(600),
                    signatureId = UUID.randomUUID(), signedAt = clock.instant(), createdAt = clock.instant(), contractSnapshotId = snapshot.id,
                )
            val sessions = mock<SigningSessions> { on { signedFor(app.id, cycle) } doReturn signed }
            val opening = LoanOpeningOutbox(jdbc, mapper, snapshots, sessions, operations, localOpening, tx, clock, "remote")
            val offer =
                Offer(
                    id = cycle,
                    applicationId = app.id,
                    decisionId = UUID.randomUUID(),
                    kind = OfferKind.APPROVED,
                    productCode = "OVERDRAFT",
                    amount = 1_000_000,
                    termMonths = 12,
                    rate = BigDecimal("20.00"),
                    monthlyPayment = BigDecimal("100000.00"),
                    totalPayment = BigDecimal("1200000.00"),
                    validUntil = clock.instant().plusSeconds(600), status = OfferStatus.ACCEPTED, createdAt = clock.instant(),
                )
            val offers = mock<OfferRepository> { on { current(app.id) } doReturn offer }
            val core = mock<CoreBanking> {
                on { disburse(any()) } doReturn
                    LoanBooked(
                        "LIVE-CREDIT-${app.id.toString().take(8)}",
                        "45206810000000000001",
                        "40702810000000000001",
                        clock.instant(),
                        clock.instant().atZone(ZoneId.of("Europe/Moscow")).toLocalDate().plusMonths(12),
                    )
            }
            val pinned = mock<ProductCatalog>()
            whenever(
                pinned.version("OVERDRAFT", 1),
            ).thenReturn(products.version("OVERDRAFT", 1)).thenThrow(IllegalStateException("Current Catalog unavailable"))
            val steps = disbursementPipeline(context, opening, core, offers, sessions, pinned)
            val jobId = UUID.randomUUID()
            jdbc.update(
                "insert into pipeline_jobs(id,application_id,step,status,attempts,run_at,created_at,updated_at,locked_until) values(?,?,'DISBURSEMENT','RUNNING',1,now(),now(),now(),now()+interval '5 minutes')",
                jobId,
                app.id,
            )
            val job = PipelineJob(jobId, app.id, PipelineStep.DISBURSEMENT, 1)
            steps.run(
                job.copy(
                    leaseUntil = jdbc.queryForObject("select locked_until from pipeline_jobs where id=?", java.sql.Timestamp::class.java, job.id)!!.toInstant(),
                ),
            )
            assertEquals(ApplicationStatus.DISBURSING, applications.find(app.id)!!.status)
            assertNull(loans.find(app.id))
            val command = mapper.readValue(
                jdbc.queryForObject("select command_json::text from servicing_open_outbox where application_id=?", String::class.java, app.id),
                OpenLoanCommand::class.java,
            )
            val receipt = operations.open(command)
            assertFailsWith<IllegalStateException> {
                tx.executeWithoutResult {
                    opening.acknowledge(command, receipt)
                    error("producer crashed before commit")
                }
            }
            assertEquals("PENDING", jdbc.queryForObject("select state from servicing_open_outbox where application_id=?", String::class.java, app.id))
            assertTrue(opening.deliverOne())
            assertEquals(receipt, operations.open(command))
            assertEquals("CONFIRMED", jdbc.queryForObject("select state from servicing_open_outbox where application_id=?", String::class.java, app.id))
            jdbc.update("update pipeline_jobs set status='RUNNING',attempts=1,locked_until=now()+interval '5 minutes' where id=?", jobId)
            steps.run(
                job.copy(
                    leaseUntil = jdbc.queryForObject("select locked_until from pipeline_jobs where id=?", java.sql.Timestamp::class.java, job.id)!!.toInstant(),
                ),
            )
            assertEquals(ApplicationStatus.DISBURSED, applications.find(app.id)!!.status)
            assertNotNull(loans.find(app.id))
            verify(core, times(1)).disburse(any())
            verify(pinned, times(1)).version("OVERDRAFT", 1)
        } finally {
            channel.shutdownNow()
        }
    }
}
