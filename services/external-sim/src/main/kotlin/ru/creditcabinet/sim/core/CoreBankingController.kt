package ru.creditcabinet.sim.core

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.sim.control.Faults
import ru.creditcabinet.sim.dataset.Source
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class LoanRequest(
    /** The application the loan comes from: one application, one loan, however often this is repeated. */
    val applicationId: UUID,
    @field:NotBlank val companyInn: String,
    @field:Positive val amount: Long,
    @field:Min(1) @field:Max(120) val termMonths: Int,
    @field:Positive val annualRate: BigDecimal,
    val revolving: Boolean,
    /** The signature that authorises the loan. */
    val signatureId: UUID,
)

data class LoanRecord(
    val applicationId: UUID,
    val contractNumber: String,
    val loanAccount: String,
    val settlementAccount: String,
    val amount: Long,
    val termMonths: Int,
    val annualRate: BigDecimal,
    val revolving: Boolean,
    val disbursedAt: Instant,
    val maturityDate: LocalDate,
)

@Tag(name = "АБС: открытие кредита и выдача (симулятор)")
@RestController
@RequestMapping("/ext/core/v1")
class CoreBankingController(private val faults: Faults) {
    // Kept in memory: the cabinet stores the contract number it gets back, the simulator does not need to.
    private val loans = ConcurrentHashMap<UUID, LoanRecord>()
    private val sequence = AtomicLong(1000)

    @Operation(summary = "Открыть ссудный счёт и зачислить средства; повтор по той же заявке возвращает тот же кредит")
    @PostMapping("/loans")
    fun disburse(@Valid @RequestBody request: LoanRequest): LoanRecord {
        faults.enter(Source.CORE)
        return loans.computeIfAbsent(request.applicationId) {
            val number = sequence.incrementAndGet()
            val now = Instant.now()
            LoanRecord(
                applicationId = request.applicationId,
                contractNumber = "КД-${LocalDate.now().year}-%06d".format(number),
                loanAccount = "45206810" + "%012d".format(number),
                settlementAccount = "40702810" + request.companyInn.padStart(12, '0'),
                amount = request.amount,
                termMonths = request.termMonths,
                annualRate = request.annualRate,
                revolving = request.revolving,
                disbursedAt = now,
                maturityDate = LocalDate.now().plusMonths(request.termMonths.toLong()),
            )
        }
    }

    @GetMapping("/loans/{applicationId}")
    fun get(@PathVariable applicationId: UUID): LoanRecord = loans[applicationId] ?: throw NotFoundException("Кредит по заявке не найден")
}
