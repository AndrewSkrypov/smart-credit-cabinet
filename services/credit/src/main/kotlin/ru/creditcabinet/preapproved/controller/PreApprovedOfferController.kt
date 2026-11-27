package ru.creditcabinet.preapproved.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.preapproved.application.PreApprovedOfferService
import ru.creditcabinet.preapproved.domain.PreApprovedOffer
import ru.creditcabinet.preapproved.domain.PreApprovedOfferStatus
import ru.creditcabinet.product.domain.ProductCatalog
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class PreApprovedOfferView(
    val id: UUID,
    val companyId: UUID,
    val productCode: String,
    val productName: String,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    val rate: BigDecimal,
    val validUntil: Instant,
    val status: PreApprovedOfferStatus,
)

data class PreApprovedApplicationView(
    val id: UUID,
    val number: Long?,
    val status: ApplicationStatus,
    val productCode: String,
    val amount: Long,
    val termMonths: Int,
    val preApprovedOfferId: UUID?,
) {
    companion object {
        fun of(application: LoanApplication) =
            PreApprovedApplicationView(
                id = application.id,
                number = application.number,
                status = application.status,
                productCode = application.terms.productCode,
                amount = application.terms.amount,
                termMonths = application.terms.termMonths,
                preApprovedOfferId = application.preApprovedOfferId,
            )
    }
}

@Tag(name = "Предодобренные предложения")
@RestController
@RequestMapping("/api/v1/pre-approved-offers")
class PreApprovedOfferController(
    private val service: PreApprovedOfferService,
    private val products: ProductCatalog,
) {
    @Operation(summary = "Активные предодобренные предложения компании клиента")
    @GetMapping
    fun list(): List<PreApprovedOfferView> {
        val (_, companyId) = CurrentCaller.requireClient()
        val catalog = products.all().associateBy { it.code }
        return service.listForCompany(companyId).map { offer -> offer.toView(catalog[offer.productCode]?.name ?: offer.productCode) }
    }

    @Operation(summary = "Оформить черновик заявки по предодобренному предложению")
    @PostMapping("/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    fun apply(@PathVariable id: UUID): PreApprovedApplicationView =
        PreApprovedApplicationView.of(service.applyForClient(id))

    private fun PreApprovedOffer.toView(productName: String) =
        PreApprovedOfferView(
            id = id,
            companyId = companyId,
            productCode = productCode,
            productName = productName,
            maxAmount = maxAmount,
            minTermMonths = minTermMonths,
            maxTermMonths = maxTermMonths,
            rate = rate,
            validUntil = validUntil,
            status = status,
        )
}
