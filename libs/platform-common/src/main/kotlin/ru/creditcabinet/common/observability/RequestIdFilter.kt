package ru.creditcabinet.common.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Returns the request id to the caller in [RequestIds.HEADER], so a user or support can
 * quote it and it can be found in the logs. Runs after the tracing filter has opened the
 * request's span (and put its trace id into the logging context).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        RequestIds.current()?.let { response.setHeader(RequestIds.HEADER, it) }
        chain.doFilter(request, response)
    }
}
