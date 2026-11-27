package ru.creditcabinet.document.domain

import java.io.InputStream

enum class StorageBackend { LOCAL, S3 }

/** A stored object opened for reading; the caller closes [content]. */
class StoredFile(val sizeBytes: Long, val content: InputStream)

/** Where file bytes live: a local volume under docker compose, S3-compatible storage elsewhere. */
interface FileStore {
    val writeBackend: StorageBackend get() = StorageBackend.LOCAL

    fun put(storedName: String, content: InputStream, sizeBytes: Long)

    fun put(storedName: String, content: InputStream, sizeBytes: Long, backend: StorageBackend) {
        require(backend == writeBackend) { "Неверное хранилище документа" }
        put(storedName, content, sizeBytes)
    }

    /** Null when no object with this name exists. */
    fun get(storedName: String): StoredFile?

    fun get(storedName: String, backend: StorageBackend): StoredFile? {
        require(backend == writeBackend) { "Неверное хранилище документа" }
        return get(storedName)
    }
}
