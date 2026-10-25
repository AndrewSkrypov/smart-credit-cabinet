package ru.creditcabinet.advice

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.advice.domain.AdviceKind
import ru.creditcabinet.advice.domain.P
import ru.creditcabinet.advice.domain.UnderwritingAdviceModel
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.service.notFound
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.CreditPolicy
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.assessment.domain.EnrichmentStore
import ru.creditcabinet.assessment.domain.Rating
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.RuleSets
import ru.creditcabinet.assessment.domain.Scorecard
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.forApplication
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class AdviceItemView(val code: String, val kind: AdviceKind, val title: String, val text: String, val because: List<String>)

/** What the expert system advises on one application, with the chain of statements behind each item. */
data class AdviceView(
    val applicationId: UUID,
    val engine: String,
    val modelVersion: Int,
    val verdict: String,
    val items: List<AdviceItemView>,
    val rulesFired: Int,
    val rulesTotal: Int,
)

/**
 * Collects the facts of an application — its terms, what the sources said, the automatic decision
 * and the credit policy it was made under — and runs the manual-review knowledge base on them.
 */
@Service
class AdviceService(
    private val applications: ApplicationRepository,
    private val enrichment: EnrichmentStore,
    private val decisions: DecisionRepository,
    private val rules: RuleSets,
    private val catalog: ProductCatalog,
    private val clock: Clock,
) {
    fun advice(id: UUID): AdviceView {
        CurrentCaller.requireBankStaff()
        val application = applications.find(id)?.takeIf { it.visibleToBank } ?: throw notFound()
        if (application.status.terminal) {
            val text = when (application.status) {
                ApplicationStatus.DISBURSED -> "Кредит уже выдан. Согласованные условия находятся в досье; новые варианты не рассчитываются."
                ApplicationStatus.REJECTED ->
                    "По заявке принято решение об отказе. Причины находятся в досье; " +
                        "ставка выдачи и новые варианты не рассчитываются."
                ApplicationStatus.WITHDRAWN -> "Заявка отозвана. Новые варианты условий не рассчитываются."
                else -> "Заявка закрыта. Новые варианты условий не рассчитываются."
            }
            val kind = if (application.status == ApplicationStatus.REJECTED) AdviceKind.STOP else AdviceKind.INFO
            return AdviceView(
                application.id,
                "Сеть правил андеррайтинга",
                UnderwritingAdviceModel.VERSION,
                text,
                listOf(AdviceItemView("TERMINAL", kind, "Рассмотрение завершено", text, listOf("Сохранённый статус заявки: ${application.status}"))),
                0,
                UnderwritingAdviceModel.model.rules.size,
            )
        }
        val sources = enrichment.load(application.id)
        val registry = sources[SourceKind.REGISTRY] as RegistryInfo?
        val tax = sources[SourceKind.TAX] as TaxInfo?
        val bureau = sources[SourceKind.BUREAU] as BureauInfo?
        val bank = sources[SourceKind.BANK] as BankInfo?
        val decision = decisions.latestAutomatic(application.id)
        val policy = decision?.let { rules.find(it.ruleSetVersion) } ?: rules.current()
        val product = catalog.forApplication(application)
        val terms = application.terms
        val inputs = decision?.inputs.orEmpty()
        val silent = (inputs["silentSources"] as? List<*>)?.mapNotNull { name -> SourceKind.entries.firstOrNull { it.name == name }?.title }.orEmpty()

        val facts: Map<String, Any?> = mapOf(
            P.AMOUNT to terms.amount,
            P.TERM to terms.termMonths,
            // The rate the policy offers at this rating, so the advised amount matches the system's counter offer.
            P.BASE_RATE to
                if (decision?.rating == Rating.E || !decision?.stopFactors.isNullOrEmpty()) null else CreditPolicy.rate(product, decision?.rating, policy),
            P.REVOLVING to product.revolving,
            P.MAX_TERM to product.maxTermMonths,
            P.MIN_AMOUNT to product.minAmount,
            P.MAX_AMOUNT to product.maxAmount,
            P.AUTO_LIMIT to product.autoApprovalLimit,
            P.ANNUAL_REVENUE to ((inputs["annualRevenue"] as? Number)?.toLong() ?: tax?.revenue ?: terms.declaredRevenue12m),
            P.EXISTING_PAYMENTS to
                ((inputs["existingMonthlyPayments"] as? Number)?.toLong() ?: bureau?.monthlyPaymentsTotal ?: terms.declaredMonthlyDebtPayments),
            P.MAX_LOAD to policy.maxLoad,
            P.REVENUE_MISMATCH to policy.revenueMismatch,
            P.DECLARED_REVENUE to terms.declaredRevenue12m,
            P.TAX_REVENUE to tax?.revenue,
            P.STOP_FACTORS to decision?.stopFactors?.map { it.clientText },
            P.SILENT_SOURCES to silent,
            P.BANK_CLIENT to bank?.client,
            P.TURNOVER_VARIATION to bank?.monthlyInflows?.takeIf { it.isNotEmpty() }?.let(Scorecard::coefficientOfVariation),
            P.INQUIRIES to bureau?.inquiries30d,
            P.WORST_OVERDUE to bureau?.worstOverdueDays24m,
            P.OVERDUE_NOW to bureau?.currentOverdueDaysMax,
            P.TAX_DEBT to tax?.taxDebt,
            P.AGE_MONTHS to registry?.let { ChronoUnit.MONTHS.between(it.registrationDate, LocalDate.now(clock)) },
            P.INDUSTRY_RISK to registry?.let { policy.industryRisk(it.okvedCode).name },
            P.RATING to decision?.rating?.name,
            P.SCORE to decision?.score,
        )
        val (items, inference) = UnderwritingAdviceModel.advise(facts)
        return AdviceView(
            applicationId = application.id,
            engine = "Сеть правил андеррайтинга",
            modelVersion = UnderwritingAdviceModel.VERSION,
            verdict = verdict(items.map { it.first.kind }),
            items = items.map { (item, because) -> AdviceItemView(item.code, item.kind, item.title, item.text, because) },
            rulesFired = inference.firings.size,
            rulesTotal = UnderwritingAdviceModel.model.rules.size,
        )
    }

    private fun verdict(kinds: List<AdviceKind>): String {
        if (AdviceKind.STOP in kinds) return "Есть стоп-фактор: по кредитной политике — отказ."
        val checks = kinds.count { it == AdviceKind.CHECK }
        val asks = kinds.count { it == AdviceKind.ASK }
        val risks = kinds.count { it == AdviceKind.RISK }
        if (checks + asks + risks == 0) return "Существенных вопросов к заявке нет: решение можно принимать по рекомендации системы."
        val parts = listOfNotNull(
            risks.takeIf { it > 0 }?.let { "рисков — $it" },
            checks.takeIf { it > 0 }?.let { "проверок — $it" },
            asks.takeIf { it > 0 }?.let { "вопросов клиенту — $it" },
        )
        val options = if (AdviceKind.OPTION in kinds) " Есть варианты в пределах порога нагрузки; остальные условия политики проверяются отдельно." else ""
        return "Перед решением: ${parts.joinToString(", ")}.$options"
    }
}

@Tag(name = "Решения сотрудников банка")
@RestController
@RequestMapping("/api/v1/underwriting/applications")
class AdviceController(private val service: AdviceService) {
    @Operation(summary = "Рекомендации экспертной системы: что проверить, что запросить, какие условия проходят — с цепочкой «почему»")
    @GetMapping("/{id}/advice")
    fun advice(@PathVariable id: UUID): AdviceView = service.advice(id)
}
