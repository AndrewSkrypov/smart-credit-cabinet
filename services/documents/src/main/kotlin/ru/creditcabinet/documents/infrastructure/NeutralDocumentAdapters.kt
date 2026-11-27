package ru.creditcabinet.documents.infrastructure

import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.document.infrastructure.DocumentStorageErrors
import ru.creditcabinet.documents.application.DocumentsContentRejected
import ru.creditcabinet.documents.application.DocumentsStorageFailure
import ru.creditcabinet.documents.application.DocumentsStorageUnavailable
import java.io.InputStream
import java.io.OutputStream

/** Service-specific policy for shared adapters; other services keep their HTTP defaults. */
object NeutralDocumentStorageErrors : DocumentStorageErrors {
    override fun invalidDocument(message: String): DocumentsContentRejected = invalidDocument(message, null)
    override fun unavailable(message: String): DocumentsStorageUnavailable = unavailable(message, null)

    private fun invalidDocument(message: String, cause: Throwable?) = DocumentsContentRejected(message, cause)
    private fun unavailable(message: String, cause: Throwable?) = DocumentsStorageUnavailable(message, cause)

    internal fun fromHttp(failure: ApiException): DocumentsStorageFailure =
        if (failure.httpStatus == HttpStatus.SERVICE_UNAVAILABLE) {
            unavailable(failure.message, failure)
        } else {
            invalidDocument(failure.message, failure)
        }
}

/** Also protects custom/legacy adapters that do not accept the shared error factory. */
private fun <T> documentObjectCall(action: () -> T): T = try {
    action()
} catch (failure: ApiException) {
    throw NeutralDocumentStorageErrors.fromHttp(failure)
}

class NeutralDocumentScanner(private val delegate: DocumentScanner) : DocumentScanner {
    override fun scan(content: ByteArray) = documentObjectCall { delegate.scan(content) }
}

class NeutralDocumentFileStore(private val delegate: FileStore) : FileStore {
    override val writeBackend: StorageBackend get() = documentObjectCall { delegate.writeBackend }

    override fun put(storedName: String, content: InputStream, sizeBytes: Long) =
        documentObjectCall { delegate.put(storedName, content, sizeBytes) }

    override fun put(storedName: String, content: InputStream, sizeBytes: Long, backend: StorageBackend) =
        documentObjectCall { delegate.put(storedName, content, sizeBytes, backend) }

    override fun get(storedName: String): StoredFile? = documentObjectCall { delegate.get(storedName) }?.neutral()
    override fun get(storedName: String, backend: StorageBackend): StoredFile? = documentObjectCall { delegate.get(storedName, backend) }?.neutral()

    private fun StoredFile.neutral(): StoredFile = StoredFile(sizeBytes, NeutralDocumentStream(content))
}

/** No eager reads, buffering, retry or additional close; errors may occur after get returns. */
private class NeutralDocumentStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = documentObjectCall { delegate.read() }
    override fun read(bytes: ByteArray): Int = documentObjectCall { delegate.read(bytes) }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = documentObjectCall { delegate.read(bytes, offset, length) }
    override fun readAllBytes(): ByteArray = documentObjectCall { delegate.readAllBytes() }
    override fun readNBytes(length: Int): ByteArray = documentObjectCall { delegate.readNBytes(length) }
    override fun readNBytes(bytes: ByteArray, offset: Int, length: Int): Int = documentObjectCall { delegate.readNBytes(bytes, offset, length) }
    override fun skip(count: Long): Long = documentObjectCall { delegate.skip(count) }
    override fun skipNBytes(count: Long) = documentObjectCall { delegate.skipNBytes(count) }
    override fun available(): Int = documentObjectCall { delegate.available() }
    override fun mark(readlimit: Int) = documentObjectCall { delegate.mark(readlimit) }
    override fun reset() = documentObjectCall { delegate.reset() }
    override fun markSupported(): Boolean = documentObjectCall { delegate.markSupported() }
    override fun transferTo(output: OutputStream): Long = documentObjectCall { delegate.transferTo(output) }
    override fun close() = documentObjectCall { delegate.close() }
}
