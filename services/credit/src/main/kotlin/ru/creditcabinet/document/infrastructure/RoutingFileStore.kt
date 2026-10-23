package ru.creditcabinet.document.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import java.io.InputStream

/** The write mode never changes the location of a document already recorded in PostgreSQL. */
@Component
@Primary
class RoutingFileStore(
    private val local: LocalFileStore,
    private val s3: S3FileStore,
    @Value("\${cabinet.storage.mode:local}") mode: String,
) : FileStore {
    override val writeBackend: StorageBackend = when (mode.lowercase()) {
        "local" -> StorageBackend.LOCAL
        "s3" -> StorageBackend.S3
        else -> throw IllegalArgumentException("Хранилище документов — local или s3")
    }

    override fun put(storedName: String, content: InputStream, sizeBytes: Long) = adapter(writeBackend).put(storedName, content, sizeBytes)

    override fun get(storedName: String): StoredFile? = get(storedName, writeBackend)

    override fun get(storedName: String, backend: StorageBackend): StoredFile? = adapter(backend).get(storedName)

    private fun adapter(backend: StorageBackend): FileStore = when (backend) {
        StorageBackend.LOCAL -> local
        StorageBackend.S3 -> s3
    }
}
