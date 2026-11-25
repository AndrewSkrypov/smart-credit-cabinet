package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.notification.Audience
import ru.creditcabinet.notification.NewNotification
import ru.creditcabinet.notification.NotificationService
import ru.creditcabinet.notification.NotificationType
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
)

@Service
class AssistantNextSteps(
    private val credit: CreditApplications,
    private val notifications: NotificationService,
    private val clock: Clock,
) {
    fun list(applicationId: UUID?, authorization: String): List<AssistantHint> {
        val role = CurrentCaller.claims().role
        if (role != Roles.CLIENT && role != Roles.AGENT) return emptyList()
        val agent = role == Roles.AGENT
        val now = clock.instant()
        if (applicationId != null) return summarize(applicationId, credit.application(applicationId, authorization, agent), agent, now)
        if (!agent) return emptyList()
        val applications = credit.agentApplications(authorization)
            .filter { it.path("status").asText() in ACTIONABLE }
            .sortedByDescending { it.path("statusChangedAt").asText() }
            .take(MAX_APPLICATIONS)
            .flatMap { row ->
                val id = UUID.fromString(row.path("id").asText())
                try {
                    summarize(id, credit.application(id, authorization, agent = true), true, now)
                } catch (_: NotFoundException) {
                    emptyList() // The assignment may have been revoked since the portfolio was read.
                }
            }
        val offers = credit.agentOffers(authorization).filter { it.path("status").asText() == "ACTIVE" }
            .mapNotNull { row ->
                val expiry = row.path("validUntil").instant() ?: return@mapNotNull null
                if (expiry <= now) return@mapNotNull null
                AssistantHint(
                    HintType.PRE_APPROVED,
                    "Есть предодобренное предложение",
                    row.path("productName").asText(),
                    deadline = expiry,
                    companyId = UUID.fromString(row.path("companyId").asText()),
                )
            }.take(MAX_PRE_APPROVED)
        return applications + offers
    }

    fun sendReminder(id: UUID, authorization: String) {
        CurrentCaller.requireRole(Roles.AGENT)
        val app = credit.application(id, authorization, agent = true)
        val request = app.path("process").path("infoRequest")
        val documents = request.path("items").filter { it.path("documentType").isTextual }.map { it.path("title").asText() }
        if (app.path("status").asText() != "INFO_REQUESTED" ||
            documents.isEmpty() ||
            request.path("deadline").instant()?.isAfter(clock.instant()) != true
        ) {
            throw ApiException(HttpStatus.CONFLICT, "Сейчас нет действующего запроса документов для напоминания")
        }
        val companyId = UUID.fromString(app.path("company").path("id").asText())
        val requestId = UUID.fromString(request.path("id").asText())
        notifications.deliver(
            listOf(
                NewNotification(
                    audience = Audience(companyId = companyId),
                    type = NotificationType.ACTION_REQUIRED,
                    title = "Агент напомнил о запросе документов",
                    body = "Банк ждёт документы по заявке. Откройте карточку и проверьте список.",
                    link = "/client/applications/$id",
                    dedupKey = "assistant-documents:$id:$requestId",
                ),
            ),
        )
    }

    internal fun summarize(id: UUID, app: JsonNode, agent: Boolean, now: Instant): List<AssistantHint> {
        val process = app.path("process")
        return when (app.path("status").asText()) {
            "DRAFT" -> {
                val missing = app.path("requiredDocuments").filter { !it.path("uploaded").asBoolean() }.map { it.path("title").asText() }
                val problems = app.path("submissionProblems").map(JsonNode::asText).filter(String::isNotBlank)
                val checklist = missing.ifEmpty { problems }
                if (checklist.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        AssistantHint(
                            HintType.MISSING_DOCUMENTS,
                            if (missing.isEmpty()) "Проверьте данные перед отправкой" else "В черновике не хватает документов",
                            checklist.joinToString("\n"),
                            id,
                        ),
                    )
                }
            }
            "INFO_REQUESTED" -> {
                val request = process.path("infoRequest")
                val deadline = request.path("deadline").instant() ?: return emptyList()
                if (deadline <= now) return emptyList()
                val checkList = request.path("items").map { it.path("title").asText() }.filter(String::isNotBlank)
                val hasDocuments = request.path("items").any { it.path("documentType").isTextual }
                val title = if (deadline <= now.plus(REMINDER_WINDOW)) "Скоро истечёт срок ответа банку" else "Банк ждёт ответа по заявке"
                listOf(AssistantHint(HintType.INFO_DEADLINE, title, checkList.joinToString("\n"), id, deadline, agent && hasDocuments))
            }
            "OFFER_ISSUED" -> buildList {
                val offer = process.path("offer")
                val deadline = offer.path("validUntil").instant()
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
                if (offer.path("kind").asText() == "COUNTER") add(alternative(id))
            }
            "REJECTED" -> listOf(alternative(id))
            else -> emptyList()
        }
    }

    private fun JsonNode.instant(): Instant? = if (isTextual) runCatching { Instant.parse(asText()) }.getOrNull() else null

    private fun alternative(id: UUID) =
        AssistantHint(HintType.ALTERNATIVE, "Можно сравнить другие продукты", "Подбор посчитает ориентировочный платёж по параметрам этой заявки.", id)

    private companion object {
        val ACTIONABLE = setOf("DRAFT", "INFO_REQUESTED", "OFFER_ISSUED")
        val REMINDER_WINDOW: Duration = Duration.ofDays(2)
        const val MAX_APPLICATIONS = 8
        const val MAX_PRE_APPROVED = 3
    }
}
