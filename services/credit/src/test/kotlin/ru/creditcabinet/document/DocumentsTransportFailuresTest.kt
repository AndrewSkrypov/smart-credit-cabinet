package ru.creditcabinet.document

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.grpc.Status
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
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
            assertFailsWith<ApiException> {
                boundary.rpc { throw Status.UNAVAILABLE.withCause(SSLHandshakeException("Synthetic certificate rejection")).asRuntimeException() }
            }
        assertEquals(HttpStatus.BAD_GATEWAY, failure.httpStatus)
    }

    @Test
    fun `authorization invalid payload and idempotency conflict do not become transient outages`() {
        assertFailsWith<NotFoundException> { boundary.rpc { throw Status.PERMISSION_DENIED.asRuntimeException() } }
        assertFailsWith<BadRequestException> { boundary.rpc { throw Status.INVALID_ARGUMENT.asRuntimeException() } }
        assertFailsWith<ConflictException> { boundary.rpc { throw Status.ALREADY_EXISTS.asRuntimeException() } }
    }
}
