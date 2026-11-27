package ru.creditcabinet.document.infrastructure
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StoredFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Files in a local directory (a docker volume). Names are random and never come from a request. */
@Component
class LocalFileStore(@Value("\${cabinet.storage.local-dir:./uploads}") directory: String) : FileStore {
    private val root: Path = Path.of(directory).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
    }

    override fun put(storedName: String, content: InputStream, sizeBytes: Long) {
        val target = resolve(storedName)
        val temporary = Files.createTempFile(root, ".upload-", ".tmp")
        try {
            require(sizeBytes in 1..ru.creditcabinet.documents.MAX_DOCUMENT_BYTES)
            Files.newOutputStream(temporary).use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = content.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > sizeBytes) throw java.io.IOException("Document exceeds declared length")
                    output.write(buffer, 0, count)
                }
            }
            if (Files.size(temporary) != sizeBytes) throw java.io.IOException("Incomplete document write")
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun get(storedName: String): StoredFile? {
        val path = resolve(storedName)
        if (!Files.isRegularFile(path)) return null
        return StoredFile(Files.size(path), Files.newInputStream(path))
    }

    private fun resolve(name: String): Path {
        val path = root.resolve(name).normalize()
        require(path.parent == root) { "Invalid stored name" }
        return path
    }
}
