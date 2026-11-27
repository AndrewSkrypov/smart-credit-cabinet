package ru.creditcabinet.documents.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactRenderer
import ru.creditcabinet.document.infrastructure.DocumentStorageErrors
import ru.creditcabinet.documents.StoreDocument
import ru.creditcabinet.documents.application.ArtifactOperations
import ru.creditcabinet.documents.application.DocumentsStore
import ru.creditcabinet.documents.application.FrozenArtifactInput
import ru.creditcabinet.documents.application.GenerateArtifact
import ru.creditcabinet.documents.application.RenderArtifact
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import java.time.Clock

@Configuration
class DocumentsConfiguration {
    @Bean
    fun documentsStorageErrors(): DocumentStorageErrors = NeutralDocumentStorageErrors

    @Bean
    fun documentsStore(
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
        tx: TransactionTemplate,
        files: FileStore,
        scanner: DocumentScanner,
        renderer: ArtifactRenderer,
        metadata: JdbcDocumentMetadata,
    ): DocumentsStore {
        val repository = JdbcDocumentsRepository(jdbc, mapper, tx)
        val input = FrozenArtifactInput { mapper.readValue<ArtifactInput>(it) }
        val lifecycle = ArtifactLifecycle(ArtifactRenderer.TEMPLATE_VERSION)
        val objects = NeutralDocumentFileStore(files)
        val scanning = NeutralDocumentScanner(scanner)
        val artifacts = ArtifactOperations(repository, repository, input, objects, lifecycle)
        val generation = GenerateArtifact(
            JdbcArtifactJobs(jdbc, mapper, tx, repository),
            input,
            RenderArtifact(renderer::render),
            objects,
            lifecycle,
            Clock.systemUTC(),
        )
        return DocumentsStore(repository, StoreDocument(metadata, objects, scanning), artifacts, generation, repository, objects)
    }
}

@Component
class DocumentsArtifactWorker(private val store: DocumentsStore) {
    @Scheduled(fixedDelayString = "\${cabinet.documents.poll-ms:1000}")
    fun generateOne() = store.generateOne()
}
