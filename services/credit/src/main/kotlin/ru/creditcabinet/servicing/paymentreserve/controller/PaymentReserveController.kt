package ru.creditcabinet.servicing.paymentreserve.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.servicing.paymentreserve.application.PaymentReserveService
import ru.creditcabinet.servicing.paymentreserve.domain.AgentPaymentReserveSummary
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveInputs
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveQuote
import ru.creditcabinet.servicing.paymentreserve.domain.ReceiptEvent
import java.util.UUID

data class ConfirmReserveDrawRequest(val expectedVersion: Long)

@Tag(name = "Резерв к дате платежа на синтетической линии")
@RestController
class PaymentReserveController(private val reserves: PaymentReserveService) {
    @Operation(summary = "Посмотреть план и текущий расчёт резерва по своей линии")
    @GetMapping("/api/v1/loans/{loanId}/payment-reserve")
    fun get(@PathVariable loanId: UUID): ResponseEntity<PaymentReserveQuote> =
        reserves.get(loanId)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()

    @Operation(summary = "Сохранить обязательство, ожидаемое поступление и желаемый запас")
    @PutMapping("/api/v1/loans/{loanId}/payment-reserve")
    fun put(@PathVariable loanId: UUID, @RequestBody request: PaymentReserveInputs): PaymentReserveQuote =
        reserves.put(loanId, request)

    @Operation(summary = "Зафиксировать синтетическое поступление или задержку и пересчитать резерв")
    @PostMapping("/api/v1/loans/{loanId}/payment-reserve/receipt-event")
    fun receipt(@PathVariable loanId: UUID, @RequestBody event: ReceiptEvent): PaymentReserveQuote =
        reserves.receipt(loanId, event)

    @Operation(summary = "Клиент подтверждает один транш по актуальной версии расчёта")
    @PostMapping("/api/v1/loans/{loanId}/payment-reserve/confirm-draw")
    fun confirm(@PathVariable loanId: UUID, @RequestBody request: ConfirmReserveDrawRequest): PaymentReserveQuote =
        reserves.confirm(loanId, request.expectedVersion)

    @Operation(summary = "Агент видит этап и следующее действие по резерву в рамках мандата")
    @GetMapping("/api/v1/agent/applications/{id}/payment-reserve")
    fun agentSummary(@PathVariable id: UUID): ResponseEntity<AgentPaymentReserveSummary> =
        reserves.agentSummary(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
}
