package ru.creditcabinet.document.generated.application

import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.DocumentScope
import java.util.UUID

interface GeneratedArtifactRemote {
    val remote: Boolean

    fun scope(applicationId: UUID): DocumentScope

    fun prepare(records: List<ArtifactRecord>): List<ArtifactView>

    fun list(scope: DocumentScope): List<ArtifactRecord>

    fun retry(scope: DocumentScope, id: UUID): ArtifactView

    fun read(scope: DocumentScope, record: ArtifactRecord): ArtifactDownload
}

interface GeneratedArtifactRendering {
    val templateVersion: Int

    fun title(kind: ArtifactKind, input: ArtifactInput): String

    fun render(kind: ArtifactKind, input: ArtifactInput): ByteArray
}

interface GeneratedArtifactFailures {
    fun preparation(applicationId: UUID, failure: Throwable)

    fun generation(artifactId: UUID, failure: Throwable)
}

class GeneratedOriginalUnavailable : RuntimeException("Исходный файл временно недоступен. Попробуйте позже.")

internal fun ArtifactRecord.remoteView(): ArtifactView = ArtifactView(
    id, ArtifactKind.valueOf(kind), ArtifactPhase.valueOf(phase), title, fileName, contentType,
    ArtifactStatus.valueOf(status), sizeBytes, checksum, createdAt, readyAt,
    if (status == "ERROR") "Не удалось подготовить файл. Можно повторить создание." else null,
    if (status == "READY") "/api/v1/applications/${scope.applicationId}/artifacts/$id/download" else null,
)
