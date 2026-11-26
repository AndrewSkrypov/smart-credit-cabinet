package ru.creditcabinet

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test

@SpringBootTest(
    properties = [
        "SERVICE_NAME=service-template-test",
        "JWT_SECRET=test-only-secret-012345678901234567890123456",
    ],
)
@AutoConfigureMockMvc
class ServiceStartsTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `health is available`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
