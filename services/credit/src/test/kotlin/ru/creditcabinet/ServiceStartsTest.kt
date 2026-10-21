package ru.creditcabinet

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.Test

/** The service starts with its configuration and, until a path is made public, refuses callers without a token. */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceStartsTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `health is up and the API is closed by default`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/applications").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the product line and the needs wizard are open before sign-in`() {
        mockMvc.get("/api/v1/products").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/products/purposes").andExpect { status { isOk() } }
        mockMvc.get("/api/v1/products/OVERDRAFT/calculation?amount=500000&termMonths=12").andExpect { status { isOk() } }
        mockMvc.post("/api/v1/products/match") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"purpose":"EQUIPMENT","amount":2000000,"termMonths":36}"""
        }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/underwriting/applications/00000000-0000-0000-0000-000000000000").andExpect { status { isUnauthorized() } }
    }
}
