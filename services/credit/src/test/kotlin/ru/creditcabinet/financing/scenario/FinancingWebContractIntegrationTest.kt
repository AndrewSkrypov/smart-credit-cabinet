package ru.creditcabinet.financing.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import ru.creditcabinet.agent.infrastructure.McpActorAssertionRedeemer
import ru.creditcabinet.agent.infrastructure.McpCreditTls
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

abstract class FinancingWebContract {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    protected open val exportContract = false

    protected open val remoteConnectorBoundary = false

    @Test
    fun `public preview remains available without exposing MCP routes`() {
        val response = mvc.get("/v3/api-docs/credit").andReturn().response
        assertEquals(200, response.status)
        val contract = mapper.readTree(response.contentAsString)
        val paths = contract["paths"]
        assertTrue(paths.path("/api/v1/agent/applications/{id}/financing-scenario/preview").has("post"))
        assertFalse(paths.fieldNames().asSequence().any { it.startsWith("/internal/") || it.startsWith("/api/v1/agent/mcp/") })
        if (remoteConnectorBoundary) {
            assertEquals(404, mvc.get("/internal/mcp/credit/priorities").andReturn().response.status)
        }
        if (exportContract) {
            val output = Path.of("../../ci-artifacts/fantech-openapi-credit.json")
            Files.createDirectories(output.parent)
            Files.writeString(output, response.contentAsString)
        }
    }
}

@SpringBootTest(properties = ["cabinet.outbox.worker-enabled=false"])
@AutoConfigureMockMvc
class FinancingWebContractIntegrationTest : FinancingWebContract() {
    override val exportContract = true
}

/** Routing and OpenAPI checks; the real TLS/OAuth transport has its separate live acceptance. */
@SpringBootTest(
    properties = [
        "cabinet.outbox.worker-enabled=false",
        "cabinet.mcp.credit-tls.peer-sha256=0000000000000000000000000000000000000000000000000000000000000000",
    ],
)
@ActiveProfiles("agent-mcp-remote")
@AutoConfigureMockMvc
class FinancingRemoteWebContractIntegrationTest : FinancingWebContract() {
    override val remoteConnectorBoundary = true

    @MockitoBean private lateinit var actors: McpActorAssertionRedeemer

    @MockitoBean private lateinit var transport: McpCreditTls
}
