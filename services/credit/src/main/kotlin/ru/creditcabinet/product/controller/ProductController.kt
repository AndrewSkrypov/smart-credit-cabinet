package ru.creditcabinet.product.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.product.domain.Calculation
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductMatch
import ru.creditcabinet.product.domain.ProductMatcher

data class PurposeResponse(val code: FinancingPurpose, val title: String)

data class MatchRequest(
    val purpose: FinancingPurpose,
    @field:Positive(message = "Сумма должна быть больше нуля")
    val amount: Long,
    @field:Min(1, message = "Срок — от 1 месяца")
    @field:Max(120, message = "Срок — не больше 120 месяцев")
    val termMonths: Int,
)

/** The calculation without the month-by-month schedule: the wizard shows only the totals. */
data class MatchResponse(
    val product: Product,
    val fits: Boolean,
    val reasons: List<String>,
    val adjustments: List<String>,
    val termMonths: Int,
    val monthlyPayment: java.math.BigDecimal?,
    val overpayment: java.math.BigDecimal?,
) {
    companion object {
        fun of(match: ProductMatch) =
            MatchResponse(
                match.product,
                match.fits,
                match.reasons,
                match.adjustments,
                match.termMonths,
                match.calculation?.monthlyPayment,
                match.calculation?.overpayment,
            )
    }
}

@Tag(name = "Кредитные продукты")
@RestController
@RequestMapping("/api/v1/products")
class ProductController(private val catalog: ProductCatalog) {
    @Operation(summary = "Каталог кредитных продуктов для подбора финансирования")
    @GetMapping
    fun list(): List<Product> = catalog.all()

    @Operation(summary = "Цели финансирования для мастера подбора")
    @GetMapping("/purposes")
    fun purposes(): List<PurposeResponse> = FinancingPurpose.entries.map { PurposeResponse(it, it.title) }

    @Operation(summary = "Условия продукта по его коду")
    @GetMapping("/{code}")
    fun get(@PathVariable code: String): Product = catalog.find(code) ?: throw NotFoundException("Продукт не найден")

    @Operation(summary = "Подбор продуктов под цель, сумму и срок")
    @PostMapping("/match")
    fun match(@Valid @RequestBody request: MatchRequest): List<MatchResponse> =
        ProductMatcher.match(catalog.all(), request.purpose, request.amount, request.termMonths).map(MatchResponse::of)

    @Operation(summary = "Расчёт платежа и графика по базовой ставке продукта")
    @GetMapping("/{code}/calculation")
    fun calculation(@PathVariable code: String, @RequestParam amount: Long, @RequestParam termMonths: Int): Calculation {
        val product = get(code)
        if (!product.amountFits(
                amount,
            )
        ) {
            throw BadRequestException("Сумма для продукта — от ${ProductMatcher.rubles(product.minAmount)} до ${ProductMatcher.rubles(product.maxAmount)}")
        }
        if (!product.termFits(termMonths)) throw BadRequestException("Срок для продукта — от ${product.minTermMonths} до ${product.maxTermMonths} мес.")
        return LoanMath.calculate(amount, product.baseRate, termMonths, product.revolving)
    }
}
