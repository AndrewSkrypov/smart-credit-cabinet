package ru.creditcabinet.financing.invoice.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.document.controller.toDocumentUpload
import ru.creditcabinet.financing.invoice.application.ExtractAgentInvoice
import ru.creditcabinet.financing.invoice.application.InvoiceExtractionResult
import java.util.UUID

@Tag(name = "Сценарий финансирования агента")
@RestController
@RequestMapping("/api/v1/agent/applications/{id}/invoice-extraction")
internal class AgentInvoiceExtractionController(private val extraction: ExtractAgentInvoice) {
    @Operation(summary = "Предложить сумму и дату из PDF-счёта с исходными строками (агентский черновик)")
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun extract(
        @PathVariable id: UUID,
        @RequestPart("file") file: MultipartFile,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): ResponseEntity<InvoiceExtractionResult> {
        val result = extraction.extract(id, file.toDocumentUpload(), authorization)
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(result)
    }
}
