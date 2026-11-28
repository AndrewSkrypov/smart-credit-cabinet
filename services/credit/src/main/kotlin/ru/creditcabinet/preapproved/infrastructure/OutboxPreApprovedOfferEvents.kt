package ru.creditcabinet.preapproved.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import ru.creditcabinet.common.outbox.DurableOutbox
import ru.creditcabinet.preapproved.application.PreApprovedOfferEvents
import ru.creditcabinet.preapproved.domain.PreApprovedOffer

@Component
class OutboxPreApprovedOfferEvents(
    private val outbox: ObjectProvider<DurableOutbox>,
    private val mapper: ObjectMapper,
) : PreApprovedOfferEvents {
    override fun generated(offer: PreApprovedOffer) {
        val delivery = outbox.ifAvailable ?: return
        val payload = mapper.writeValueAsString(
            mapOf(
                "type" to "PRE_APPROVED_OFFER",
                "companyId" to offer.companyId.toString(),
                "offerId" to offer.id.toString(),
                "productCode" to offer.productCode,
                "maxAmount" to offer.maxAmount,
                "rate" to offer.rate,
            ),
        )
        delivery.enqueue("notification", "preapproved:${offer.id}", payload)
    }
}
