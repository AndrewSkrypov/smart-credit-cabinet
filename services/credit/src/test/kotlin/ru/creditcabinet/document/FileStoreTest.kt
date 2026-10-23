package ru.creditcabinet.document

import org.junit.jupiter.api.io.TempDir
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.infrastructure.LocalFileStore
import ru.creditcabinet.document.infrastructure.RoutingFileStore
import ru.creditcabinet.document.infrastructure.S3FileStore
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileStoreTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `a failed stream or wrong length never publishes a partial file or replaces an existing one`() {
        val store = LocalFileStore(directory.toString())
        val bytes = "Complete original document".toByteArray()
        store.put("existing.pdf", bytes.inputStream(), bytes.size.toLong())
        val broken = object : InputStream() {
            var count = 0
            override fun read(): Int = if (count++ < 5) 65 else throw IOException("Synthetic interrupted source")
        }
        assertFailsWith<IOException> { store.put("existing.pdf", broken, 20) }
        store.get("existing.pdf")!!.content.use { assertContentEquals(bytes, it.readAllBytes()) }
        assertFailsWith<IOException> { store.put("partial.pdf", byteArrayOf(1, 2).inputStream(), 20) }
        assertFalse(Files.exists(directory.resolve("partial.pdf")))
        Files.list(directory).use { assertEquals(listOf("existing.pdf"), it.map { path -> path.fileName.toString() }.toList()) }
    }

    @Test
    fun `a write mode switch does not redirect reads of legacy local documents to S3`() {
        val local = LocalFileStore(directory.toString())
        val bytes = "Legacy local document".toByteArray()
        local.put("legacy.pdf", bytes.inputStream(), bytes.size.toLong())
        // An unreachable S3 endpoint proves the recorded LOCAL backend is actually used.
        val remote = S3FileStore("http://127.0.0.1:1", "us-east-1", "cabinet-documents", "synthetic-access", "synthetic-secret")
        val store = RoutingFileStore(local, remote, "s3")
        assertEquals(StorageBackend.S3, store.writeBackend)
        store.get("legacy.pdf", StorageBackend.LOCAL)!!.content.use { assertContentEquals(bytes, it.readAllBytes()) }
        assertFailsWith<IllegalArgumentException> { local.get("../other.pdf") }
    }
}
