package ru.creditcabinet.common.observability

import org.slf4j.MDC

/**
 * The id of the request being served: the trace id Micrometer Tracing puts into the logging
 * context. It is the same in the logs of every service a request passes through (the trace
 * context travels with internal calls), so one id finds the whole story.
 */
object RequestIds {
    const val HEADER = "X-Request-Id"

    fun current(): String? = MDC.get("traceId")
}
