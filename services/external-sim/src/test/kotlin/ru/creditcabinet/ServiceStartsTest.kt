package ru.creditcabinet

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
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
        mockMvc.get("/sim/registry").andExpect { status { isUnauthorized() } }
    }
}
