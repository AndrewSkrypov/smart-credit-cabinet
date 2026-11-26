package ru.creditcabinet.document.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.document.application.ArtifactUnavailableException
import ru.creditcabinet.document.application.GeneratedArtifactOperations
import ru.creditcabinet.document.domain.ArtifactKind
import ru.creditcabinet.document.domain.ArtifactPhase
import ru.creditcabinet.document.domain.ArtifactRecord
import ru.creditcabinet.document.domain.ArtifactStatus
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

data class ArtifactView(
    val id: UUID,
    val kind: ArtifactKind,
    val phase: ArtifactPhase,
    val title: String,
    val fileName: String,
    val contentType: String,
    val status: ArtifactStatus,
    val sizeBytes: Long?,
    val checksum: String?,
    val createdAt: Instant,
    val readyAt: Instant?,
    val errorMessage: String?,
    val downloadUrl: String?,
)

@Tag(name = "Файлы условий и графика платежей")
@RestController
@RequestMapping("/api/v1/applications/{id}/artifacts")
class ArtifactController(private val artifacts: GeneratedArtifactOperations) {
    @Operation(summary = "Готовность демонстрационных файлов договора и графика")
    @GetMapping
    fun list(@PathVariable id: UUID): List<ArtifactView> = artifacts.list(id).map(ArtifactRecord::toView)

    @Operation(summary = "Повторить создание файла после ошибки")
    @PostMapping("/{artifactId}/retry")
    fun retry(@PathVariable id: UUID, @PathVariable artifactId: UUID): ArtifactView = artifacts.retry(id, artifactId).toView()

    @Operation(summary = "Скачать готовый файл: представителям компании и сотрудникам банка")
    @GetMapping("/{artifactId}/download")
    fun download(@PathVariable id: UUID, @PathVariable artifactId: UUID): ResponseEntity<ByteArray> {
        val file = try {
            artifacts.download(id, artifactId)
        } catch (failure: ArtifactUnavailableException) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, failure.message)
        }
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

private fun ArtifactRecord.toView() = ArtifactView(
    id, kind, phase, title, fileName, contentType, status, sizeBytes, checksum, createdAt, readyAt,
    if (status == ArtifactStatus.ERROR) "Не удалось подготовить файл. Можно повторить создание." else null,
    if (status == ArtifactStatus.READY) "/api/v1/applications/${group.applicationId}/artifacts/$id/download" else null,
)
