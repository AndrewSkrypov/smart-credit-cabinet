@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.*
import ru.creditcabinet.offer.*
import ru.creditcabinet.offer.application.ContractTextStore
import ru.creditcabinet.offer.application.ContractTexts
import ru.creditcabinet.offer.application.OfferService
import ru.creditcabinet.offer.domain.ContractTextSnapshot
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.servicing.application.LoanServicingService
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.domain.ServicingConflict
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

/** Real Credit PostgreSQL transactions; transport loss is injected explicitly, never claimed as live RPC. */
@SpringBootTest
@Import(PipelineFakesConfig::class)
class LoanOpeningAtomicityIntegrationTest {
    @Autowired private lateinit var context: org.springframework.context.ApplicationContext

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tx: TransactionTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var applications: ApplicationRepository

    @Autowired private lateinit var operations: ServicingOperations

    @Autowired private lateinit var localOpening: LoanServicingService

    @Autowired private lateinit var clock: Clock

    @Autowired private lateinit var loans: Loans

    @Autowired private lateinit var transitions: ru.creditcabinet.application.service.Transitions

    @Autowired private lateinit var jobs: PipelineJobs

    @Autowired private lateinit var products: ru.creditcabinet.product.domain.ProductCatalog
    private val created = mutableListOf<UUID>()

    @org.junit.jupiter.api.AfterEach fun cleanup() {
        created.forEach { id ->
            jdbc.update("delete from servicing_open_outbox where application_id=?", id)
            jdbc.update("delete from loan_accounts where application_id=?", id)
        }
        created.clear()
    }

    private data class Fixture(val app: LoanApplication, val cycle: UUID, val loan: Loan, val outbox: LoanOpeningOutbox, val signed: SigningSessionRecord)
    private fun fixture(): Fixture {
        val app = applications.save(
            LoanApplication(
                company = CompanySnapshot(UUID.randomUUID(), "0012345678", "Synthetic", LegalForm.LEGAL_ENTITY),
                createdBy = UUID.randomUUID(),
                terms = ApplicationTerms("OVERDRAFT", FinancingPurpose.CASH_GAP, amount = 1_000_000, termMonths = 12),
                status = ApplicationStatus.DISBURSING,
                createdAt = clock.instant(),
                submittedAt = clock.instant(),
                productCatalogSnapshot = mapOf("OVERDRAFT" to 1L),
            ),
        )
        val cycle = UUID.randomUUID()
        val text = "Immutable signed terms $cycle"
        val snapshot = ContractTextSnapshot(UUID.randomUUID(), app.id, cycle, 1, text, ContractTexts.sha256(text), clock.instant())
        val snapshots = mock<ContractTextStore> { on { find(cycle) } doReturn snapshot }
        val signed =
            SigningSessionRecord(
                applicationId = app.id, offerId = cycle, externalSessionId = UUID.randomUUID(), code = "123456",
                expiresAt = clock.instant().plusSeconds(600),
                signatureId = UUID.randomUUID(), signedAt = clock.instant(), createdAt = clock.instant(), contractSnapshotId = snapshot.id,
            )
        val sessions = mock<SigningSessions> { on { signedFor(app.id, cycle) } doReturn signed }
        val loan =
            Loan(
                app.id,
                "AT-${app.id.toString().take(8)}",
                "45206810000000000001", "40702810000000000001", 1_000_000, 12, BigDecimal("20.00"), true, clock.instant(), LocalDate.parse("2027-10-01"),
            )
        created += app.id
        return Fixture(app, cycle, loan, LoanOpeningOutbox(jdbc, mapper, snapshots, sessions, operations, localOpening, tx, clock, "remote"), signed)
    }
    private fun command(
        appId: UUID,
    ): OpenLoanCommand = mapper.readValue(
        jdbc.queryForObject("select command_json::text from servicing_open_outbox where application_id=?", String::class.java, appId),
        OpenLoanCommand::class.java,
    )

