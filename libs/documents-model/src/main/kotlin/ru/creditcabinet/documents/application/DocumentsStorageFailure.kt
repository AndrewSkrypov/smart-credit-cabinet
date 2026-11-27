package ru.creditcabinet.documents.application

/** Adapter-neutral failures. Causes stay available locally and are never wire descriptions. */
sealed class DocumentsStorageFailure(message: String, cause: Throwable) : RuntimeException(message, cause)

class DocumentsStorageUnavailable(cause: Throwable) : DocumentsStorageFailure("Document storage unavailable", cause)
class DocumentsStorageRejected(cause: Throwable) : DocumentsStorageFailure("Document storage operation failed", cause)
