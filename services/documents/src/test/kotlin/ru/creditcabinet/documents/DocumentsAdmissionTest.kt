package ru.creditcabinet.documents

import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import ru.creditcabinet.documents.grpc.DocumentsAdmission
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentsAdmissionTest {
    @Test
    fun `global admission rejects a third connection and cancellation releases exactly one slot`() {
        val admission = DocumentsAdmission()
        var admitted = 0
        val next = ServerCallHandler<Any, Any> { _, _ ->
            admitted++
            object : ServerCall.Listener<Any>() {}
        }
        val first = admission.interceptCall(mock<ServerCall<Any, Any>>(), Metadata(), next)
        admission.interceptCall(mock<ServerCall<Any, Any>>(), Metadata(), next)
        val rejected = mock<ServerCall<Any, Any>>()
        admission.interceptCall(rejected, Metadata(), next)
        val status = argumentCaptor<Status>()
        verify(rejected).close(status.capture(), any())
        assertEquals(Status.Code.RESOURCE_EXHAUSTED, status.firstValue.code)
        assertEquals(2, admitted)
        first.onCancel()
        first.onComplete()
        admission.interceptCall(mock<ServerCall<Any, Any>>(), Metadata(), next)
        assertEquals(3, admitted)
        admission.interceptCall(rejected, Metadata(), next)
        assertEquals(3, admitted)
    }
}