    @Test fun `Credit rollback removes opening command and leaves no visible issued loan`() {
        val fixture = fixture()
        assertFailsWith<IllegalStateException> {
            tx.executeWithoutResult {
                assertFalse(fixture.outbox.prepareOrConfirmed(fixture.app, fixture.loan, fixture.cycle))
                error("rollback after append")
            }
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from servicing_open_outbox where application_id=?", Int::class.java, fixture.app.id))
        assertNull(loans.find(fixture.app.id))
        assertNull(operations.byApplication(fixture.app.id))
    }

    @Test fun `response loss before Credit ack commit and restart replays same account receipt`() {
        val fixture = fixture()
        fixture.outbox.requireSignedSnapshot(fixture.app.id, fixture.cycle)
        tx.executeWithoutResult { assertFalse(fixture.outbox.prepareOrConfirmed(fixture.app, fixture.loan, fixture.cycle)) }
        assertEquals(fixture.loan, fixture.outbox.bookedLoan(fixture.app.id))
        assertNull(loans.find(fixture.app.id))
        assertEquals(ApplicationStatus.DISBURSING, applications.find(fixture.app.id)!!.status)
        val command = command(fixture.app.id)
        val accepted = operations.open(command) // remote side commits; producer dies before its ack transaction commits
        assertFailsWith<IllegalStateException> {
            tx.executeWithoutResult {
                fixture.outbox.acknowledge(command, accepted)
                error("crash before producer commit")
            }
        }
        assertEquals("PENDING", jdbc.queryForObject("select state from servicing_open_outbox where application_id=?", String::class.java, fixture.app.id))
        assertEquals(accepted, operations.open(command)) // reconstructed adapter sees durable target receipt
        tx.executeWithoutResult { fixture.outbox.acknowledge(command, accepted) }
        tx.executeWithoutResult { fixture.outbox.acknowledge(command, accepted) } // duplicate ack is harmless
        tx.executeWithoutResult { assertTrue(fixture.outbox.prepareOrConfirmed(fixture.app, fixture.loan, fixture.cycle)) }
        assertEquals(1, jdbc.queryForObject("select count(*) from loan_accounts where application_id=?", Int::class.java, fixture.app.id))
        assertEquals(1, jdbc.queryForObject("select count(*) from loan_tranches where loan_account_id=?", Int::class.java, accepted.accountId))
    }

    @Test fun `late cycle version or payload acknowledgment cannot confirm current command`() {
        val fixture = fixture()
        tx.executeWithoutResult { fixture.outbox.prepareOrConfirmed(fixture.app, fixture.loan, fixture.cycle) }
        val command = command(fixture.app.id)
        val receipt = operations.open(command)
        for (stale in listOf(
            receipt.copy(cycleId = UUID.randomUUID()),
            receipt.copy(applicationVersion = receipt.applicationVersion - 1),
            receipt.copy(commandId = UUID.randomUUID()),
            receipt.copy(payloadSha256 = "b".repeat(64)),
        )) {
            assertFailsWith<ServicingConflict> { tx.executeWithoutResult { fixture.outbox.acknowledge(command, stale) } }
        }
        assertEquals("PENDING", jdbc.queryForObject("select state from servicing_open_outbox where application_id=?", String::class.java, fixture.app.id))
        assertFailsWith<ServicingConflict> {
            tx.executeWithoutResult { fixture.outbox.prepareOrConfirmed(fixture.app, fixture.loan.copy(amount = 900_000), fixture.cycle) }
        }
        assertFailsWith<ServicingConflict> { tx.executeWithoutResult { fixture.outbox.prepareOrConfirmed(fixture.app, fixture.loan, UUID.randomUUID()) } }
        assertEquals(receipt, operations.open(command))
    }

    @Test fun `waiting receipt parks job without spending failure budget`() {
        val fixture = fixture()
        val jobId = UUID.randomUUID()
        jdbc.update(
            "insert into pipeline_jobs(id,application_id,step,status,attempts,run_at,created_at,updated_at,locked_until) values(?,?,'DISBURSEMENT','RUNNING',1,now(),now(),now(),now()+interval '5 minutes')",
            jobId,
            fixture.app.id,
        )
        tx.executeWithoutResult { fixture.outbox.awaitReceipt(jobId, clock.instant().plusSeconds(2)) }
        assertEquals(0, jdbc.queryForObject("select attempts from pipeline_jobs where id=?", Int::class.java, jobId))
        assertEquals("PENDING", jdbc.queryForObject("select status from pipeline_jobs where id=?", String::class.java, jobId))
        assertNull(loans.find(fixture.app.id))
    }

    @Test fun `pipeline waits durable receipt without second core booking and completes only after acknowledgment`() {
        val fixture = fixture()
        val offer = Offer(
            id = fixture.cycle, applicationId = fixture.app.id, decisionId = UUID.randomUUID(), kind = OfferKind.APPROVED,
            productCode = "OVERDRAFT", amount = fixture.loan.amount, termMonths = 12, rate = fixture.loan.rate,
            monthlyPayment = BigDecimal("100000.00"), totalPayment = BigDecimal("1200000.00"), validUntil = clock.instant().plusSeconds(600),
            status = OfferStatus.ACCEPTED, createdAt = clock.instant(),
        )
        val offerStore = mock<OfferRepository> { on { current(fixture.app.id) } doReturn offer }
        val sessions = mock<SigningSessions> { on { signedFor(fixture.app.id, fixture.cycle) } doReturn fixture.signed }
        val core = mock<ru.creditcabinet.integration.domain.CoreBanking> {
            on { disburse(any()) } doReturn ru.creditcabinet.integration.domain.LoanBooked(
                fixture.loan.contractNumber,
                fixture.loan.loanAccount,
                fixture.loan.settlementAccount,
                fixture.loan.disbursedAt,
                fixture.loan.maturityDate,
            )
        }
        val pinned = mock<ru.creditcabinet.product.domain.ProductCatalog>()
        whenever(
            pinned.version("OVERDRAFT", 1),
        ).thenReturn(products.version("OVERDRAFT", 1)).thenThrow(IllegalStateException("Catalog unavailable after booked command"))
        val steps = disbursementPipeline(context, fixture.outbox, core, offerStore, sessions, pinned)
        val id = UUID.randomUUID()
        jdbc.update(
            "insert into pipeline_jobs(id,application_id,step,status,attempts,run_at,created_at,updated_at,locked_until) values(?,?,'DISBURSEMENT','RUNNING',1,now(),now(),now(),now()+interval '5 minutes')",
            id,
            fixture.app.id,
        )
        val job = PipelineJob(id, fixture.app.id, PipelineStep.DISBURSEMENT, 1)
        steps.run(
            job.copy(
                leaseUntil = jdbc.queryForObject("select locked_until from pipeline_jobs where id=?", java.sql.Timestamp::class.java, job.id)!!.toInstant(),
            ),
        )
        assertEquals(ApplicationStatus.DISBURSING, applications.find(fixture.app.id)!!.status)
        assertNull(loans.find(fixture.app.id))
        assertNull(operations.byApplication(fixture.app.id))
        jdbc.update("update pipeline_jobs set status='RUNNING',attempts=1,locked_until=now()+interval '5 minutes' where id=?", id)
        steps.run(
            job.copy(
                leaseUntil = jdbc.queryForObject("select locked_until from pipeline_jobs where id=?", java.sql.Timestamp::class.java, job.id)!!.toInstant(),
            ),
        ) // pending receipt survives another job execution without another core call
        verify(core, times(1)).disburse(any())
        verify(pinned, times(1)).version("OVERDRAFT", 1)
        assertEquals(0, jdbc.queryForObject("select attempts from pipeline_jobs where id=?", Int::class.java, id))
        assertTrue(fixture.outbox.deliverOne())
        jdbc.update("update pipeline_jobs set status='RUNNING',attempts=1,locked_until=now()+interval '5 minutes' where id=?", id)
        steps.run(
            job.copy(
                leaseUntil = jdbc.queryForObject("select locked_until from pipeline_jobs where id=?", java.sql.Timestamp::class.java, job.id)!!.toInstant(),
            ),
        )
        assertEquals(ApplicationStatus.DISBURSED, applications.find(fixture.app.id)!!.status)
        assertNotNull(loans.find(fixture.app.id))
        assertEquals("DONE", jdbc.queryForObject("select status from pipeline_jobs where id=?", String::class.java, id))
        assertEquals(1, operations.byApplication(fixture.app.id)!!.tranches.size)
        verify(core, times(1)).disburse(any())
        verify(pinned, times(1)).version("OVERDRAFT", 1)
    }

    @Test fun `expired core worker cannot invalidate a newer booked opening and receipt`() {
        val fixture = fixture()
        val offer = Offer(
            id = fixture.cycle, applicationId = fixture.app.id, decisionId = UUID.randomUUID(), kind = OfferKind.APPROVED,
            productCode = "OVERDRAFT", amount = fixture.loan.amount, termMonths = 12, rate = fixture.loan.rate,
            monthlyPayment = BigDecimal("100000.00"), totalPayment = BigDecimal("1200000.00"), validUntil = clock.instant().plusSeconds(600),
            status = OfferStatus.ACCEPTED, createdAt = clock.instant(),
        )
        val offerStore = mock<OfferRepository> { on { current(fixture.app.id) } doReturn offer }
        val sessions = mock<SigningSessions> { on { signedFor(fixture.app.id, fixture.cycle) } doReturn fixture.signed }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val core = mock<ru.creditcabinet.integration.domain.CoreBanking>()
        whenever(core.disburse(any())).thenAnswer {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                throw ru.creditcabinet.integration.domain.SourceUnavailableException("CORE", "Late response from expired lease")
            }
            ru.creditcabinet.integration.domain.LoanBooked(
                fixture.loan.contractNumber,
                fixture.loan.loanAccount,
                fixture.loan.settlementAccount,
                fixture.loan.disbursedAt,
                fixture.loan.maturityDate,
            )
        }
        val steps = disbursementPipeline(context, fixture.outbox, core, offerStore, sessions, products)
        val id = UUID.randomUUID()
        val lease = clock.instant().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        jdbc.update(
            "insert into pipeline_jobs(id,application_id,step,status,attempts,run_at,created_at,updated_at,locked_until) values(?,?,'DISBURSEMENT','RUNNING',1,now(),now(),now(),?)",
            id,
            fixture.app.id,
            java.sql.Timestamp.from(lease),
        )
        val oldJob = PipelineJob(id, fixture.app.id, PipelineStep.DISBURSEMENT, 1, lease)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val late = pool.submit { steps.run(oldJob) }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            jdbc.update("update pipeline_jobs set locked_until=? where id=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)), id)
            val next = jobs.claim(clock.instant(), clock.instant().plusSeconds(600), 1000).single { it.id == id }
            steps.run(next)
            val command = command(fixture.app.id)
            release.countDown()
            late.get(20, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(fixture.app.version, applications.find(fixture.app.id)!!.version)
            assertEquals(ApplicationStatus.DISBURSING, applications.find(fixture.app.id)!!.status)
            assertEquals("PENDING", jdbc.queryForObject("select state from servicing_open_outbox where application_id=?", String::class.java, fixture.app.id))
            assertTrue(fixture.outbox.deliverOne())
            val receipt = operations.open(command)
            tx.executeWithoutResult { fixture.outbox.acknowledge(command, receipt) }
            jdbc.update("update pipeline_jobs set run_at=? where id=?", java.sql.Timestamp.from(clock.instant()), id)
            val finalJob = jobs.claim(clock.instant(), clock.instant().plusSeconds(600), 1000).single { it.id == id }
            steps.run(finalJob)
            steps.run(oldJob)
            assertEquals(ApplicationStatus.DISBURSED, applications.find(fixture.app.id)!!.status)
            assertNotNull(loans.find(fixture.app.id))
            assertEquals(2, calls.get()) // one expired request, one successful booking; receipt retries never call core
            assertEquals(1, operations.byApplication(fixture.app.id)!!.tranches.size)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    /** Expected RED on 9e: combine with the Documents owner's fenced generic Worker catch before running. */
    @Test fun `late generic core exception cannot retry or fail a newer running lease`() {
        // Exercise both branches of the generic catch, independently of claim-attempt/business-failure accounting.
        for (maxAttempts in listOf(1, 20)) {
            val fixture = fixture()
            val offer = Offer(
                id = fixture.cycle, applicationId = fixture.app.id, decisionId = UUID.randomUUID(), kind = OfferKind.APPROVED,
                productCode = "OVERDRAFT", amount = fixture.loan.amount, termMonths = 12, rate = fixture.loan.rate,
                monthlyPayment = BigDecimal("100000.00"), totalPayment = BigDecimal("1200000.00"),
                validUntil = clock.instant().plusSeconds(600), status = OfferStatus.ACCEPTED, createdAt = clock.instant(),
            )
            val offerStore = mock<OfferRepository> { on { current(fixture.app.id) } doReturn offer }
            val sessions = mock<SigningSessions> { on { signedFor(fixture.app.id, fixture.cycle) } doReturn fixture.signed }
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val core = mock<ru.creditcabinet.integration.domain.CoreBanking>()
            whenever(core.disburse(any())).thenAnswer {
                entered.countDown()
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                throw IllegalStateException("Late CORE HTTP 400 from a superseded worker")
            }
            val steps = disbursementPipeline(context, fixture.outbox, core, offerStore, sessions, products)
            jobs.enqueue(fixture.app.id, PipelineStep.DISBURSEMENT, clock.instant())
            val id = jdbc.queryForObject("select id from pipeline_jobs where application_id=?", UUID::class.java, fixture.app.id)!!
            val handler = ru.creditcabinet.pipeline.application.PipelineJobHandler { job ->
                // Other completed test fixtures can leave due jobs in this isolated PG; only this fixture calls Core.
                if (job.id == id) steps.run(job) else jobs.complete(job.id)
            }
            val worker =
                ru.creditcabinet.pipeline.infrastructure.PipelineWorker(
                    jobs,
                    handler,
                    ru.creditcabinet.pipeline.application.PipelineSettings(enabled = false, maxAttempts = maxAttempts),
                    clock,
                    context.getBean(ru.creditcabinet.observability.PipelineMetrics::class.java),
                )
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val late = pool.submit<Int> { worker.runDue() }
                assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                jdbc.update("update pipeline_jobs set locked_until=? where id=?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)), id)
                val next = jobs.claim(clock.instant(), clock.instant().plusSeconds(600), 1000).single { it.id == id }
                assertEquals(2, next.attempts)
                assertNotNull(next.leaseUntil)
                val running = jdbc.queryForObject("select to_jsonb(j)::text from pipeline_jobs j where id=?", String::class.java, id)
                assertEquals("RUNNING", jdbc.queryForObject("select status from pipeline_jobs where id=?", String::class.java, id))
                release.countDown()
                assertTrue(late.get(20, java.util.concurrent.TimeUnit.SECONDS) >= 1)
                assertEquals(
                    running,
                    jdbc.queryForObject("select to_jsonb(j)::text from pipeline_jobs j where id=?", String::class.java, id),
                    "A stale generic catch must preserve the newer lease, schedule, error and both counters (maxAttempts=$maxAttempts)",
                )
                assertEquals(ApplicationStatus.DISBURSING, applications.find(fixture.app.id)!!.status)
                assertEquals(fixture.app.version, applications.find(fixture.app.id)!!.version)
                assertNull(loans.find(fixture.app.id))
                assertEquals(0, jdbc.queryForObject("select count(*) from servicing_open_outbox where application_id=?", Int::class.java, fixture.app.id))
                verify(core, times(1)).disburse(any())
            } finally {
                release.countDown()
                pool.shutdownNow()
                check(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                jobs.cancelPending(fixture.app.id)
            }
        }
    }

    @Test fun `late signing code cannot replace the signature bound to the booked cycle`() {
        val fixture = fixture()
        jdbc.update("update applications set status='SIGNING' where id=?", fixture.app.id)
        val application = applications.find(fixture.app.id)!!
        val decision = UUID.randomUUID()
        jdbc.update(
            "insert into decisions(id,application_id,rule_set_version,decided_by_type,outcome,inputs,created_at) values(?,?,1,'SYSTEM','APPROVE','{}'::jsonb,now())",
            decision,
            application.id,
        )
        val offer =
            Offer(
                id = fixture.cycle, applicationId = application.id, decisionId = decision, kind = OfferKind.APPROVED,
                productCode = "OVERDRAFT", amount = 1_000_000, termMonths = 12,
                rate = BigDecimal(
                    "20.00",
                ),
                monthlyPayment = BigDecimal(
                    "100000.00",
                ),
                totalPayment = BigDecimal(
                    "1200000.00",
                ),
                validUntil = clock.instant().plusSeconds(600), status = OfferStatus.ACCEPTED, createdAt = clock.instant(),
            )
        val offerStore = context.getBean(OfferRepository::class.java)
        offerStore.save(offer)
        val contractTexts = context.getBean(ContractTexts::class.java)
        val snapshot = contractTexts.freeze(application, offer, products.version("OVERDRAFT", 1)!!)
        val records = context.getBean(SigningSessions::class.java)
        val initial =
            SigningSessionRecord(
                applicationId = application.id,
                offerId = offer.id,
                externalSessionId = UUID.randomUUID(),
                code = "123456",
                expiresAt = clock.instant().plusSeconds(300),
                createdAt = clock.instant(),
                contractSnapshotId = snapshot.id,
            )
        records.save(initial)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val gateway = mock<ru.creditcabinet.integration.domain.SigningGateway>()
        whenever(gateway.start(any(), any(), any(), any())).thenAnswer {
            entered.countDown()
            check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
            ru.creditcabinet.integration.domain.SigningStarted(UUID.randomUUID(), "654321", clock.instant().plusSeconds(300))
        }
        val signature = UUID.randomUUID()
        whenever(
            gateway.confirm(initial.externalSessionId, "123456"),
        ).thenReturn(ru.creditcabinet.integration.domain.SigningResult.Signed(signature, clock.instant()))
        val service =
            OfferService(
                context.getBean(
                    ru.creditcabinet.application.service.ApplicationService::class.java,
                ),
                transitions, offerStore, records, gateway, jobs, products, contractTexts,
                context.getBean(ru.creditcabinet.application.application.CreditTransactions::class.java), clock,
            )
        val caller = ru.creditcabinet.common.security.TokenClaims(
            application.createdBy,
            ru.creditcabinet.common.security.Roles.CLIENT,
            null,
            null,
            application.company.id,
            true,
        )
        fun authenticate() {
            org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken(caller, null, emptyList())
        }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val late = pool.submit<Boolean> {
                authenticate()
                try {
                    service.requestCode(application.id)
                    false
                } catch (
                    _: ru.creditcabinet.common.exception.ConflictException,
                ) {
                    true
                } finally {
                    org.springframework.security.core.context.SecurityContextHolder.clearContext()
                }
            }
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            authenticate()
            assertEquals(ApplicationStatus.DISBURSING, service.sign(application.id, "123456").status)
            release.countDown()
            assertTrue(late.get(20, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(initial.id, records.latest(application.id)!!.id)
            assertEquals(signature, records.signedFor(application.id, offer.id)!!.signatureId)
            // A legacy unbound unsigned row also cannot shadow the cycle's actual signature.
            records.save(initial.copy(id = UUID.randomUUID(), externalSessionId = UUID.randomUUID(), createdAt = clock.instant().plusSeconds(1)))
            assertNull(records.latest(application.id)!!.signatureId)
            assertEquals(signature, records.signedFor(application.id, offer.id)!!.signatureId)
            assertEquals(ApplicationStatus.DISBURSING, service.sign(application.id, "123456").status)
        } finally {
            release.countDown()
            pool.shutdownNow()
            org.springframework.security.core.context.SecurityContextHolder.clearContext()
        }
    }
}
