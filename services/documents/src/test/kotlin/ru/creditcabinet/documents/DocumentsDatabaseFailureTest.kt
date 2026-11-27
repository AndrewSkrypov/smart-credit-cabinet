package ru.creditcabinet.documents

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.contracts.documents.v1.Records
import ru.creditcabinet.contracts.documents.v1.Scope
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.generated.ArtifactRenderer
import ru.creditcabinet.documents.application.DocumentsStorageRejected
import ru.creditcabinet.documents.application.DocumentsStorageUnavailable
import ru.creditcabinet.documents.application.DocumentsStore
import ru.creditcabinet.documents.grpc.DocumentsRpc
import ru.creditcabinet.documents.infrastructure.DocumentsConfiguration
import ru.creditcabinet.documents.infrastructure.JdbcDocumentMetadata
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class DocumentsDatabaseFailureTest {
    @Test
    fun `database shutdown and connection capacity are transient while auth and schema failures remain permanent`() {
        val cases = listOf(
            SQLException("Synthetic disconnect", "08006") to true,
            SQLException("Synthetic capacity", "53300") to true,
            SQLException("Synthetic shutdown", "57P01") to true,
            SQLException("Synthetic crash recovery", "57P02") to true,
            SQLException("Synthetic startup", "57P03") to true,
            SQLTransientConnectionException("Synthetic pool acquisition timeout") to true,
            SQLException("Synthetic credentials error", "28P01") to false,
            SQLException("Synthetic schema error", "42P01") to false,
            SQLException("Synthetic permission error", "42501") to false,
            SQLException("Synthetic serialization error", "40001") to false,
            SQLException("Synthetic deadlock", "40P01") to false,
            SQLException("Synthetic permanent error") to false,
        )
        cases.forEach { (cause, transient) ->
            val jdbc = mock<JdbcTemplate>()
            val tx = mock<TransactionTemplate>()
            whenever(tx.execute(any<TransactionCallback<Any>>())).thenThrow(CannotGetJdbcConnectionException("Synthetic database failure", cause))
            val mapper = jacksonObjectMapper()
            val store = DocumentsConfiguration().documentsStore(
                jdbc,
                mapper,
                tx,
                mock<FileStore>(),
                DocumentScanner {},
                mock<ArtifactRenderer>(),
                JdbcDocumentMetadata(jdbc, mapper),
            )
            var actual: Status.Code? = null
            DocumentsRpc(store, jacksonObjectMapper()).listDocuments(
                Scope.getDefaultInstance(),
                object : StreamObserver<Records> {
                    override fun onNext(value: Records) = error("Unexpected successful database operation")
                    override fun onCompleted() = error("Unexpected database completion")
                    override fun onError(failure: Throwable) {
                        actual = Status.fromThrowable(failure).code
                    }
                },
            )
            assertEquals(if (transient) Status.Code.UNAVAILABLE else Status.Code.FAILED_PRECONDITION, actual)
        }
    }

    @Test
    fun `neutral storage failures and not-ready exceptions preserve statuses without exposing descriptions`() {
        val cause = IllegalStateException("Synthetic private SQL parameters must not cross the wire")
        for ((failure, code) in listOf(
            DocumentsStorageUnavailable(cause) to Status.Code.UNAVAILABLE,
            DocumentsStorageRejected(cause) to Status.Code.FAILED_PRECONDITION,
            IllegalStateException("Artifact not ready") to Status.Code.FAILED_PRECONDITION,
        )) {
            val store = mock<DocumentsStore>()
            whenever(store.owned(any(), any<() -> Any>())).thenThrow(failure)
            var observed: Throwable? = null
            DocumentsRpc(store, jacksonObjectMapper()).listDocuments(
                Scope.getDefaultInstance(),
                object : StreamObserver<Records> {
                    override fun onNext(value: Records) = error("Unexpected successful operation")
                    override fun onCompleted() = error("Unexpected completion")
                    override fun onError(failure: Throwable) {
                        observed = failure
                    }
                },
            )
            val actual = observed ?: error("Missing failure")
            assertEquals(code, Status.fromThrowable(actual).code)
            assertNull(Status.fromThrowable(actual).description)
            assertFalse(actual.message.orEmpty().contains("private SQL"))
            assertSame(failure, actual.cause)
            if (failure is ru.creditcabinet.documents.application.DocumentsStorageFailure) assertSame(cause, failure.cause)
        }
    }
}
