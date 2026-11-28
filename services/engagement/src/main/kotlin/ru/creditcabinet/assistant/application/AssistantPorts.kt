package ru.creditcabinet.assistant.application

import ru.creditcabinet.assistant.domain.Answer
import ru.creditcabinet.assistant.domain.ApplicationFacts
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface AssistantApplications {
    fun facts(applicationId: UUID, authorization: String, agent: Boolean): ApplicationFacts?
    fun application(id: UUID, authorization: String, agent: Boolean): AssistantApplication
    fun agentApplications(authorization: String): List<AssistantApplicationRow>
    fun agentOffers(authorization: String): List<AssistantPreApprovedOffer>
    fun advice(applicationId: UUID, authorization: String): AdviceSnapshot?
    fun manualReviewAdvice(applicationId: UUID, eventId: UUID, foreground: Boolean = false): ManualReviewAdvice?
}

data class AssistantApplication(
    val status: String,
    val purpose: String,
    val amount: Long,
    val termMonths: Int,
    val productCode: String,
    val companyId: String,
    val missingDocuments: List<String>,
    val submissionProblems: List<String>,
    val infoRequest: InfoRequest?,
    val offer: AssistantOffer?,
)

data class InfoRequest(val id: UUID?, val deadline: Instant?, val items: List<InfoRequestItem>)

data class InfoRequestItem(val title: String, val hasDocument: Boolean)

data class AssistantOffer(val kind: String, val productCode: String, val validUntil: Instant?)

data class AssistantApplicationRow(val id: String, val status: String, val statusChangedAt: String)

data class AssistantPreApprovedOffer(val status: String, val validUntil: Instant?, val productName: String, val companyId: String)

interface AssistantProducts {
    fun purposes(authorization: String): Map<String, String>
    fun match(purpose: String, amount: Long, termMonths: Int, authorization: String): List<ProductMatch>?
    fun catalogue(authorization: String): List<AssistantProduct>?
}

data class AssistantProduct(val code: String, val name: String, val purposes: List<String>, val maxAmount: Long, val baseRate: BigDecimal)

data class ProductMatch(val product: AssistantProduct, val fits: Boolean, val adjustments: List<String>, val monthlyPayment: BigDecimal?)

interface ReferenceCache {
    fun key(material: String): String?
    fun read(key: String): String?
    fun save(key: String, text: String)
}

enum class AnswerOperation(val tag: String) {
    MESSAGE("message"),
    UNDERWRITING_SUMMARY("underwriting_summary"),
}

interface AnswerMetrics {
    fun measure(operation: AnswerOperation, answer: () -> Answer): Answer
    fun modelEvent(outcome: String)
    fun rejected(operation: AnswerOperation)
}

enum class AssistantFailureReason { UNAUTHENTICATED, FORBIDDEN, NOT_FOUND, CONFLICT, RATE_LIMIT, UNAVAILABLE, INVALID_EVENT }

class AssistantFailure(val reason: AssistantFailureReason, override val message: String) : RuntimeException(message)

internal fun forbidden(): Nothing = throw AssistantFailure(AssistantFailureReason.FORBIDDEN, "Недостаточно прав для этого действия")
