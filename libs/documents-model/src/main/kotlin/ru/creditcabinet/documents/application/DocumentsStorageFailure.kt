package ru.creditcabinet.documents.application

/** Adapter-neutral failures. Causes stay available locally and are never wire descriptions. */
sealed class DocumentsStorageFailure(message: String, cause: Throwable?) : RuntimeException(message, cause)

class DocumentsStorageUnavailable : DocumentsStorageFailure {
    constructor(cause: Throwable) : super("Document storage unavailable", cause)
    constructor(message: String, cause: Throwable? = null) : super(message, cause)
}
class DocumentsStorageRejected(cause: Throwable) : DocumentsStorageFailure("Document storage operation failed", cause)
class DocumentsContentRejected(message: String, cause: Throwable? = null) : DocumentsStorageFailure(message, cause)
