package ru.creditcabinet.financing.invoice

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.document.application.DocumentUploadLimit
import ru.creditcabinet.document.domain.DocumentScanner
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

enum class InvoiceExtractionMode { MODEL, MANUAL }

data class InvoiceExtractionResult(
    val mode: InvoiceExtractionMode,
    val amount: InvoiceFact<Long>? = null,
    val dates: List<InvoiceFact<LocalDate>> = emptyList(),
    val reason: String? = null,
    val needsConfirmation: Boolean = true,
)

internal data class InvoiceModelChoice(val amountIndex: Int?, val dateIndex: Int?)

internal fun interface InvoiceModel {
    fun select(amountQuotes: List<String>, dateQuotes: List<String>, authorization: String): InvoiceModelChoice?
}

@Component
internal class EngagementInvoiceModel(
    @Value("\${cabinet.engagement.url:}") url: String,
    builder: RestClient.Builder,
) : InvoiceModel {
    private val client = url.takeIf { it.isNotBlank() }?.let {
        val timeouts = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(1))
            setReadTimeout(Duration.ofSeconds(8))
        }
        builder.clone().baseUrl(it).requestFactory(timeouts).build()
    }

    override fun select(amountQuotes: List<String>, dateQuotes: List<String>, authorization: String): InvoiceModelChoice? =
        try {
            client?.post()?.uri("/internal/v1/assistant/invoice-facts")
                ?.header(HttpHeaders.AUTHORIZATION, authorization)?.contentType(MediaType.APPLICATION_JSON)
                ?.body(mapOf("amounts" to amountQuotes, "dates" to dateQuotes))
                ?.retrieve()?.body(InvoiceModelChoice::class.java)
        } catch (_: RestClientException) {
            null
        }
}

@Service
internal class InvoiceExtraction(private val model: InvoiceModel) {
    private val reader = InvoicePdfReader()

    fun extract(bytes: ByteArray, authorization: String): InvoiceExtractionResult {
        val evidence = InvoiceEvidence.from(reader.read(bytes))
        val amount = evidence.amounts.singleOrNull()
        val dates = evidence.dates.map { it.fact }.distinctBy { it.value }
        fun manual(reason: String, candidates: List<InvoiceFact<LocalDate>> = dates.takeIf { it.size <= 2 }.orEmpty()) =
            InvoiceExtractionResult(InvoiceExtractionMode.MANUAL, amount, candidates, reason)

        if (amount == null || dates.isEmpty()) return manual("Проверьте сумму и дату по счёту и заполните пропуски")
        if (dates.size > 1) return manual("В счёте несколько дат. Уточните дату ожидаемого поступления")
        if (evidence.dates.none { it.label == InvoiceDateLabel.PAYMENT }) {
            return manual("В счёте указана дата действия. Уточните дату ожидаемого поступления")
        }
        val date = dates.single()
        val choice = model.select(listOf(amount.quote), listOf(date.quote), authorization)
        // The model selects indexes. Neither its prose nor numbers enter the response.
        if (choice?.amountIndex != 0 || choice.dateIndex != 0) {
            return manual("Подтвердите сумму и дату по исходным строкам счёта")
        }
        return InvoiceExtractionResult(InvoiceExtractionMode.MODEL, amount, dates)
    }
}

@Tag(name = "Сценарий финансирования агента")
@RestController
@RequestMapping("/api/v1/agent/applications/{id}/invoice-extraction")
internal class AgentInvoiceExtractionController(
    private val agents: AgentApplicationService,
    private val extraction: InvoiceExtraction,
    private val scanner: DocumentScanner,
    private val throttle: DocumentUploadLimit,
    @Value("\${cabinet.storage.uploads-enabled:true}") private val uploadsEnabled: Boolean,
) {
    @Operation(summary = "Предложить сумму и дату из PDF-счёта с исходными строками (агентский черновик)")
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun extract(
        @PathVariable id: UUID,
        @RequestPart("file") file: MultipartFile,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): ResponseEntity<InvoiceExtractionResult> {
        val (claims, application) = agents.ownAgentApplication(id)
        if (application.status != ApplicationStatus.DRAFT) {
            throw ConflictException("Извлекать данные из счёта можно в черновике")
        }
        if (!uploadsEnabled) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Загрузка файлов временно приостановлена")
        if (file.isEmpty) throw BadRequestException("Выберите непустой PDF-счёт")
        if (file.size > InvoicePdfReader.MAX_FILE_BYTES) throw ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Для разбора выберите PDF-счёт до 2 МБ")
        throttle.acquire(claims.userId)
        val bytes = try {
            file.bytes
        } catch (_: IOException) {
            throw BadRequestException("Не удалось прочитать загруженный файл")
        }
        if (bytes.size.toLong() != file.size) throw BadRequestException("Размер счёта изменился при загрузке — выберите файл заново")
        scanner.scan(bytes)
        val result = extraction.extract(bytes, authorization)
        // Model/scanning can be slow. Do not return financial excerpts after access changes.
        val (_, current) = agents.ownAgentApplication(id)
        if (current.status != ApplicationStatus.DRAFT) throw ConflictException("Черновик изменился — обновите страницу")
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(result)
    }
}
