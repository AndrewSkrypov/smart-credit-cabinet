package ru.creditcabinet.document.generated

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.util.UUID

@Tag(name = "Файлы условий и графика платежей")
@RestController
@RequestMapping("/api/v1/applications/{id}/artifacts")
class ArtifactController(private val artifacts: GeneratedArtifacts) {
    @Operation(summary = "Готовность демонстрационных файлов договора и графика")
    @GetMapping
    fun list(@PathVariable id: UUID): List<ArtifactView> = artifacts.list(id)

    @Operation(summary = "Повторить создание файла после ошибки")
    @PostMapping("/{artifactId}/retry")
    fun retry(@PathVariable id: UUID, @PathVariable artifactId: UUID): ArtifactView = artifacts.retry(id, artifactId)

    @Operation(summary = "Скачать готовый файл: представителям компании и сотрудникам банка")
    @GetMapping("/{artifactId}/download")
    fun download(@PathVariable id: UUID, @PathVariable artifactId: UUID): ResponseEntity<ByteArray> {
        val file = artifacts.download(id, artifactId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(file.metadata.contentType))
            .contentLength(file.bytes.size.toLong())
            .cacheControl(CacheControl.noStore())
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.metadata.fileName, StandardCharsets.UTF_8).build().toString(),
            )
            .header("X-Content-Type-Options", "nosniff")
            .body(file.bytes)
    }
}
