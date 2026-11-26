package ru.creditcabinet.document.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentContent
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StoredFile

@Component
class DocumentContentAdapter(private val files: FileStore, private val boundary: DocumentsBoundary) : DocumentContent {
    override fun open(document: ApplicationDocument): StoredFile? = if (boundary.remote) {
        boundary.bytes(document)
    } else {
        files.get(document.storedName, document.storageBackend)
    }
}
