package ru.creditcabinet.servicing.paymentreserve.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.creditcabinet.servicing.paymentreserve.domain.ConfirmedReserveDraw
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveInputs
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReservePlan
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveStore
import ru.creditcabinet.servicing.paymentreserve.domain.ReceiptEvent
import ru.creditcabinet.servicing.paymentreserve.domain.ReceiptStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcPaymentReserveStore(private val jdbc: JdbcTemplate) : PaymentReserveStore {
    override fun find(loanId: UUID): PaymentReservePlan? =
        jdbc.query(
            """
            select loan_id, version, opening_balance, expected_receipt_amount, payable_amount, target_buffer,
                   control_date, payment_date, receipt_status, received_amount,
                   confirmed_version, drawn_tranche_id, drawn_amount, confirmed_by, confirmed_at
              from payment_reserves
             where loan_id = ?
            """.trimIndent(),
            { rs, _ -> plan(rs) },
            loanId,
        ).singleOrNull()

    override fun save(plan: PaymentReservePlan, actorId: UUID) {
        val inputs = plan.inputs
        val draw = plan.confirmedDraw
        jdbc.update(
            """
            insert into payment_reserves (
                loan_id, version, opening_balance, expected_receipt_amount, payable_amount, target_buffer,
                control_date, payment_date, receipt_status, received_amount,
                confirmed_version, drawn_tranche_id, drawn_amount, confirmed_by, confirmed_at, updated_by
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (loan_id) do update set
                version = excluded.version,
                opening_balance = excluded.opening_balance,
                expected_receipt_amount = excluded.expected_receipt_amount,
                payable_amount = excluded.payable_amount,
                target_buffer = excluded.target_buffer,
                control_date = excluded.control_date,
                payment_date = excluded.payment_date,
                receipt_status = excluded.receipt_status,
                received_amount = excluded.received_amount,
                confirmed_version = excluded.confirmed_version,
                drawn_tranche_id = excluded.drawn_tranche_id,
                drawn_amount = excluded.drawn_amount,
                confirmed_by = excluded.confirmed_by,
                confirmed_at = excluded.confirmed_at,
                updated_by = excluded.updated_by,
                updated_at = now()
            """.trimIndent(),
            plan.loanId,
            plan.version,
            inputs.openingBalance,
            inputs.expectedReceiptAmount,
            inputs.payableAmount,
            inputs.targetBuffer,
            inputs.controlDate,
            inputs.paymentDate,
            plan.receiptStatus.name,
            plan.receivedAmount,
            draw?.version,
            draw?.trancheId,
            draw?.amount,
            draw?.confirmedBy,
            draw?.confirmedAt?.let(Timestamp::from),
            actorId,
        )
    }

    override fun findEvent(loanId: UUID, eventId: String): ReceiptEvent? =
        jdbc.query(
            """
            select event_id, receipt_status, reported_amount
              from payment_reserve_receipt_events
             where loan_id = ? and event_id = ?
            """.trimIndent(),
            { rs, _ ->
                ReceiptEvent(
                    eventId = rs.getString("event_id"),
                    status = ReceiptStatus.valueOf(rs.getString("receipt_status")),
                    receivedAmount = rs.getObject("reported_amount", Long::class.javaObjectType),
                )
            },
            loanId,
            eventId,
        ).singleOrNull()

    override fun saveEvent(loanId: UUID, event: ReceiptEvent, planVersion: Long, actorId: UUID) {
        jdbc.update(
            """
            insert into payment_reserve_receipt_events (loan_id, event_id, receipt_status, reported_amount, plan_version, actor_id)
            values (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            loanId,
            event.eventId,
            event.status.name,
            event.receivedAmount,
            planVersion,
            actorId,
        )
    }

    private fun plan(rs: ResultSet): PaymentReservePlan {
        val draw = rs.getObject("drawn_tranche_id", UUID::class.java)?.let { trancheId ->
            ConfirmedReserveDraw(
                version = rs.getLong("confirmed_version"),
                trancheId = trancheId,
                amount = rs.getLong("drawn_amount"),
                confirmedBy = rs.getObject("confirmed_by", UUID::class.java),
                confirmedAt = rs.getTimestamp("confirmed_at").toInstant(),
            )
        }
        return PaymentReservePlan(
            loanId = rs.getObject("loan_id", UUID::class.java),
            version = rs.getLong("version"),
            inputs = PaymentReserveInputs(
                openingBalance = rs.getLong("opening_balance"),
                expectedReceiptAmount = rs.getLong("expected_receipt_amount"),
                payableAmount = rs.getLong("payable_amount"),
                targetBuffer = rs.getLong("target_buffer"),
                controlDate = rs.getObject("control_date", LocalDate::class.java),
                paymentDate = rs.getObject("payment_date", LocalDate::class.java),
            ),
            receiptStatus = ReceiptStatus.valueOf(rs.getString("receipt_status")),
            receivedAmount = rs.getLong("received_amount"),
            confirmedDraw = draw,
        )
    }
}
