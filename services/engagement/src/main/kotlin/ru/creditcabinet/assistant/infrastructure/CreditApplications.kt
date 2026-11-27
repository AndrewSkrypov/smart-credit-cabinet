package ru.creditcabinet.assistant.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import ru.creditcabinet.assistant.application.AdviceSnapshot
import ru.creditcabinet.assistant.application.AssistantApplication
import ru.creditcabinet.assistant.application.AssistantApplicationRow
import ru.creditcabinet.assistant.application.AssistantApplications
import ru.creditcabinet.assistant.application.AssistantFailure
import ru.creditcabinet.assistant.application.AssistantFailureReason
import ru.creditcabinet.assistant.application.AssistantOffer
import ru.creditcabinet.assistant.application.AssistantPreApprovedOffer
import ru.creditcabinet.assistant.application.InfoRequest
import ru.creditcabinet.assistant.application.InfoRequestItem
import ru.creditcabinet.assistant.application.ManualReviewAdvice
import ru.creditcabinet.assistant.application.UnderwritingSummaryInput
import ru.creditcabinet.assistant.domain.ApplicationFacts
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/**
 * Reads the application from credit with the caller's own token: the assistant sees exactly what
 * the person may see, and another company's application stays "not found".
 */
@Component
class CreditApplications(
    @Value("\${cabinet.credit.url:}") creditUrl: String,
    builder: RestClient.Builder,
) : AssistantApplications {
    private val client = creditClient(creditUrl, builder, Duration.ofSeconds(2), Duration.ofSeconds(5))
    private val reviewCheckClient = creditClient(creditUrl, builder, Duration.ofMillis(300), Duration.ofMillis(500))

    private fun creditClient(url: String, builder: RestClient.Builder, connect: Duration, read: Duration): RestClient? = url.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(connect)
            setReadTimeout(read)
        }
        builder.clone().baseUrl(it).requestFactory(requestFactory).build()
    }

    /** The expert system's advice on an application, read with the staff member's own token. */
    override fun advice(applicationId: UUID, authorization: String): AdviceSnapshot? {
        val client = client ?: return null
        return try {
            client.get().uri("/api/v1/underwriting/applications/{id}/advice", applicationId).header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().body(JsonNode::class.java)?.toAdviceSnapshot()
        } catch (exception: HttpClientErrorException.NotFound) {
            throw AssistantFailure(AssistantFailureReason.NOT_FOUND, "Заявка не найдена")
        } catch (exception: Exception) {
            null
        }
    }

    /** Credit binds this private read to the latest transition into manual review. */
    override fun manualReviewAdvice(applicationId: UUID, eventId: UUID, foreground: Boolean): ManualReviewAdvice? {
        val target = (if (foreground) reviewCheckClient else client) ?: unavailable()
        val response = try {
            target.get().uri(
                "/internal/v1/underwriting/applications/{id}/advice?eventId={eventId}",
                applicationId,
                eventId,
            ).retrieve().body(JsonNode::class.java)
        } catch (_: HttpClientErrorException.Conflict) {
            return null
        }
        if (response !is ObjectNode || response.path("applicationId").asText() != applicationId.toString()) unavailable()
        val facts = response.deepCopy()
        val revision = facts.remove("adviceRevision")?.takeIf { it.isTextual }?.asText()?.lowercase()
        if (revision == null || !revision.matches(ADVICE_REVISION)) unavailable()
        return ManualReviewAdvice(revision, facts.toAdviceSnapshot())
    }

    override fun facts(applicationId: UUID, authorization: String, agent: Boolean): ApplicationFacts? {
        if (agent) return describe(read(authorization, "/api/v1/agent/applications/{id}", applicationId))
        val client = client ?: return null
        val application = try {
            client.get().uri("/api/v1/applications/{id}", applicationId).header(HttpHeaders.AUTHORIZATION, authorization).retrieve().body(JsonNode::class.java)
        } catch (exception: HttpClientErrorException.NotFound) {
            throw AssistantFailure(AssistantFailureReason.NOT_FOUND, "Заявка не найдена")
        } catch (exception: Exception) {
            return null
        } ?: return null
        return describe(application)
    }

    override fun application(id: UUID, authorization: String, agent: Boolean): AssistantApplication =
        read(authorization, if (agent) "/api/v1/agent/applications/{id}" else "/api/v1/applications/{id}", id).toAssistantApplication()

    override fun agentApplications(authorization: String): List<AssistantApplicationRow> =
        read(authorization, "/api/v1/agent/applications").map {
            AssistantApplicationRow(it.path("id").asText(), it.path("status").asText(), it.path("statusChangedAt").asText())
        }

    override fun agentOffers(authorization: String): List<AssistantPreApprovedOffer> =
        read(authorization, "/api/v1/agent/pre-approved-offers").map {
            AssistantPreApprovedOffer(
                it.path("status").asText(),
                it.path("validUntil").instant(),
                it.path("productName").asText(),
                it.path("companyId").asText(),
            )
        }

    private fun read(authorization: String, path: String, vararg variables: UUID): JsonNode = requestWithCaller {
        get().uri(path, *variables).header(HttpHeaders.AUTHORIZATION, authorization)
            .retrieve().body(JsonNode::class.java) ?: unavailable()
    }

    private fun <T> requestWithCaller(block: RestClient.() -> T): T {
        val target = client ?: unavailable()
        return try {
            target.block()
        } catch (_: HttpClientErrorException.NotFound) {
            throw AssistantFailure(AssistantFailureReason.NOT_FOUND, "Заявка не найдена")
        } catch (_: HttpClientErrorException.Unauthorized) {
            throw AssistantFailure(AssistantFailureReason.UNAUTHENTICATED, "Требуется вход в систему")
        } catch (_: HttpClientErrorException.Forbidden) {
            throw AssistantFailure(AssistantFailureReason.FORBIDDEN, "Недостаточно прав для этого действия")
        } catch (_: HttpClientErrorException.Conflict) {
            throw AssistantFailure(AssistantFailureReason.CONFLICT, "Действие недоступно в текущем состоянии заявки")
        } catch (_: RestClientException) {
            unavailable()
        }
    }

    private fun unavailable(): Nothing = throw AssistantFailure(AssistantFailureReason.UNAVAILABLE, "Данные заявки сейчас недоступны")

    private fun describe(app: JsonNode): ApplicationFacts {
        val process = app["process"]
        val offerNode = process["offer"].takeIf { it != null && !it.isNull }
        val offer = offerNode?.let {
            val kind = if (it["kind"].asText() == "COUNTER") "контрпредложение" else "одобрение на запрошенных условиях"
            val changes = it["changes"].map { change -> change.asText().trimEnd('.') }.takeIf { list -> list.isNotEmpty() }
                ?.joinToString("; ", prefix = "; изменения: ") ?: ""
            "Предложение банка ($kind): ${it["productName"].asText()}, ${rub(it["amount"].asLong())}, ${it["termMonths"].asInt()} мес., " +
                "ставка ${percent(it["rate"].asText())}, платёж ${kopecks(it["monthlyPayment"].asText())} в месяц, " +
                "действует до ${date(it["validUntil"].asText())}$changes"
        }
        // The explanation usually starts with the title itself: then the title is not repeated.
        val refusal = process["outcome"].takeIf { it != null && !it.isNull }?.let { outcome ->
            val lines = outcome["explanation"].flatMap { it.asText().split("\n") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val title = outcome["reasonTitle"]?.takeIf { !it.isNull }?.asText()
            if (title == null || lines.any { it.startsWith(title) }) lines else listOf(title) + lines
        }.orEmpty()
        val request = process["infoRequest"].takeIf { it != null && !it.isNull }
        val requested = request?.get("items")?.map { it["title"].asText() }.orEmpty()
        val estimate = app["estimate"].takeIf { it != null && !it.isNull }
            ?.let { "Ориентировочный платёж по базовой ставке ${percent(it["rate"].asText())}: ${kopecks(it["monthlyPayment"].asText())} в месяц" }
        val missingRequired = app["requiredDocuments"].filter { !it["uploaded"].asBoolean() }.map { it["title"].asText() }
        val lines = buildList {
            add(
                "Продукт: ${app["product"]["name"].asText()}; запрошено ${rub(
                    app["amount"].asLong(),
                )} на ${app["termMonths"].asInt()} мес.; цель: ${app["purposeTitle"].asText()}",
            )
            estimate?.let(::add)
            if (requested.isNotEmpty()) add("Банк запросил: ${requested.joinToString("; ")}; срок ответа до ${date(request!!["deadline"].asText())}")
            if (app["status"].asText() == "DRAFT" && missingRequired.isNotEmpty()) add("Для отправки нужны документы: ${missingRequired.joinToString("; ")}")
            app["submissionProblems"].map(JsonNode::asText).takeIf { it.isNotEmpty() }?.let { add("Что мешает отправить: ${it.joinToString("; ")}") }
            offer?.let(::add)
            if (refusal.isNotEmpty()) add("Причины отказа:\n${refusal.joinToString("\n")}")
            process["loan"].takeIf {
                it != null && !it.isNull
            }?.let { add("Кредит выдан: договор ${it["contractNumber"].asText()}, погашение до ${it["maturityDate"].asText()}") }
        }
        return ApplicationFacts(
            number = app.path("number").asText("без номера"),
            status = (STATUS[app["status"].asText()] ?: app["status"].asText()) +
                (app["closeReason"]?.takeIf { !it.isNull }?.let { reason -> CLOSE_REASONS[reason.asText()]?.let { ": $it" } } ?: ""),
            lines = lines,
            offer = offer,
            estimate = estimate,
            refusal = refusal,
            missingDocuments = requested.ifEmpty { missingRequired },
            deadline = request?.get("deadline")?.asText()?.let(::date),
        )
    }

    private fun rub(amount: Long) = "%,d ₽".format(Locale.ROOT, amount).replace(',', ' ')

    /** «99 761,88 ₽»: the way the cabinet writes money, so the figures match what the client sees. */
    private fun kopecks(amount: String) =
        amount.toBigDecimalOrNull()?.let { "%,.2f ₽".format(Locale.ROOT, it).replace(',', ' ').replace('.', ',') } ?: "$amount ₽"

    private fun percent(rate: String) = (rate.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: rate).replace('.', ',') + "%"

    private fun date(iso: String) = runCatching { DATE.format(Instant.parse(iso).atZone(MOSCOW)) }.getOrDefault(iso)

    private companion object {
        val ADVICE_REVISION = Regex("[0-9a-f]{64}")
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru"))
        val CLOSE_REASONS = mapOf(
            "INFO_EXPIRED" to "банк не получил запрошенные данные в срок",
            "OFFER_DECLINED" to "клиент отказался от предложения банка",
            "OFFER_EXPIRED" to "срок предложения истёк",
        )
        val STATUS = mapOf(
            "DRAFT" to "черновик, ещё не отправлена",
            "PENDING_CLIENT_CONFIRMATION" to "ждёт подтверждения клиента",
            "SUBMITTED" to "принята, банк проверяет полноту данных",
            "INFO_REQUESTED" to "банк ждёт от клиента дополнительные данные",
            "ENRICHMENT" to "банк запрашивает сведения в ЕГРЮЛ/ЕГРИП, ФНС и БКИ",
            "SCORING" to "банк оценивает заявку",
            "MANUAL_REVIEW" to "заявку рассматривает специалист банка",
            "OFFER_ISSUED" to "есть предложение банка, ждём ответа клиента",
            "SIGNING" to "договор готов к подписанию кодом",
            "DISBURSING" to "договор подписан, деньги зачисляются",
            "DISBURSED" to "деньги зачислены",
            "REJECTED" to "банк отказал",
            "WITHDRAWN" to "клиент отозвал заявку",
            "CLOSED" to "заявка закрыта",
        )
    }
}

