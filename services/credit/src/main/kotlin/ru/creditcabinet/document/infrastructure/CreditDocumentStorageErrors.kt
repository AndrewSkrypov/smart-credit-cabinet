package ru.creditcabinet.document.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CreditFailure

@Component
class CreditDocumentStorageErrors : DocumentStorageErrors {
    override fun invalidDocument(message: String) = CreditFailure.InvalidInput(message)

    override fun unavailable(message: String) = CreditFailure.DependencyUnavailable(message)
}
