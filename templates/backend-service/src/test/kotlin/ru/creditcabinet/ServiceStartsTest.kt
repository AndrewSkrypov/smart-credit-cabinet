package ru.creditcabinet

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.env.Environment
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import ru.creditcabinet.common.observability.RequestIdFilter
import ru.creditcabinet.common.observability.RequestIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "SERVICE_NAME=service-template-test",
        "JWT_SECRET=test-only-secret-012345678901234567890123456",
        "management.tracing.sampling.probability=0.2",
        "management.otlp.tracing.export.enabled=false",
        "cabinet.identity.url=",
    ],
)
@AutoConfigureObservability
class ServiceStartsTest {
    @Autowired private lateinit var http: TestRestTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var environment: Environment

    @Autowired private lateinit var requestIdFilters: Map<String, RequestIdFilter>

    @Test
    fun `generated service starts and exposes health with a trace backed request ID`() {
        val response = http.getForEntity("/actuator/health", String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(TRACE_ID.matches(assertNotNull(response.headers.getFirst(RequestIds.HEADER))))
        assertEquals(setOf("requestIdFilter"), requestIdFilters.keys)
        assertEquals("health,info,prometheus", environment.getProperty("management.endpoints.web.exposure.include"))
        assertEquals("0.2", environment.getProperty("management.tracing.sampling.probability"))
    }

    @Test
    fun `security error preserves the public request ID contract`() {
        val headers = HttpHeaders().apply { set(RequestIds.HEADER, "untrusted-client-value") }
        val response = http.exchange(
            "/template-protected?token=synthetic",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java,
        )

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        val requestId = assertNotNull(response.headers.getFirst(RequestIds.HEADER))
        assertTrue(TRACE_ID.matches(requestId))
        val error = mapper.readTree(response.body)
        assertEquals(requestId, error["requestId"].asText())
        assertEquals("/template-protected", error["path"].asText())
    }

    private companion object {
        val TRACE_ID = Regex("[0-9a-f]{32}")
    }
}
