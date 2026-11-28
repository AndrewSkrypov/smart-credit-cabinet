package ru.creditcabinet.agent

import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import ru.creditcabinet.agent.infrastructure.McpCreditConnectorBoundary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpCreditConnectorBoundaryTest {
    @Test
    fun `private MCP listener accepts only the three route shapes on TLS`() {
        val application = "00000000-0000-4000-8000-000000000001"
        for (path in listOf(
            "/internal/mcp/credit/priorities",
            "/internal/mcp/credit/applications/$application/summary",
            "/internal/mcp/credit/applications/$application/financing-scenario/preview",
        )) {
            assertTrue(probe(8443, true, path))
            assertFalse(probe(8443, false, path))
            assertFalse(probe(8080, true, path))
        }
        assertFalse(probe(8443, true, "/actuator/health"))
        assertFalse(probe(8443, true, "/internal/mcp/credit/priorities/extra"))
        assertTrue(probe(8080, false, "/actuator/health"))
    }

    private fun probe(port: Int, secure: Boolean, path: String): Boolean {
        val request = MockHttpServletRequest("GET", path).apply {
            localPort = port
            isSecure = secure
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        McpCreditConnectorBoundary().mcpCreditConnectorFilter(8443).filter.doFilter(request, response, chain)
        val passed = chain.request != null
        assertEquals(if (passed) 200 else 404, response.status)
        return passed
    }
}
