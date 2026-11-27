package ru.creditcabinet.financing.invoice.application

import org.springframework.stereotype.Service
import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.document.application.DocumentUpload
import ru.creditcabinet.document.application.DocumentUploadLimit
import ru.creditcabinet.document.application.DocumentUploadSettings
import ru.creditcabinet.document.application.DocumentUploadsDisabled
import ru.creditcabinet.document.domain.DocumentScanner
import java.io.IOException
import java.util.UUID

class InvoicePdfTooLarge(message: String) : RuntimeException(message)

@Service
internal class ExtractAgentInvoice(
    private val agents: AgentApplicationService,
    private val extraction: InvoiceExtraction,
    private val scanner: DocumentScanner,
    private val throttle: DocumentUploadLimit,
    private val uploads: DocumentUploadSettings,
) {
    fun extract(id: UUID, file: DocumentUpload, authorization: String): InvoiceExtractionResult {
        val (claims, application) = agents.ownAgentApplication(id)
        if (application.status != ApplicationStatus.DRAFT) {
            throw ConflictException("Извлекать данные из счёта можно в черновике")
        }
        if (!uploads.enabled) throw DocumentUploadsDisabled("Загрузка файлов временно приостановлена")
        if (file.size == 0L) throw BadRequestException("Выберите непустой PDF-счёт")
        if (file.size > InvoiceTextReader.MAX_FILE_BYTES) throw InvoicePdfTooLarge("Для разбора выберите PDF-счёт до 2 МБ")
        throttle.acquire(claims.userId)
        val bytes = try {
            file.read()
        } catch (_: IOException) {
            throw BadRequestException("Не удалось прочитать загруженный файл")
        }
        if (bytes.size.toLong() != file.size) throw BadRequestException("Размер счёта изменился при загрузке — выберите файл заново")
        scanner.scan(bytes)
        val result = extraction.extract(bytes, authorization)
        // Model/scanning can be slow. Do not return financial excerpts after access changes.
        val (_, current) = agents.ownAgentApplication(id)
        if (current.status != ApplicationStatus.DRAFT) throw ConflictException("Черновик изменился — обновите страницу")
        return result
    }
}
