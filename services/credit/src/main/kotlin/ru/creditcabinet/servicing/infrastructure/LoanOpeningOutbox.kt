@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.offer.application.ContractTextStore
import ru.creditcabinet.servicing.application.LoanOpening
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.core.OpeningFingerprint
import ru.creditcabinet.servicing.domain.ServicingConflict
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

@Component
class LoanOpeningOutbox(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val snapshots: ContractTextStore,
    private val signing: ru.creditcabinet.offer.SigningSessions,
    private val operations: ServicingOperations,
    private val localOpening: ru.creditcabinet.servicing.application.LoanServicingService,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    @Value("\${cabinet.servicing.mode:local}") private val mode: String,
    @Value("\${cabinet.servicing.worker-enabled:true}") private val workerEnabled: Boolean = true,
) : LoanOpening {
    override fun lockExecution(job: ru.creditcabinet.application.domain.PipelineJob): Boolean {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        if (jdbc.query("select id from applications where id=? for update", { _, _ -> true }, job.applicationId).isEmpty()) return false
        return jdbc.query(
            "select attempts,locked_until from pipeline_jobs where id=? and application_id=? and step='DISBURSEMENT' and status='RUNNING' for update",
            { row, _ ->
                val lease = row.getTimestamp("locked_until")?.toInstant()
                row.getInt("attempts") == job.attempts &&
                    lease != null &&
                    lease > clock.instant() &&
                    job.leaseUntil != null &&
                    lease == job.leaseUntil
            },
            job.id,
            job.applicationId,
        ).singleOrNull() == true
    }
    override fun bookedLoan(
        applicationId: UUID,
    ): Loan? = jdbc.query("select booked_loan_json from servicing_open_outbox where application_id=?", { rs, _ ->
        mapper.readValue(rs.getString(1), Loan::class.java)
    }, applicationId).singleOrNull()
    override fun requireSignedSnapshot(applicationId: UUID, cycleId: UUID) {
        if (mode == "local") return
        val snapshot = snapshots.find(cycleId) ?: throw ServicingConflict("Нет неизменяемого снимка подписанного договора; требуется восстановление условий")
        val session = signing.signedFor(applicationId, cycleId)
        if (snapshot.applicationId != applicationId ||
            snapshot.offerId != cycleId ||
            session == null ||
            session.offerId != cycleId ||
            session.signatureId == null ||
            session.contractSnapshotId != snapshot.id ||
            ru.creditcabinet.offer.application.ContractTexts.sha256(snapshot.text) != snapshot.sha256
        ) {
            throw ServicingConflict("Подпись и неизменяемый снимок не относятся к циклу заявки")
        }
    }
    override fun prepareOrConfirmed(application: LoanApplication, loan: Loan, cycleId: UUID): Boolean {
        check(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
        if (mode == "local") {
            localOpening.openOnDisbursement(application, loan)
            return true
        }
        // Application row locking also serializes absent outbox rows and competing leased jobs.
        val current = jdbc.queryForMap("select version,status from applications where id=? for update", application.id)
        if ((current["version"] as Number).toLong() != application.version || current["status"] != "DISBURSING") {
            throw ServicingConflict("Заявка изменилась после начала выдачи")
        }
        val existing = jdbc.query(
            "select command_json,receipt_json,state from servicing_open_outbox where application_id=?",
            { rs, _ ->
                Triple(mapper.readValue(rs.getString(1), OpenLoanCommand::class.java), rs.getString(2), rs.getString(3))
            },
            application.id,
        ).singleOrNull()
        if (existing != null) {
            if (existing.first.cycleId != cycleId ||
                existing.first.applicationVersion != application.version ||
                existing.first.contractNumber != loan.contractNumber ||
                existing.first.approvedLimit != loan.amount ||
                existing.first.termMonths != loan.termMonths ||
                existing.first.rate.compareTo(loan.rate) != 0 ||
                existing.first.revolving != loan.revolving ||
                existing.first.disbursedAt != loan.disbursedAt ||
                existing.first.maturityDate != loan.maturityDate ||
                existing.first.companyId != application.company.id
            ) {
                throw ServicingConflict("Условия или цикл выдачи изменились после фиксации команды")
            }
            if (existing.third == "CONFLICT") throw ServicingConflict("Открытие счёта требует сверки конфликтующей команды")
            if (existing.third != "CONFIRMED") return false
            validate(existing.first, mapper.readValue(requireNotNull(existing.second), LoanOpenedReceipt::class.java))
            return true
        }
        val snapshot = snapshots.find(cycleId) ?: throw ServicingConflict("Нет неизменяемого снимка подписанного договора; требуется восстановление условий")
        require(snapshot.applicationId == application.id && snapshot.offerId == cycleId)
        val command = OpenLoanCommand(
            UUID.nameUUIDFromBytes("servicing-open-v1:${application.id}:$cycleId".toByteArray()),
            application.id, cycleId, requireNotNull(application.version), application.company.id, snapshot.id, snapshot.sha256,
            loan.contractNumber, loan.amount, loan.termMonths, loan.rate, loan.revolving, loan.disbursedAt, loan.maturityDate,
        )
        jdbc.update(
            "insert into servicing_open_outbox(command_id,application_id,cycle_id,application_version,payload_sha256,command_json,booked_loan_json,available_at,created_at) values(?,?,?,?,?,?::jsonb,?::jsonb,?,?)",
            command.commandId, command.applicationId, command.cycleId, command.applicationVersion,
            OpeningFingerprint.of(
                command,
            ),
            mapper.writeValueAsString(command), mapper.writeValueAsString(loan), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()),
        )
        return false
    }
    override fun awaitReceipt(jobId: UUID, until: java.time.Instant) {
        jdbc.update(
            "update pipeline_jobs set status='PENDING',run_at=?,locked_until=null,attempts=greatest(attempts-1,0),last_error=null,updated_at=now() where id=? and status='RUNNING'",
            Timestamp.from(until),
            jobId,
        )
    }

    /** Separate delivery loop; no notification worker or business transaction waits on the network. */
    @Scheduled(fixedDelayString = "\${cabinet.servicing.outbox-poll:PT1S}", scheduler = "servicingOpeningDeliveryScheduler")
    fun poll() {
        if (mode == "remote" && workerEnabled) deliverOne()
    }
    fun deliverOne(): Boolean {
        val command = tx.execute {
            val selected =
                jdbc.query(
                    "select command_json from servicing_open_outbox where state='PENDING' and available_at<=? order by created_at limit 1 for update skip locked",
                    { rs, _ ->
                        mapper.readValue(rs.getString(1), OpenLoanCommand::class.java)
                    },
                    Timestamp.from(clock.instant()),
                ).singleOrNull() ?: return@execute null
            jdbc.update(
                "update servicing_open_outbox set available_at=?,attempts=attempts+1 where command_id=?",
                Timestamp.from(clock.instant().plusSeconds(30)),
                selected.commandId,
            )
            selected
        } ?: return false
        try {
            val receipt = operations.open(command)
            tx.executeWithoutResult { acknowledge(command, receipt) }
        } catch (e: Exception) {
            val retryable = e is ru.creditcabinet.servicing.domain.ServicingUnavailable || e is org.springframework.dao.DataAccessException
            val errorCode = when (e) {
                is ServicingConflict -> "OPENING_CONFLICT"
                is ru.creditcabinet.common.exception.ForbiddenException, is ru.creditcabinet.common.exception.UnauthorizedException -> "PEER_AUTHORIZATION"
                is IllegalArgumentException -> "INVALID_RESPONSE"
                else -> if (retryable) "OWNER_UNAVAILABLE" else "DELIVERY_ERROR"
            }
            tx.executeWithoutResult {
                jdbc.update(
                    "update servicing_open_outbox set state=?,last_error=?,available_at=? where command_id=? and state='PENDING'",
                    if (retryable) "PENDING" else "CONFLICT",
                    errorCode,
                    Timestamp.from(clock.instant().plusSeconds(10)),
                    command.commandId,
                )
            }
        }
        return true
    }
    internal fun acknowledge(command: OpenLoanCommand, receipt: LoanOpenedReceipt) {
        validate(command, receipt)
        val changed = jdbc.update(
            "update servicing_open_outbox set state='CONFIRMED',receipt_json=?::jsonb,confirmed_at=?,last_error=null where command_id=? and cycle_id=? and application_version=? and payload_sha256=? and state='PENDING'",
            mapper.writeValueAsString(receipt),
            Timestamp.from(clock.instant()),
            command.commandId,
            receipt.cycleId,
            receipt.applicationVersion,
            receipt.payloadSha256,
        )
        if (changed == 0) {
            val prior = jdbc.query("select receipt_json from servicing_open_outbox where command_id=? and state='CONFIRMED'", { rs, _ ->
                rs.getString(1)
            }, command.commandId).singleOrNull()
            if (prior == null ||
                mapper.readValue(prior, LoanOpenedReceipt::class.java) != receipt
            ) {
                throw ServicingConflict("Неожиданное или устаревшее подтверждение открытия")
            }
        }
    }
    private fun validate(command: OpenLoanCommand, receipt: LoanOpenedReceipt) {
        if (receipt.schemaVersion != 1 ||
            receipt.commandId != command.commandId ||
            receipt.applicationId != command.applicationId ||
            receipt.cycleId != command.cycleId ||
            receipt.applicationVersion != command.applicationVersion ||
            receipt.payloadSha256 != OpeningFingerprint.of(command)
        ) {
            throw ServicingConflict("Подтверждение не соответствует неизменяемой команде открытия")
        }
    }
}
