package ru.creditcabinet.document.generated.application

import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactPhase
import java.time.Instant
import java.util.UUID

enum class ArtifactStatus { PENDING, READY, ERROR }

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

data class ArtifactDownload(val metadata: ArtifactView, val bytes: ByteArray)
