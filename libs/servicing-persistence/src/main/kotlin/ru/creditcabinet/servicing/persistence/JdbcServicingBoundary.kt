package ru.creditcabinet.servicing.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.servicing.contract.v1.LoanOpenedReceipt
import ru.creditcabinet.servicing.contract.v1.OpenLoanCommand
import ru.creditcabinet.servicing.core.OpeningLedger
import ru.creditcabinet.servicing.core.ServicingTransaction
import ru.creditcabinet.servicing.domain.ServicingConflict
import ru.creditcabinet.servicing.domain.ServicingUnavailable
import java.util.UUID

class JdbcServicingTransaction(
    private val jdbc: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val writer: String,
) : ServicingTransaction {
    init {
        require(writer in setOf("LOCAL", "REMOTE"))
    }
    override fun <T : Any> execute(action: () -> T): T = try {
        requireNotNull(
            tx.execute {
                jdbc.queryForObject("select public.assert_servicing_writer(?)", Boolean::class.java, writer)
                action()
            },
        )
    } catch (e: DuplicateKeyException) {
        throw ServicingConflict("Команда или счёт уже зарегистрированы с другими условиями")
    } catch (e: DataAccessException) {
        throw ServicingUnavailable("Хранилище текущего баланса недоступно")
    }
}

class JdbcOpeningLedger(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) : OpeningLedger {
    override fun lockApplication(applicationId: UUID) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 731))", Any::class.java, applicationId.toString())
    }
    override fun findOpening(applicationId: UUID): Pair<OpenLoanCommand, LoanOpenedReceipt>? = jdbc.query(
        "select command_json,receipt_json from servicing_opened_commands where application_id=?",
        { rs, _ -> mapper.readValue(rs.getString(1), OpenLoanCommand::class.java) to mapper.readValue(rs.getString(2), LoanOpenedReceipt::class.java) },
        applicationId,
    ).singleOrNull()
    override fun save(command: OpenLoanCommand, receipt: LoanOpenedReceipt) {
        jdbc.update(
            "insert into servicing_opened_commands(application_id,command_id,cycle_id,account_id,payload_sha256,command_json,receipt_json) values(?,?,?,?,?,?::jsonb,?::jsonb)",
            command.applicationId,
            command.commandId,
            command.cycleId,
            receipt.accountId,
            receipt.payloadSha256,
            mapper.writeValueAsString(command),
            mapper.writeValueAsString(receipt),
        )
    }
}
