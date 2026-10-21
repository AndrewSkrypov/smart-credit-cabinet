package ru.creditcabinet.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.common.security.JwtVerifier
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Runs against the local Postgres (DB_HOST/PORT/NAME defaults of application.yml) with the
// Flyway migrations applied, the same way CI runs it with a Postgres service container.
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtVerifier: JwtVerifier

    @Autowired
    private lateinit var mapper: ObjectMapper

    private fun signUp(inn: String, email: String = "owner-${Random.nextLong()}@example.test", consent: Boolean = true, password: String = "Secret2026") =
        mockMvc.post("/api/v1/auth/sign-up") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"fullName":"Тест Тестов","email":"$email","password":"$password","inn":"$inn","personalDataConsent":$consent}"""
        }.andReturn().response

    private fun signIn(email: String, password: String) =
        mockMvc.post("/api/v1/auth/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn().response

    private fun tokenOf(body: String): String = mapper.readTree(body)["token"].asText()

    private fun message(body: String): String = mapper.readTree(body)["message"].asText()

    @Test
    fun `sign-up connects the company and the token carries it with the right to sign`() {
        val response = signUp(randomInn12())
        assertEquals(200, response.status, response.contentAsString)
        val claims = jwtVerifier.parseClaims(tokenOf(response.getContentAsString(Charsets.UTF_8)))
        assertEquals("CLIENT", claims.role)
        assertNotNull(claims.companyId)
        assertTrue(claims.canSign)
    }

    @Test
    fun `a company is connected once - a second sign-up with the same INN is 409`() {
        val inn = randomInn12()
        assertEquals(200, signUp(inn).status)
        val second = signUp(inn)
        assertEquals(409, second.status)
        assertTrue(message(second.getContentAsString(Charsets.UTF_8)).startsWith("Компания с этим ИНН уже подключена"))
    }

    @Test
    fun `a mistyped INN, a weak password and a missing consent are refused before anything is stored`() {
        assertEquals("В ИНН ошибка: не сходится контрольная цифра", message(signUp("000100000005").getContentAsString(Charsets.UTF_8)))
        assertEquals(400, signUp(randomInn12(), password = "short").status)
        assertEquals(400, signUp(randomInn12(), consent = false).status)
    }

    @Test
    fun `demo staff signs in without a company, a wrong password is 401`() {
        val response = signIn("underwriter@demo.test", "Demo2026")
        assertEquals(200, response.status, response.contentAsString)
        val claims = jwtVerifier.parseClaims(tokenOf(response.contentAsString))
        assertEquals("UNDERWRITER", claims.role)
        assertEquals(null, claims.companyId)
        assertEquals(401, signIn("underwriter@demo.test", "wrong-password1").status)
    }

    @Test
    fun `a client sees their own company, bank staff and anonymous callers do not`() {
        val client = tokenOf(signIn("client@demo.test", "Demo2026").contentAsString)
        mockMvc.get("/api/v1/companies/me") { header("Authorization", "Bearer $client") }
            .andExpect {
                status { isOk() }
                jsonPath("$.inn") { value("000100000004") }
                jsonPath("$.legalForm") { value("ENTREPRENEUR") }
            }
        val staff = tokenOf(signIn("manager@demo.test", "Demo2026").contentAsString)
        mockMvc.get("/api/v1/companies/me") { header("Authorization", "Bearer $staff") }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/companies/me").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/users/me") { header("Authorization", "Bearer forged") }.andExpect { status { isUnauthorized() } }
    }

    /** A synthetic ИНН of an entrepreneur ("00" region does not exist) with valid check digits. */
    private fun randomInn12(): String {
        val base = "00" + (1..8).map { Random.nextInt(10) }.joinToString("")
        val first = checkDigit(base, intArrayOf(7, 2, 4, 10, 3, 5, 9, 4, 6, 8))
        val withFirst = base + first
        return withFirst + checkDigit(withFirst, intArrayOf(3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8))
    }

    private fun checkDigit(digits: String, weights: IntArray) = weights.indices.sumOf { weights[it] * (digits[it] - '0') } % 11 % 10
}
