package ru.creditcabinet.assistant.controller

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.assistant.application.InvoiceFactSelection
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.limits.LimitWindow
import ru.creditcabinet.common.limits.SharedLimits
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import java.time.Duration
import ru.creditcabinet.assistant.application.InvoiceFactCandidates as FactCandidates

internal data class InvoiceFactCandidates(val amounts: List<String>, val dates: List<String>)

internal data class InvoiceFactChoice(val amountIndex: Int? = null, val dateIndex: Int? = null)

/** Returns selections only to Credit, using the caller's verified agent token on the private network. */
@Hidden
@RestController
@RequestMapping("/internal/v1/assistant/invoice-facts")
internal class InvoiceFactsController(
    private val selection: InvoiceFactSelection,
    private val limits: SharedLimits,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun select(@RequestBody candidates: InvoiceFactCandidates): InvoiceFactChoice {
        val claims = CurrentCaller.requireRole(Roles.AGENT)
        if (limits.enabled) {
            limits.acquire(
                "Слишком много счетов для разбора — попробуйте через несколько минут",
                LimitWindow("invoice-extraction", claims.userId.toString(), 12, Duration.ofMinutes(10)),
            )
        }
        return try {
            selection.choose(FactCandidates(candidates.amounts, candidates.dates)).let { InvoiceFactChoice(it.amountIndex, it.dateIndex) }
        } catch (_: IllegalArgumentException) {
            throw BadRequestException("Некорректные строки счёта")
        }
    }
}
