package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import ru.creditcabinet.common.exception.NotFoundException
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
    private val client: RestClient? = creditUrl.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        }
        builder.clone().baseUrl(it).requestFactory(requestFactory).build()
    }

    fun facts(applicationId: UUID, authorization: String): ApplicationFacts? {
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
        val missingRequired = app["requiredDocuments"].filter { !it["uploaded"].asBoolean() }.map { it["title"].asText() }
        val lines = buildList {
            add(
                "Продукт: ${app["product"]["name"].asText()}; запрошено ${rub(
                    app["amount"].asLong(),
                )} на ${app["termMonths"].asInt()} мес.; цель: ${app["purposeTitle"].asText()}",
            )
            app["estimate"].takeIf {
                it != null && !it.isNull
            }?.let { add("Ориентировочный платёж по базовой ставке ${percent(it["rate"].asText())}: ${kopecks(it["monthlyPayment"].asText())} в месяц") }
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
            number = app["number"].asText(),
            status = (STATUS[app["status"].asText()] ?: app["status"].asText()) +
                (app["closeReason"]?.takeIf { !it.isNull }?.let { reason -> CLOSE_REASONS[reason.asText()]?.let { ": $it" } } ?: ""),
            lines = lines,
            offer = offer,
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
        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM, HH:mm", Locale.forLanguageTag("ru"))
        val CLOSE_REASONS = mapOf(
            "INFO_EXPIRED" to "банк не получил запрошенные данные в срок",
            "OFFER_DECLINED" to "клиент отказался от предложения банка",
            "OFFER_EXPIRED" to "срок предложения истёк",
        )
        val STATUS = mapOf(
            "DRAFT" to "черновик, ещё не отправлена",
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
