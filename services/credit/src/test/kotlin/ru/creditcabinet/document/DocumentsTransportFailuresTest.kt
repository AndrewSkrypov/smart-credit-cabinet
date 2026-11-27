package ru.creditcabinet.document

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.grpc.Status
import org.mockito.kotlin.mock
import org.springframework.mock.web.MockHttpServletRequest
import ru.creditcabinet.application.application.CreditFailure.Conflict
import ru.creditcabinet.application.application.CreditFailure.DependencyFailure
import ru.creditcabinet.application.application.CreditFailure.InvalidInput
import ru.creditcabinet.application.application.CreditFailure.NotFound
import ru.creditcabinet.application.controller.CreditBoundaryErrorHandler
import ru.creditcabinet.common.exception.GlobalExceptionHandler
import ru.creditcabinet.document.application.DocumentsUnavailable
import ru.creditcabinet.document.infrastructure.DocumentsBoundary
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentsTransportFailuresTest {
    private val boundary = DocumentsBoundary(mock(), mock(), jacksonObjectMapper(), "remote", 2, "127.0.0.1", 1, "", "", "", 1000)

    @Test
    fun `network outage is transient while TLS trust failure is a permanent configuration error`() {
        assertFailsWith<DocumentsUnavailable> {
            boundary.rpc { throw Status.UNAVAILABLE.withCause(java.io.IOException("Synthetic refused connection")).asRuntimeException() }
        }
        val failure =
            assertFailsWith<DependencyFailure> {
                boundary.rpc { throw Status.UNAVAILABLE.withCause(SSLHandshakeException("Synthetic certificate rejection")).asRuntimeException() }
            }
        val request = MockHttpServletRequest("GET", "/api/v1/applications/id/documents/id")
        val response = CreditBoundaryErrorHandler(GlobalExceptionHandler()).applicationFailure(failure, request)
        assertEquals(502, response.statusCode.value())
        val body = checkNotNull(response.body)
        assertEquals("Ошибка доверия сервиса документов", body.message)
        assertEquals(request.requestURI, body.path)
    }

    @Test
    fun `authorization invalid payload and idempotency conflict do not become transient outages`() {
        assertFailsWith<NotFound> { boundary.rpc { throw Status.PERMISSION_DENIED.asRuntimeException() } }
        assertFailsWith<InvalidInput> { boundary.rpc { throw Status.INVALID_ARGUMENT.asRuntimeException() } }
        assertFailsWith<Conflict> { boundary.rpc { throw Status.ALREADY_EXISTS.asRuntimeException() } }
    }
}
