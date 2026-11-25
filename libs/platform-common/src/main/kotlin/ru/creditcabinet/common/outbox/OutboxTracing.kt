package ru.creditcabinet.common.outbox

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.slf4j.MDC

/** Persist W3C trace context separately so delivery metadata never changes idempotent payload matching. */
class OutboxTracing(private val tracer: Tracer, private val propagator: Propagator) {
    fun traceParent(): String? {
        val span = tracer.currentSpan() ?: return null
        val carrier = mutableMapOf<String, String>()
        propagator.inject(span.context(), carrier) { target, key, value -> target?.put(key, value) }
        return carrier["traceparent"]?.takeIf(TRACE_PARENT::matches)
    }

    fun deliver(traceParent: String?, channel: String, block: () -> Unit) {
        val builder = if (traceParent != null && TRACE_PARENT.matches(traceParent)) {
            propagator.extract(traceParent) { source, key -> source.takeIf { key == "traceparent" } }
        } else {
            tracer.spanBuilder().setNoParent()
        }
        val span = builder.name("cabinet.outbox.deliver").tag("cabinet.outbox.channel", channel).start()
        try {
            tracer.withSpan(span).use {
                MDC.putCloseable("requestId", span.context().traceId()).use { block() }
            }
        } finally {
            span.end()
        }
    }

    private companion object {
        val TRACE_PARENT = Regex("[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
    }
}
