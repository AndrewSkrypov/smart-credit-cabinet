package ru.creditcabinet.agent.infrastructure

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.web.filter.OncePerRequestFilter

@Configuration(proxyBeanMethods = false)
@Profile("agent-mcp-remote")
internal class McpCreditConnectorBoundary {
    @Bean
    fun mcpCreditConnectorFilter(@Value("\${cabinet.mcp.credit-port:8443}") port: Int): FilterRegistrationBean<OncePerRequestFilter> {
        val remotePath = Regex(
            "/internal/mcp/credit/(?:priorities|applications/[0-9a-fA-F-]{36}/(?:summary|financing-scenario/preview))",
        )
        val filter = object : OncePerRequestFilter() {
            override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                val dedicatedConnector = request.localPort == port
                val uri = request.requestURI
                if (dedicatedConnector &&
                    !(request.isSecure && remotePath.matches(uri)) ||
                    !dedicatedConnector &&
                    uri.startsWith("/internal/mcp/credit/")
                ) {
                    response.status = HttpServletResponse.SC_NOT_FOUND
                    return
                }
                chain.doFilter(request, response)
            }
        }
        return FilterRegistrationBean<OncePerRequestFilter>(filter).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }
    }
}
