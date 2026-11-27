package ru.creditcabinet.documents.infrastructure

import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.documents.DocumentConflict
import ru.creditcabinet.documents.application.DocumentsStorageFailure
import ru.creditcabinet.documents.application.DocumentsStorageRejected
import ru.creditcabinet.documents.application.DocumentsStorageUnavailable
import java.sql.SQLException
import java.sql.SQLTransientConnectionException

/** Also surrounds transaction begin/commit; it never starts or splits a transaction. */
internal fun <T> documentsDatabaseCall(action: () -> T): T = try {
    action()
} catch (failure: Exception) {
    // Preserve the existing transport precedence for business failures and avoid double wrapping.
    if (failure is DocumentsStorageFailure ||
        failure is DocumentConflict ||
        failure is ApiException ||
        failure is IllegalArgumentException ||
        failure is NoSuchElementException
    ) {
        throw failure
    }
    val sqlFailures = generateSequence<Throwable>(failure) { it.cause }.take(12).filterIsInstance<SQLException>().toList()
    if (sqlFailures.isEmpty()) throw failure
    val unavailable = sqlFailures.any {
        it.sqlState?.take(2) in setOf("08", "53") ||
            it.sqlState in setOf("57P01", "57P02", "57P03") ||
            (it is SQLTransientConnectionException && it.sqlState == null)
    }
    throw if (unavailable) DocumentsStorageUnavailable(failure) else DocumentsStorageRejected(failure)
}
