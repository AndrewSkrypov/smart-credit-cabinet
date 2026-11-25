package ru.creditcabinet.servicing.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStatus
import ru.creditcabinet.servicing.domain.LoanAccountStore
import ru.creditcabinet.servicing.domain.LoanPayment
import ru.creditcabinet.servicing.domain.LoanPaymentStatus
import ru.creditcabinet.servicing.domain.LoanTranche
import java.sql.Date
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class LoanServicingJdbcStore(
    private val jdbc: JdbcTemplate,
) : LoanAccountStore {
    override fun existsByApplicationId(applicationId: UUID): Boolean =
        (jdbc.queryForObject("select count(*) from loan_accounts where application_id = ?", Int::class.java, applicationId) ?: 0) > 0

    override fun findByApplicationId(applicationId: UUID): LoanAccount? {
        val row = jdbc.query(
            """
            select id, application_id, company_id, contract_number, approved_limit, disbursed_amount,
                   outstanding_principal, rate, term_months, revolving, status, disbursed_at, maturity_date, repaid_at
              from loan_accounts
             where application_id = ?
            """.trimIndent(),
            { rs, _ -> mapAccount(rs) },
            applicationId,
        ).firstOrNull() ?: return null
        return hydrate(row)
    }

    override fun findByIdForUpdate(id: UUID): LoanAccount? {
        val row = jdbc.query(
            """
            select id, application_id, company_id, contract_number, approved_limit, disbursed_amount,
                   outstanding_principal, rate, term_months, revolving, status, disbursed_at, maturity_date, repaid_at
              from loan_accounts
             where id = ?
               for update
            """.trimIndent(),
            { rs, _ -> mapAccount(rs) },
            id,
        ).firstOrNull() ?: return null
        return hydrate(row)
    }

    override fun create(account: LoanAccount) {
        jdbc.update(
            """
            insert into loan_accounts (
                id, application_id, company_id, contract_number, approved_limit, disbursed_amount,
                outstanding_principal, rate, term_months, revolving, status, disbursed_at, maturity_date, repaid_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            account.id,
            account.applicationId,
            account.companyId,
            account.contractNumber,
            account.approvedLimit,
            account.disbursedAmount,
            account.outstandingPrincipal,
            account.rate,
            account.termMonths,
            account.revolving,
            account.status.name,
            Timestamp.from(account.disbursedAt),
            Date.valueOf(account.maturityDate),
            account.repaidAt?.let(Timestamp::from),
        )
        account.tranches.forEach(::insertTranche)
        insertPayments(account.payments)
    }

    override fun saveState(account: LoanAccount, newTranche: LoanTranche?) {
        jdbc.update(
            """
            update loan_accounts
               set disbursed_amount = ?,
                   outstanding_principal = ?,
                   status = ?,
                   repaid_at = ?,
                   updated_at = now()
             where id = ?
            """.trimIndent(),
            account.disbursedAmount,
            account.outstandingPrincipal,
            account.status.name,
            account.repaidAt?.let(Timestamp::from),
            account.id,
        )
        if (newTranche != null) {
            insertTranche(newTranche)
        }
        jdbc.update("delete from loan_payments where loan_account_id = ?", account.id)
        insertPayments(account.payments)
    }

    private fun hydrate(account: LoanAccount): LoanAccount {
        val tranches = jdbc.query(
            """
            select id, loan_account_id, tranche_number, amount, purpose, disbursed_at
              from loan_tranches
             where loan_account_id = ?
             order by tranche_number
            """.trimIndent(),
            { rs, _ ->
                LoanTranche(
                    id = rs.getObject("id", UUID::class.java),
                    loanAccountId = rs.getObject("loan_account_id", UUID::class.java),
                    trancheNumber = rs.getInt("tranche_number"),
                    amount = rs.getLong("amount"),
                    purpose = rs.getString("purpose"),
                    disbursedAt = rs.getTimestamp("disbursed_at").toInstant(),
                )
            },
            account.id,
        )
        val payments = jdbc.query(
            """
            select id, loan_account_id, payment_number, due_date, payment_amount, principal_amount,
                   interest_amount, remaining_balance, status, paid_at
              from loan_payments
             where loan_account_id = ?
             order by payment_number
            """.trimIndent(),
            { rs, _ ->
                LoanPayment(
                    id = rs.getObject("id", UUID::class.java),
                    loanAccountId = rs.getObject("loan_account_id", UUID::class.java),
                    paymentNumber = rs.getInt("payment_number"),
                    dueDate = rs.getDate("due_date").toLocalDate(),
                    paymentAmount = rs.getBigDecimal("payment_amount"),
                    principalAmount = rs.getBigDecimal("principal_amount"),
                    interestAmount = rs.getBigDecimal("interest_amount"),
                    remainingBalance = rs.getBigDecimal("remaining_balance"),
                    status = LoanPaymentStatus.valueOf(rs.getString("status")),
                    paidAt = rs.getTimestamp("paid_at")?.toInstant(),
                )
            },
            account.id,
        )
        return account.copy(tranches = tranches, payments = payments)
    }

    private fun insertTranche(tranche: LoanTranche) {
        jdbc.update(
            """
            insert into loan_tranches (id, loan_account_id, tranche_number, amount, purpose, disbursed_at)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tranche.id,
            tranche.loanAccountId,
            tranche.trancheNumber,
            tranche.amount,
            tranche.purpose,
            Timestamp.from(tranche.disbursedAt),
        )
    }

    private fun insertPayments(payments: List<LoanPayment>) {
        if (payments.isEmpty()) return
        jdbc.batchUpdate(
            """
            insert into loan_payments (
                id, loan_account_id, payment_number, due_date, payment_amount,
                principal_amount, interest_amount, remaining_balance, status, paid_at
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            payments,
            payments.size,
        ) { ps, payment ->
            ps.setObject(1, payment.id)
            ps.setObject(2, payment.loanAccountId)
            ps.setInt(3, payment.paymentNumber)
            ps.setDate(4, Date.valueOf(payment.dueDate))
            ps.setBigDecimal(5, payment.paymentAmount)
            ps.setBigDecimal(6, payment.principalAmount)
            ps.setBigDecimal(7, payment.interestAmount)
            ps.setBigDecimal(8, payment.remainingBalance)
            ps.setString(9, payment.status.name)
            ps.setTimestamp(10, payment.paidAt?.let(Timestamp::from))
        }
    }

    private fun mapAccount(rs: ResultSet): LoanAccount =
        LoanAccount(
            id = rs.getObject("id", UUID::class.java),
            applicationId = rs.getObject("application_id", UUID::class.java),
            companyId = rs.getObject("company_id", UUID::class.java),
            contractNumber = rs.getString("contract_number"),
            approvedLimit = rs.getLong("approved_limit"),
            disbursedAmount = rs.getLong("disbursed_amount"),
            outstandingPrincipal = rs.getBigDecimal("outstanding_principal"),
            rate = rs.getBigDecimal("rate"),
            termMonths = rs.getInt("term_months"),
            revolving = rs.getBoolean("revolving"),
            status = LoanAccountStatus.valueOf(rs.getString("status")),
            disbursedAt = rs.getTimestamp("disbursed_at").toInstant(),
            maturityDate = rs.getDate("maturity_date").toLocalDate(),
            repaidAt = rs.getTimestamp("repaid_at")?.toInstant(),
        )
}
