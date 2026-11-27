package ru.creditcabinet.document.infrastructure

import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.BadRequestException

interface DocumentStorageErrors {
    fun invalidDocument(message: String): RuntimeException

    fun unavailable(message: String): RuntimeException
}

internal object HttpDocumentStorageErrors : DocumentStorageErrors {
    override fun invalidDocument(message: String) = BadRequestException(message)

    override fun unavailable(message: String) = ApiException(HttpStatus.SERVICE_UNAVAILABLE, message)
}
