package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** What the assistant may know about one application, in words; facts only, no guesses. */
data class ApplicationFacts(
    val number: String,
    val status: String,
    val lines: List<String>,
    val offer: String?,
    /** The cabinet's own estimate at the base rate: a calculation, never the bank's terms. */
    val estimate: String? = null,
    val refusal: List<String>,
    val missingDocuments: List<String>,
    val deadline: String?,
) {
    fun asText(): String = (listOf("Заявка № $number, статус: $status") + lines).joinToString("\n")
}

/**
 * Reads the application from credit with the caller's own token: the assistant sees exactly what
 * the person may see, and another company's application stays "not found".
 */
@Component
class CreditApplications(
    @Value("\${cabinet.credit.url:}") creditUrl: String,
    builder: RestClient.Builder,
) {
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
    fun advice(applicationId: UUID, authorization: String): JsonNode? {
        val client = client ?: return null
        return try {
            client.get().uri("/api/v1/underwriting/applications/{id}/advice", applicationId).header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().body(JsonNode::class.java)
        } catch (exception: HttpClientErrorException.NotFound) {
            throw NotFoundException("Заявка не найдена")
        } catch (exception: Exception) {
            null
        }
    }

    /** Credit binds this private read to the latest transition into manual review. */
    fun manualReviewAdvice(applicationId: UUID, eventId: UUID, foreground: Boolean = false): ManualReviewAdvice? {
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
        return ManualReviewAdvice(revision, facts)
    }

    fun facts(applicationId: UUID, authorization: String): ApplicationFacts? {
        if (CurrentCaller.claims().isAgent) return describe(application(applicationId, authorization, agent = true))
        val client = client ?: return null
        val application = try {
            client.get().uri("/api/v1/applications/{id}", applicationId).header(HttpHeaders.AUTHORIZATION, authorization).retrieve().body(JsonNode::class.java)
        } catch (exception: HttpClientErrorException.NotFound) {
            throw NotFoundException("Заявка не найдена")
        } catch (exception: Exception) {
            return null
        } ?: return null
        return describe(application)
    }

    fun application(id: UUID, authorization: String, agent: Boolean): JsonNode =
        read(authorization, if (agent) "/api/v1/agent/applications/{id}" else "/api/v1/applications/{id}", id)

    fun agentApplications(authorization: String): JsonNode = read(authorization, "/api/v1/agent/applications")

    fun agentOffers(authorization: String): JsonNode = read(authorization, "/api/v1/agent/pre-approved-offers")

    private fun read(authorization: String, path: String, vararg variables: UUID): JsonNode = requestWithCaller {
        get().uri(path, *variables).header(HttpHeaders.AUTHORIZATION, authorization)
            .retrieve().body(JsonNode::class.java) ?: unavailable()
    }

    private fun <T> requestWithCaller(block: RestClient.() -> T): T {
        val target = client ?: unavailable()
        return try {
            target.block()
        } catch (_: HttpClientErrorException.NotFound) {
            throw NotFoundException("Заявка не найдена")
        } catch (_: HttpClientErrorException.Unauthorized) {
            throw ApiException(HttpStatus.UNAUTHORIZED, "Требуется вход в систему")
        } catch (_: HttpClientErrorException.Forbidden) {
            throw ApiException(HttpStatus.FORBIDDEN, "Недостаточно прав для этого действия")
        } catch (_: HttpClientErrorException.Conflict) {
            throw ApiException(HttpStatus.CONFLICT, "Действие недоступно в текущем состоянии заявки")
        } catch (_: RestClientException) {
            unavailable()
        }
    }

    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Данные заявки сейчас недоступны")

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

    private fun date(iso: String) = runCatching { DATE.format(java.time.Instant.parse(iso).atZone(MOSCOW)) }.getOrDefault(iso)

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
