package ru.creditcabinet.documents

import io.grpc.ForwardingServerCall
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** Reject before requesting upload messages: per-connection limits alone are insufficient. */
@Component
class DocumentsAdmission : ServerInterceptor {
    private val slots = Semaphore(2)

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        if (!slots.tryAcquire()) {
            call.close(Status.RESOURCE_EXHAUSTED, Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        val released = AtomicBoolean()
        fun release() {
            if (released.compareAndSet(false, true)) slots.release()
        }
        val guarded = object : ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
            override fun close(status: Status, trailers: Metadata) {
                try {
                    super.close(status, trailers)
                } finally {
                    release()
                }
            }
        }
        return try {
            object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(next.startCall(guarded, headers)) {
                override fun onCancel() {
                    try {
                        super.onCancel()
                    } finally {
                        release()
                    }
                }
                override fun onComplete() {
                    try {
                        super.onComplete()
                    } finally {
                        release()
                    }
                }
            }
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }
}
