package ru.creditcabinet.servicing.infrastructure

import org.springframework.context.ApplicationContext
import ru.creditcabinet.integration.CoreBanking
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.offer.SigningSessions
import ru.creditcabinet.pipeline.application.PipelineJobHandler
import ru.creditcabinet.pipeline.application.PipelineSteps
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.servicing.application.LoanOpening

/** Resolve unrelated pipeline dependencies from the actual context, including the R2 Assessment ports. */
internal fun disbursementPipeline(
    context: ApplicationContext,
    opening: LoanOpening,
    core: CoreBanking,
    offers: OfferRepository,
    signing: SigningSessions,
    pinnedProducts: ProductCatalog,
): PipelineJobHandler {
    val overrides = mapOf<Class<*>, Any>(
        LoanOpening::class.java to opening,
        CoreBanking::class.java to core,
        OfferRepository::class.java to offers,
        SigningSessions::class.java to signing,
        ProductCatalog::class.java to pinnedProducts,
    )
    val constructor = PipelineSteps::class.java.constructors.filterNot { it.isSynthetic }.maxBy { it.parameterCount }
    val arguments = constructor.parameterTypes.map { type -> overrides[type] ?: context.getBean(type) }.toTypedArray()
    return constructor.newInstance(*arguments) as PipelineJobHandler
}
