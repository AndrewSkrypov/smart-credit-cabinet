package ru.creditcabinet.documents

import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.documents.application.DocumentsStorageRejected
import ru.creditcabinet.documents.application.DocumentsStorageUnavailable
import ru.creditcabinet.documents.infrastructure.documentsDatabaseCall
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Driver classification without a database; real connection/transaction gates stay separate. */
class DocumentsDatabaseBoundaryTest {
    @Test
    fun `connection resource capacity and shutdown states are unavailable and retain the original wrapper`() {
        for (state in listOf("08006", "08001", "53000", "53100", "53300", "57P01", "57P02", "57P03")) {
            val original = RuntimeException("Synthetic JDBC wrapper", SQLException("Synthetic private parameters", state))
            val failure = assertFailsWith<DocumentsStorageUnavailable> { documentsDatabaseCall { throw original } }
            assertSame(original, failure.cause)
            assertEquals("Document storage unavailable", failure.message)
        }
    }

    @Test
    fun `auth schema permission transaction constraint and unknown SQL failures remain permanent`() {
        for (state in listOf("28P01", "42P01", "42501", "40001", "40P01", "23505", "57P04", "", null)) {
            val original = RuntimeException("Synthetic JDBC wrapper", SQLException("Synthetic private parameters", state))
            val failure = assertFailsWith<DocumentsStorageRejected> { documentsDatabaseCall { throw original } }
            assertSame(original, failure.cause)
            assertEquals("Document storage operation failed", failure.message)
        }
    }

    @Test
    fun `pool timeout is transient only when its SQLSTATE is absent or independently transient`() {
        val absent = SQLTransientConnectionException("Synthetic pool timeout")
        assertSame(absent, assertFailsWith<DocumentsStorageUnavailable> { documentsDatabaseCall { throw absent } }.cause)
        val denied = SQLTransientConnectionException("Synthetic pool auth failure", "28P01")
        assertSame(denied, assertFailsWith<DocumentsStorageRejected> { documentsDatabaseCall { throw denied } }.cause)
    }

    @Test
    fun `cause traversal preserves the existing twelve throwable bound`() {
        var inside: Throwable = SQLException("Synthetic disconnect", "08006")
        repeat(11) { inside = RuntimeException("wrapper", inside) }
        assertSame(inside, assertFailsWith<DocumentsStorageUnavailable> { documentsDatabaseCall { throw inside } }.cause)
        val outside = RuntimeException("twelfth wrapper", inside)
        assertSame(outside, assertFailsWith<RuntimeException> { documentsDatabaseCall { throw outside } })
    }

    @Test
    fun `business and already neutral failures keep their identity and transport precedence`() {
        val sql = SQLException("Synthetic disconnect", "08006")
        for (failure in listOf(
            DocumentConflict().apply { initCause(sql) },
            IllegalArgumentException("Invalid document", sql),
            NoSuchElementException("Missing document").apply { initCause(sql) },
            ApiException(HttpStatus.BAD_REQUEST, "Business failure"),
            DocumentsStorageUnavailable(sql),
            DocumentsStorageRejected(sql),
        )) {
            assertSame(failure, assertFailsWith<Exception> { documentsDatabaseCall { throw failure } })
        }
    }

    @Test
    fun `unexpected exceptions and errors pass through unchanged for diagnostics and framework handling`() {
        val unexpected = IllegalStateException("Synthetic unexpected failure")
        assertSame(unexpected, assertFailsWith<IllegalStateException> { documentsDatabaseCall { throw unexpected } })
        val fatal = AssertionError("Synthetic fatal failure")
        assertSame(fatal, assertFailsWith<AssertionError> { documentsDatabaseCall { throw fatal } })
    }

    @Test
    fun `the boundary includes failure after action completion without replaying the action`() {
        val events = mutableListOf<String>()
        val commit = SQLException("Synthetic commit disconnect", "08006")
        val failure = assertFailsWith<DocumentsStorageUnavailable> {
            documentsDatabaseCall {
                events += "action completed"
                throw commit
            }
        }
        assertSame(commit, failure.cause)
        assertEquals(listOf("action completed"), events)
    }

    @Test
    fun `successful and nullable return values are unchanged`() {
        val expected = Any()
        assertSame(expected, documentsDatabaseCall { expected })
        assertEquals(null, documentsDatabaseCall { null })
    }
}
