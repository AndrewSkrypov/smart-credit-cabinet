package ru.creditcabinet.documents

import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.document.infrastructure.DocumentStorageErrors
import ru.creditcabinet.documents.application.DocumentsContentRejected
import ru.creditcabinet.documents.application.DocumentsStorageFailure
import ru.creditcabinet.documents.application.DocumentsStorageUnavailable
import ru.creditcabinet.documents.infrastructure.NeutralDocumentFileStore
import ru.creditcabinet.documents.infrastructure.NeutralDocumentScanner
import ru.creditcabinet.documents.infrastructure.NeutralDocumentStorageErrors
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class DocumentsObjectBoundaryTest {
    @Test
    fun `shared factory emits neutral errors with the exact business message`() {
        val factory: DocumentStorageErrors = NeutralDocumentStorageErrors
        val invalidMessage = "Файл содержит угрозу и не сохранён. Выберите безопасный файл"
        val unavailableMessage = "Проверка файлов временно недоступна. Попробуйте загрузить файл позже"
        val invalid = assertIs<DocumentsContentRejected>(factory.invalidDocument(invalidMessage))
        val unavailable = assertIs<DocumentsStorageUnavailable>(factory.unavailable(unavailableMessage))
        assertEquals(invalidMessage, invalid.message)
        assertEquals(unavailableMessage, unavailable.message)
        assertNull(invalid.cause)
        assertNull(unavailable.cause)
    }

    @Test
    fun `every file-store overload and backend property normalizes legacy HTTP failures`() {
        for (status in listOf(
            HttpStatus.SERVICE_UNAVAILABLE,
            HttpStatus.BAD_REQUEST,
            HttpStatus.FORBIDDEN,
            HttpStatus.CONFLICT,
            HttpStatus.INTERNAL_SERVER_ERROR,
        )) {
            val original = ApiException(status, "Original object-store message")
            val events = mutableListOf<String>()
            val port = NeutralDocumentFileStore(object : FileStore {
                override val writeBackend: StorageBackend get() {
                    events += "backend"
                    throw original
                }
                override fun put(storedName: String, content: InputStream, sizeBytes: Long) {
                    events += "put"
                    throw original
                }
                override fun put(
                    storedName: String,
                    content: InputStream,
                    sizeBytes: Long,
                    backend: StorageBackend,
                ) {
                    events += "put-explicit"
                    throw original
                }
                override fun get(storedName: String): StoredFile? {
                    events += "get"
                    throw original
                }
                override fun get(storedName: String, backend: StorageBackend): StoredFile? {
                    events += "get-explicit"
                    throw original
                }
            })
            val operations = listOf<() -> Any?>(
                { port.writeBackend },
                { port.put("test.pdf", byteArrayOf(1).inputStream(), 1) },
                { port.put("test.pdf", byteArrayOf(1).inputStream(), 1, StorageBackend.S3) },
                { port.get("test.pdf") },
                { port.get("test.pdf", StorageBackend.S3) },
            )
            for (operation in operations) assertNeutral(original, operation)
            assertEquals(listOf("backend", "put", "put-explicit", "get", "get-explicit"), events)
        }
    }

    @Test
    fun `every returned-stream operation normalizes late failures without an eager read or close`() {
        for (status in listOf(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.BAD_REQUEST)) {
            val original = ApiException(status, "Original late stream message")
            val events = mutableListOf<String>()
            fun fail(name: String): Nothing {
                events += name
                throw original
            }
            val raw = object : InputStream() {
                override fun read(): Int = fail("read")
                override fun read(b: ByteArray): Int = fail("read-array")
                override fun read(b: ByteArray, off: Int, len: Int): Int = fail("read-slice")
                override fun readAllBytes(): ByteArray = fail("read-all")
                override fun readNBytes(len: Int): ByteArray = fail("read-n")
                override fun readNBytes(b: ByteArray, off: Int, len: Int): Int = fail("read-n-slice")
                override fun skip(n: Long): Long = fail("skip")
                override fun skipNBytes(n: Long): Unit = fail("skip-n")
                override fun available(): Int = fail("available")
                override fun mark(readlimit: Int): Unit = fail("mark")
                override fun reset(): Unit = fail("reset")
                override fun markSupported(): Boolean = fail("mark-supported")
                override fun transferTo(out: OutputStream): Long = fail("transfer")
                override fun close(): Unit = fail("close")
            }
            val stream = NeutralDocumentFileStore(Files(raw)).get("test.pdf")!!.content
            assertEquals(emptyList(), events)
            val operations = listOf<() -> Any?>(
                { stream.read() }, { stream.read(ByteArray(2)) }, { stream.read(ByteArray(2), 0, 2) },
                { stream.readAllBytes() }, { stream.readNBytes(2) }, { stream.readNBytes(ByteArray(2), 0, 2) },
                { stream.skip(2) }, { stream.skipNBytes(2) }, { stream.available() },
                { stream.mark(2) }, { stream.reset() }, { stream.markSupported() },
                { stream.transferTo(ByteArrayOutputStream()) }, { stream.close() },
            )
            for (operation in operations) assertNeutral(original, operation)
            assertEquals(
                listOf(
                    "read", "read-array", "read-slice", "read-all", "read-n", "read-n-slice", "skip", "skip-n",
                    "available", "mark", "reset", "mark-supported", "transfer", "close",
                ),
                events,
            )
        }
    }

    @Test
    fun `read bound mark reset close metadata and null semantics remain delegated`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        var closes = 0
        val input = object : ByteArrayInputStream(bytes) {
            override fun close() {
                closes++
                super.close()
            }
        }
        val files = Files(input)
        val port = NeutralDocumentFileStore(files)
        val stored = port.get("test.pdf", StorageBackend.S3)!!
        assertEquals(123L, stored.sizeBytes)
        assertEquals(StorageBackend.S3, files.backend)
        assertEquals(4, input.available())
        assertContentEquals(byteArrayOf(1, 2), stored.content.readNBytes(2))
        assertEquals(2, input.available())
        stored.content.mark(2)
        assertEquals(3, stored.content.read())
        stored.content.reset()
        assertEquals(2, input.available())
        stored.content.close()
        assertEquals(1, closes)
        assertNull(NeutralDocumentFileStore(Files(null)).get("missing.pdf"))
    }

    @Test
    fun `late read and close preserve primary failure suppression and fail before metadata publication`() {
        val readFailure = ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Original late read failure")
        val closeFailure = BadRequestException("Original close failure")
        var closes = 0
        val raw = object : InputStream() {
            override fun read(): Int = throw readFailure
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw readFailure
            override fun close() {
                closes++
                throw closeFailure
            }
        }
        val metadata = Metadata()
        val bytes = "%PDF-1.4\noriginal\n%%EOF".toByteArray()
        val failure = assertFailsWith<DocumentsStorageUnavailable> {
            StoreDocument(metadata, NeutralDocumentFileStore(Files(raw)), DocumentScanner {}).execute(record(bytes), bytes)
        }
        assertSame(readFailure, failure.cause)
        assertSame(closeFailure, assertIs<DocumentsContentRejected>(failure.suppressed.single()).cause)
        assertEquals(1, closes)
        assertEquals(0, metadata.inserts)
    }

    @Test
    fun `scanner unavailability and invalid content stay neutral and fail closed before object access`() {
        for (original in listOf(
            ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Original unavailable scanner"),
            BadRequestException("Original invalid content"),
        )) {
            val scanner = NeutralDocumentScanner(DocumentScanner { throw original })
            val metadata = Metadata()
            val files = Files(null)
            val bytes = "%PDF-1.4\noriginal\n%%EOF".toByteArray()
            assertNeutral(original) { StoreDocument(metadata, NeutralDocumentFileStore(files), scanner).execute(record(bytes), bytes) }
            assertEquals(1, metadata.reservations)
            assertEquals(0, metadata.inserts)
            assertEquals(0, files.gets)
            assertEquals(0, files.puts)
        }
    }

    @Test
    fun `neutral unexpected and fatal adapter failures retain identity`() {
        for (failure in listOf(
            DocumentsStorageUnavailable("Already neutral"),
            DocumentsContentRejected("Already invalid"),
            IOException("Unexpected IO"),
            AssertionError("Fatal"),
        )) {
            val scanner = NeutralDocumentScanner(DocumentScanner { throw failure })
            assertSame(failure, assertFailsWith<Throwable> { scanner.scan(byteArrayOf(1)) })
        }
    }

    @Test
    fun `successful scan gets the original bytes and matching upload replay does not rescan`() {
        val bytes = "%PDF-1.4\noriginal\n%%EOF".toByteArray()
        var scanned: ByteArray? = null
        NeutralDocumentScanner(DocumentScanner { scanned = it }).scan(bytes)
        assertSame(bytes, scanned)
        val record = record(bytes)
        val metadata = Metadata(record)
        val files = Files(null)
        StoreDocument(
            metadata,
            NeutralDocumentFileStore(files),
            NeutralDocumentScanner(
                DocumentScanner {
                    error("Replay must not scan")
                },
            ),
        ).execute(record, bytes)
        assertEquals(0, files.gets)
        assertEquals(0, files.puts)
        assertEquals(0, metadata.inserts)
    }

    private fun assertNeutral(original: ApiException, action: () -> Any?) {
        val failure = assertFailsWith<DocumentsStorageFailure> { action() }
        if (original.httpStatus ==
            HttpStatus.SERVICE_UNAVAILABLE
        ) {
            assertIs<DocumentsStorageUnavailable>(failure)
        } else {
            assertIs<DocumentsContentRejected>(failure)
        }
        assertEquals(original.message, failure.message)
        assertSame(original, failure.cause)
    }

    private fun record(bytes: ByteArray) = DocumentRecord(
        UUID.randomUUID(), DocumentScope(UUID.randomUUID(), UUID.randomUUID()), "OTHER", "test.pdf", "test.pdf", bytes.size.toLong(),
        UUID.randomUUID(), Instant.EPOCH, "BUSINESS", "LOCAL", sha256(bytes), false,
    )

    private class Metadata(val existing: DocumentRecord? = null) : DocumentMetadata {
        var reservations = 0
        var inserts = 0
        override fun find(id: UUID): DocumentRecord? = existing
        override fun reserve(record: DocumentRecord) {
            reservations++
        }
        override fun insert(record: DocumentRecord) {
            inserts++
        }
    }

    private class Files(private val input: InputStream?) : FileStore {
        var backend: StorageBackend? = null
        var gets = 0
        var puts = 0
        override fun put(storedName: String, content: InputStream, sizeBytes: Long) {
            puts++
        }
        override fun get(storedName: String): StoredFile? {
            gets++
            return input?.let { StoredFile(123, it) }
        }
        override fun get(storedName: String, backend: StorageBackend): StoredFile? {
            this.backend = backend
            return get(storedName)
        }
    }
}
