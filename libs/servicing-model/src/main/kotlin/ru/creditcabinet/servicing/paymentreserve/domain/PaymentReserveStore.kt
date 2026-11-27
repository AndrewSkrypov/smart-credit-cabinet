package ru.creditcabinet.servicing.paymentreserve.domain

import java.util.UUID

/**
 * Every write and event replay runs under the corresponding loan account row lock.
 * This also serializes the plan against the ordinary servicing routes.
 */
interface PaymentReserveStore {
    fun find(loanId: UUID): PaymentReservePlan?
    fun save(plan: PaymentReservePlan, actorId: UUID)
    fun findEvent(loanId: UUID, eventId: String): ReceiptEvent?
    fun saveEvent(loanId: UUID, event: ReceiptEvent, planVersion: Long, actorId: UUID)
}
