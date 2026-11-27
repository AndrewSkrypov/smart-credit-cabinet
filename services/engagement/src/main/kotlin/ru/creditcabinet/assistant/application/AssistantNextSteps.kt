package ru.creditcabinet.assistant.application

import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.notification.Audience
import ru.creditcabinet.notification.NewNotification
import ru.creditcabinet.notification.NotificationType
import ru.creditcabinet.notification.application.NotificationDelivery
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class HintType { MISSING_DOCUMENTS, INFO_DEADLINE, OFFER_DEADLINE, PRE_APPROVED, ALTERNATIVE }

data class AssistantHint(
    val type: HintType,
    val title: String,
    val detail: String,
    val applicationId: UUID? = null,
    val deadline: Instant? = null,
    val canRemind: Boolean = false,
    val companyId: UUID? = null,
    val infoRequestId: UUID? = null,
)

class AssistantNextSteps(
    private val credit: AssistantApplications,
    private val notifications: NotificationDelivery,
    private val clock: Clock,
) {
    fun list(applicationId: UUID?, authorization: String, claims: TokenClaims): List<AssistantHint> {
        if (!claims.isClient && !claims.isAgent) return emptyList()
        val agent = claims.isAgent
        val now = clock.instant()
        if (applicationId != null) return summarize(applicationId, credit.application(applicationId, authorization, agent), agent, now)
        if (!agent) return emptyList()
        val applications = credit.agentApplications(authorization)
            .filter { it.status in ACTIONABLE }
            .sortedByDescending { it.statusChangedAt }
            .take(MAX_APPLICATIONS)
            .flatMap { row ->
                val id = UUID.fromString(row.id)
                try {
                    summarize(id, credit.application(id, authorization, agent = true), true, now)
                } catch (failure: AssistantFailure) {
                    if (failure.reason != AssistantFailureReason.NOT_FOUND) throw failure
                    emptyList() // The assignment may have been revoked since the portfolio was read.
                }
            }
        val offers = credit.agentOffers(authorization).filter { it.status == "ACTIVE" }
            .mapNotNull { row ->
                val expiry = row.validUntil ?: return@mapNotNull null
                if (expiry <= now) return@mapNotNull null
                AssistantHint(
                    HintType.PRE_APPROVED,
                    "Есть предодобренное предложение",
                    row.productName,
                    deadline = expiry,
                    companyId = UUID.fromString(row.companyId),
                )
            }.take(MAX_PRE_APPROVED)
        return applications + offers
    }

    fun sendReminder(id: UUID, infoRequestId: UUID, authorization: String, claims: TokenClaims) {
        if (!claims.isAgent) forbidden()
        val app = credit.application(id, authorization, agent = true)
        val request = app.infoRequest
        val hasDocuments = request?.items?.any(InfoRequestItem::hasDocument) == true
        if (app.status != "INFO_REQUESTED" ||
            !hasDocuments ||
            request?.deadline?.isAfter(clock.instant()) != true
        ) {
            throw AssistantFailure(AssistantFailureReason.CONFLICT, "Сейчас нет действующего запроса документов для напоминания")
        }
        if (request?.id != infoRequestId) {
            throw AssistantFailure(AssistantFailureReason.CONFLICT, "Запрос документов изменился. Проверьте новый запрос перед отправкой напоминания.")
        }
        val companyId = UUID.fromString(app.companyId)
        notifications.deliver(
            listOf(
                NewNotification(
                    audience = Audience(companyId = companyId),
                    type = NotificationType.ACTION_REQUIRED,
                    title = "Агент напомнил о запросе документов",
                    body = "Банк ждёт документы по заявке. Откройте карточку и проверьте список.",
                    link = "/client/applications/$id",
                    dedupKey = "assistant-documents:$id:$infoRequestId",
                ),
            ),
        )
    }

    internal fun summarize(id: UUID, app: AssistantApplication, agent: Boolean, now: Instant): List<AssistantHint> {
        return when (app.status) {
            "DRAFT" -> {
                val missing = app.missingDocuments
                val problems = app.submissionProblems.filter(String::isNotBlank)
                    .filterNot { agent && it.contains("бюро кредитных историй", ignoreCase = true) }
                val checklist = (missing + problems).distinct()
                if (checklist.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        AssistantHint(
                            HintType.MISSING_DOCUMENTS,
                            if (missing.isEmpty()) "Проверьте данные перед отправкой" else "Дополните черновик",
                            checklist.joinToString("\n"),
                            id,
                        ),
                    )
                }
            }
            "INFO_REQUESTED" -> {
                val request = app.infoRequest ?: return emptyList()
                val deadline = request.deadline ?: return emptyList()
                if (deadline <= now) return emptyList()
                val checkList = request.items.map(InfoRequestItem::title).filter(String::isNotBlank)
                val hasDocuments = request.items.any(InfoRequestItem::hasDocument)
                val title = if (deadline <= now.plus(REMINDER_WINDOW)) "Скоро истечёт срок ответа банку" else "Банк ждёт ответа по заявке"
                val infoRequestId = if (agent && hasDocuments) request.id else null
                listOf(
                    AssistantHint(
                        HintType.INFO_DEADLINE,
                        title,
                        checkList.joinToString("\n"),
                        id,
                        deadline,
                        canRemind = infoRequestId != null,
                        infoRequestId = infoRequestId,
                    ),
                )
            }
            "OFFER_ISSUED" -> buildList {
                val offer = app.offer
                val deadline = offer?.validUntil
                if (deadline != null && deadline > now && deadline <= now.plus(REMINDER_WINDOW)) {
                    add(
                        AssistantHint(
                            HintType.OFFER_DEADLINE,
                            "Скоро истечёт срок предложения",
                            "Клиент может ответить на предложение в своём кабинете.",
                            id,
                            deadline,
                        ),
                    )
                }
                if (offer?.kind == "COUNTER") add(alternative(id))
            }
            "REJECTED" -> listOf(alternative(id))
            else -> emptyList()
        }
    }

    private fun alternative(id: UUID) =
        AssistantHint(HintType.ALTERNATIVE, "Можно сравнить другие продукты", "Подбор посчитает ориентировочный платёж по параметрам этой заявки.", id)

    private companion object {
        val ACTIONABLE = setOf("DRAFT", "INFO_REQUESTED", "OFFER_ISSUED")
        val REMINDER_WINDOW: Duration = Duration.ofDays(2)
        const val MAX_APPLICATIONS = 8
        const val MAX_PRE_APPROVED = 3
    }
}
