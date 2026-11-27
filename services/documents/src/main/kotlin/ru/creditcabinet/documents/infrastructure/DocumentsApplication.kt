package ru.creditcabinet.documents.infrastructure

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import ru.creditcabinet.document.generated.ArtifactRenderer
import ru.creditcabinet.document.infrastructure.ClamDocumentScanner
import ru.creditcabinet.document.infrastructure.LocalFileStore
import ru.creditcabinet.document.infrastructure.RoutingFileStore
import ru.creditcabinet.document.infrastructure.S3FileStore

@SpringBootApplication(scanBasePackages = ["ru.creditcabinet.documents"])
@EnableScheduling
@Import(ArtifactRenderer::class, ClamDocumentScanner::class, LocalFileStore::class, S3FileStore::class, RoutingFileStore::class)
class DocumentsApplication

fun main(args: Array<String>) {
    runApplication<DocumentsApplication>(*args)
}
