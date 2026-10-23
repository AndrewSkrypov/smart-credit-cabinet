package ru.creditcabinet.application.domain

import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductMatcher.rubles
import java.time.Instant
import java.util.UUID

enum class LegalForm { LEGAL_ENTITY, ENTREPRENEUR }

/** The company as it was when the application was created; identity owns the company itself. */
data class CompanySnapshot(val id: UUID, val inn: String, val name: String?, val legalForm: LegalForm)

/** What the client fills in. Everything is optional in a draft; [LoanApplication.submit] checks completeness. */
data class ApplicationTerms(
    val productCode: String,
    val purpose: FinancingPurpose,
    val purposeComment: String? = null,
    val amount: Long,
    val termMonths: Int,
    val declaredRevenue12m: Long? = null,
    val declaredMonthlyDebtPayments: Long? = null,
    val bureauConsent: Boolean = false,
)

/** One line of an application's journal: a transition, or a change that matters within a status. */
data class JournalEntry(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    /** An [ApplicationEvent] name, or a [JournalNote] name for changes without a transition. */
    val event: String,
    val fromStatus: ApplicationStatus?,
    val toStatus: ApplicationStatus,
    val actor: Actor,
    val reasonCode: String? = null,
    val comment: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val occurredAt: Instant,
) {
    val isTransition: Boolean get() = fromStatus != toStatus
}

/** Journal entries that are not transitions. */
enum class JournalNote { CREATED, DOCUMENT_ADDED, DOCUMENT_REMOVED }

data class Transition(val application: LoanApplication, val entry: JournalEntry)

/**
 * The credit application: the aggregate every step of the pipeline changes. The status moves
 * only through [move], which follows [ApplicationStateMachine] and writes a journal entry;
 * there is no way to set it directly.
 */
data class LoanApplication(
    val id: UUID = UUID.randomUUID(),
    /** Assigned by the database on insert. */
    val number: Long? = null,
    val company: CompanySnapshot,
    val createdBy: UUID,
    val terms: ApplicationTerms,
    val status: ApplicationStatus = ApplicationStatus.DRAFT,
    val closeReason: CloseReason? = null,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
    val submittedAt: Instant? = null,
    val statusChangedAt: Instant = createdAt,
    /** Optimistic lock; null until the application is first saved. */
    val version: Long? = null,
    /** In a draft this is the version the client originally chose; after submission it is pinned. */
    val productVersion: Long = 1,
    val productCatalogSnapshot: Map<String, Long> = emptyMap(),
) {
    val isActive: Boolean get() = !status.terminal

    /** A draft is the client's own until it is sent: the bank does not see it. */
    val visibleToBank: Boolean get() = status != ApplicationStatus.DRAFT

    fun updateDraft(terms: ApplicationTerms, now: Instant): LoanApplication {
        if (status != ApplicationStatus.DRAFT) throw ConflictException("Заявка уже отправлена — изменить её можно только по запросу банка")
        require(terms.productCode == this.terms.productCode) { "Продукт заявки не меняется — создайте новую заявку" }
        return copy(terms = terms, updatedAt = now)
    }

    /** What still prevents sending the application (guard of T2), in words the client can act on. */
    fun submissionProblems(product: Product): List<String> =
        buildList {
            if (terms.purpose !in product.purposes) add("Продукт «${product.name}» не подходит для этой цели")
            if (!product.amountFits(terms.amount)) add("Сумма — от ${rubles(product.minAmount)} до ${rubles(product.maxAmount)}")
            if (!product.termFits(terms.termMonths)) add("Срок — от ${product.minTermMonths} до ${product.maxTermMonths} мес.")
            if (terms.declaredRevenue12m == null || terms.declaredRevenue12m <= 0) add("Укажите выручку за последние 12 месяцев")
            if (terms.declaredMonthlyDebtPayments != null && terms.declaredMonthlyDebtPayments < 0) add("Платежи по кредитам не могут быть отрицательными")
            if (!terms.bureauConsent) add("Без согласия на запрос в бюро кредитных историй заявку не рассмотреть")
        }

    fun submit(product: Product, actor: Actor, now: Instant): Transition {
        val problems = submissionProblems(product)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        return move(ApplicationEvent.SUBMIT, actor, now).let { it.copy(application = it.application.copy(submittedAt = now)) }
    }

    /**
     * Moves the application by [event] if the state machine allows it from the current status
     * for this [actor]; otherwise 409 and nothing changes.
     */
    fun move(
        event: ApplicationEvent,
        actor: Actor,
        now: Instant,
        reasonCode: String? = null,
        comment: String? = null,
        payload: Map<String, Any?> = emptyMap(),
    ): Transition {
        if (actor.type !in event.actors) throw ConflictException("Это действие недоступно для вашей роли")
        val target = ApplicationStateMachine.target(status, event) ?: throw ConflictException(refusal(event))
        val moved = copy(
            status = target,
            closeReason = closeReasonOf(event) ?: closeReason,
            updatedAt = now,
            statusChangedAt = if (target != status) now else statusChangedAt,
        )
        val entry = JournalEntry(
            applicationId = id,
            event = event.name,
            fromStatus = status,
            toStatus = target,
            actor = actor,
            reasonCode = reasonCode,
            comment = comment,
            payload = payload,
            occurredAt = now,
        )
        return Transition(moved, entry)
    }

    /** A journal entry for a change that does not move the status. */
    fun note(note: JournalNote, actor: Actor, now: Instant, payload: Map<String, Any?> = emptyMap()) =
        JournalEntry(applicationId = id, event = note.name, fromStatus = status, toStatus = status, actor = actor, payload = payload, occurredAt = now)

    private fun closeReasonOf(event: ApplicationEvent): CloseReason? =
        when (event) {
            ApplicationEvent.INFO_EXPIRED -> CloseReason.INFO_EXPIRED
            ApplicationEvent.DECLINE_OFFER -> CloseReason.OFFER_DECLINED
            ApplicationEvent.OFFER_EXPIRED -> CloseReason.OFFER_EXPIRED
            else -> null
        }

    private fun refusal(event: ApplicationEvent): String =
        when {
            status.terminal -> "Заявка уже завершена — создайте новую"
            event == ApplicationEvent.WITHDRAW -> "Сейчас заявку отозвать нельзя: идёт автоматический шаг, попробуйте через минуту"
            event == ApplicationEvent.SUBMIT -> "Заявка уже отправлена"
            else -> "Действие недоступно на этапе «${status.name}»"
        }
}
