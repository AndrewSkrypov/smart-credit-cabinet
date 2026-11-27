package ru.creditcabinet.document.generated.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.document.generated.application.ArtifactDownload
import ru.creditcabinet.document.generated.application.ArtifactView
import ru.creditcabinet.document.generated.application.GeneratedArtifactRemote
import ru.creditcabinet.document.generated.application.remoteView
import ru.creditcabinet.document.infrastructure.DocumentsBoundary
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.documents.sha256
import java.util.UUID

@Component
class DocumentsGeneratedArtifactRemote(private val boundary: DocumentsBoundary) : GeneratedArtifactRemote {
    override val remote: Boolean get() = boundary.remote

    override fun scope(applicationId: UUID): DocumentScope = boundary.scope(applicationId)

    override fun prepare(records: List<ArtifactRecord>): List<ArtifactView> =
        boundary.rpc { records.map { boundary.client().prepare(it).remoteView() } }

    override fun list(scope: DocumentScope): List<ArtifactRecord> = boundary.rpc { boundary.client().artifacts(scope) }

    override fun retry(scope: DocumentScope, id: UUID): ArtifactView = boundary.rpc { boundary.client().retry(scope, id).remoteView() }

    override fun read(scope: DocumentScope, record: ArtifactRecord): ArtifactDownload = boundary.rpc {
        val bytes = boundary.client().artifactBytes(scope, record.id)
        require(bytes.size.toLong() == record.sizeBytes && sha256(bytes) == record.checksum)
        ArtifactDownload(record.remoteView(), bytes)
    }
}
