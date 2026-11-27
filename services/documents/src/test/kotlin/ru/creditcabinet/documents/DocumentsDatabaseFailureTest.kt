package ru.creditcabinet.documents

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.jdbc.CannotGetJdbcConnectionException
import ru.creditcabinet.contracts.documents.v1.Records
import ru.creditcabinet.contracts.documents.v1.Scope
import ru.creditcabinet.documents.application.DocumentsStore
import ru.creditcabinet.documents.grpc.DocumentsRpc
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals

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
            SQLException("Synthetic permanent error") to false,
        )
        cases.forEach { (cause, transient) ->
            val store = mock<DocumentsStore>()
            whenever(store.owned(any(), any<() -> Any>())).thenThrow(CannotGetJdbcConnectionException("Synthetic database failure", cause))
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
}
