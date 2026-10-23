package ru.creditcabinet.document

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.domain.FileStore
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["cabinet.antivirus.enabled=true", "cabinet.antivirus.host=127.0.0.1", "cabinet.antivirus.port=\${AV_TEST_PORT:33102}"])
@AutoConfigureMockMvc
class DocumentScanIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var companies: CompanyDirectory

    @MockitoSpyBean(name = "routingFileStore")
    private lateinit var store: FileStore

    @Test
    fun `real antivirus rejects infected business and chat files before storage, metadata or journal changes`() {
        whenever(companies.find(any())).thenAnswer { CompanySnapshot(it.getArgument(0), "000100000004", "Проверка файлов", LegalForm.ENTREPRENEUR) }
        val company = UUID.randomUUID()
        val token = Jwts.builder().subject(UUID.randomUUID().toString()).claim(TokenClaims.ROLE, "CLIENT")
            .claim(TokenClaims.COMPANY_ID, company.toString()).claim(TokenClaims.CAN_SIGN, true)
            .expiration(Date(System.currentTimeMillis() + 600_000)).signWith(verifier.signingKey).compact()
        val created = mvc.post("/api/v1/applications") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":1000000,"termMonths":12,"declaredRevenue12m":24000000,"bureauConsent":true}"""
        }.andReturn().response
        assertEquals(201, created.status, created.contentAsString)
        val id = UUID.fromString(mapper.readTree(created.contentAsString)["id"].asText())
        fun snapshot() = listOf("documents", "application_events", "application_chat_audit").map { table ->
            jdbc.queryForObject("select count(*) from $table where application_id=?", Long::class.java, id)
        }
        fun infected(path: String) = mvc.multipart(path) {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
            param("type", "OTHER")
            file(
                MockMultipartFile(
                    "file",
                    "Проверка.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    DocumentScanFixtures.infectedDocx(),
                ),
            )
        }.andReturn().response
        var before = snapshot()
        clearInvocations(store)
        var denied = infected("/api/v1/applications/$id/documents")
        assertEquals(400, denied.status)
        assertTrue(mapper.readTree(denied.getContentAsString(Charsets.UTF_8))["message"].asText().contains("содержит угрозу"))
        assertEquals(before, snapshot())
        verify(store, never()).put(any(), any(), any())

        mvc.post("/api/v1/applications/$id/submit") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        before = snapshot()
        denied = infected("/api/v1/applications/$id/messages/attachments")
        assertEquals(400, denied.status)
        assertTrue(mapper.readTree(denied.getContentAsString(Charsets.UTF_8))["message"].asText().contains("содержит угрозу"))
        assertEquals(before, snapshot())
        verify(store, never()).put(any(), any(), any())

        mvc.multipart("/api/v1/applications/$id/messages/attachments") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", UUID.randomUUID().toString())
            file(MockMultipartFile("file", "Безопасный.pdf", "application/pdf", "%PDF-1.7\n% synthetic\n%%EOF".toByteArray()))
        }.andExpect { status { isOk() } }
        verify(store).put(any(), any(), any())
        assertEquals(before[0]!! + 1, snapshot()[0])
        assertEquals(before[1], snapshot()[1], "A clean chat upload still must not alter the financial journal")
    }
}
