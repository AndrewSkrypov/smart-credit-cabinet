package ru.creditcabinet.common.config

import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiConfigTest {
    @Test
    fun `public products stay public while application writes describe bearer and conflicts`() {
        val config = OpenApiConfig("Проверка", "0.1")
        val products = Operation()
        val application = Operation()
        val api = config.openApi().paths(
            Paths()
                .addPathItem("/api/v1/products", PathItem().get(products))
                .addPathItem("/api/v1/applications/{id}/submit", PathItem().post(application)),
        )
        config.responseContracts(PlatformSecurityProperties(listOf("GET /api/v1/products"))).customise(api)
        assertTrue(products.security.isEmpty())
        assertEquals(listOf("bearerAuth"), application.security.single().keys.toList())
        assertTrue(application.responses.keys.containsAll(listOf("400", "401", "403", "404", "409")))
        assertEquals("#/components/schemas/ErrorResponse", application.responses["409"]!!.content["application/json"]!!.schema.`$ref`)
        assertTrue(api.components.schemas.containsKey("ErrorResponse"))
    }

    @Test
    fun `simulator system key errors describe the smaller body actually returned by its filter`() {
        val config = OpenApiConfig("Проверка", "0.1")
        val operation = Operation()
        val api = config.openApi().paths(Paths().addPathItem("/ext/registry/v1/companies/{inn}", PathItem().get(operation)))
        config.responseContracts(PlatformSecurityProperties(listOf("GET /ext/**"))).customise(api)
        assertEquals(listOf("systemKey"), operation.security.single().keys.toList())
        assertEquals("#/components/schemas/SystemKeyError", operation.responses["401"]!!.content["application/json"]!!.schema.`$ref`)
        val example = operation.responses["401"]!!.content["application/json"]!!.example as Map<*, *>
        assertEquals(setOf("status", "error", "message"), example.keys)
    }
}
