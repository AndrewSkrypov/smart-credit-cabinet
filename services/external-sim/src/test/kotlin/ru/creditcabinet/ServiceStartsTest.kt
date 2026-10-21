package ru.creditcabinet

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test

/** The service starts and the simulated systems refuse callers without the system key. */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceStartsTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `health is up and the simulated systems want a key`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
        mockMvc.get("/ext/registry/v1/companies/000100000004").andExpect { status { isUnauthorized() } }
    }
}