internal fun JsonNode.toAssistantApplication(): AssistantApplication {
    val process = path("process")
    val request = process.path("infoRequest")
    val offer = process.path("offer")
    return AssistantApplication(
        status = path("status").asText(),
        purpose = path("purpose").asText(),
        amount = path("amount").asLong(),
        termMonths = path("termMonths").asInt(),
        productCode = path("product").path("code").asText(),
        companyId = path("company").path("id").asText(),
        missingDocuments = path("requiredDocuments").filter { !it.path("uploaded").asBoolean() }.map { it.path("title").asText() },
        submissionProblems = path("submissionProblems").map(JsonNode::asText),
        infoRequest = request.takeIf { it.isObject }?.let {
            InfoRequest(
                it.path("id").takeIf(JsonNode::isTextual)?.asText()?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() },
                it.path("deadline").instant(),
                it.path("items").map { item -> InfoRequestItem(item.path("title").asText(), item.path("documentType").isTextual) },
            )
        },
        offer = offer.takeIf { it.isObject }?.let {
            AssistantOffer(it.path("kind").asText(), it.path("productCode").asText(), it.path("validUntil").instant())
        },
    )
}

private fun JsonNode.instant(): Instant? = if (isTextual) runCatching { Instant.parse(asText()) }.getOrNull() else null

internal fun JsonNode.toAdviceSnapshot(): AdviceSnapshot = AdviceSnapshot(
    UnderwritingSummaryInput(
        get("verdict").asText(),
        get("items").map { "${it["title"].asText()}. ${it["text"].asText()}" },
        get("modelVersion").asInt(),
    ),
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson().toByteArray(Charsets.UTF_8))),
)

// Object field order is irrelevant, but array order and fields omitted from the summary still affect the snapshot.
private fun JsonNode.canonicalJson(): String = when {
    isObject -> fields().asSequence().sortedBy { it.key }.joinToString(",", "{", "}") { (key, value) ->
        "${TextNode.valueOf(key)}:${value.canonicalJson()}"
    }
    isArray -> joinToString(",", "[", "]") { it.canonicalJson() }
    else -> toString()
}
