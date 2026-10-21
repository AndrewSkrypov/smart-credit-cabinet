package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.tokenFor
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class AssistantApiTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @MockitoBean private lateinit var model: LanguageModel

    @MockitoBean private lateinit var applications: CreditApplications

    private val applicationId = UUID.randomUUID()
    private val token by lazy { verifier.tokenFor(UUID.randomUUID(), "CLIENT", UUID.randomUUID()) }

    private val refused = ApplicationFacts(
        number = "1042",
        status = "банк отказал",
        lines = listOf("Причины отказа:\nСейчас риск выше, чем допускает продукт\nДолговая нагрузка: платежи — 64% выручки"),
        offer = null,
        refusal = listOf("Сейчас риск выше, чем допускает продукт", "Долговая нагрузка: платежи — 64% выручки"),
        missingDocuments = emptyList(),
        deadline = null,
    )

    private fun ask(question: String, application: UUID? = applicationId) =
        mockMvc.post("/api/v1/assistant/messages") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("question" to question, "applicationId" to application))
        }.andReturn().response

    @Test
    fun `without a model the answer comes from the rules and the application's own facts`() {
        whenever(model.available()).thenReturn(false)
        whenever(applications.facts(eq(applicationId), any())).thenReturn(refused)
        val answer = mapper.readTree(ask("Почему мне отказали?").getContentAsString(Charsets.UTF_8))
        assertEquals("RULES", answer["mode"].asText())
        assertTrue(answer["text"].asText().contains("64% выручки"), answer["text"].asText())
        assertTrue(answer["sources"].any { it.asText() == "Заявка № 1042" })
    }

    @Test
    fun `with a model the prompt holds the rules, the reference and the facts, and the model answers`() {
        whenever(model.available()).thenReturn(true)
        whenever(model.chat(any())).thenReturn("Банк отказал из-за высокой нагрузки.")
        whenever(applications.facts(eq(applicationId), any())).thenReturn(refused)
        val answer = mapper.readTree(ask("Что такое долговая нагрузка и почему отказ?").getContentAsString(Charsets.UTF_8))
        assertEquals("MODEL", answer["mode"].asText())
        assertEquals("Банк отказал из-за высокой нагрузки.", answer["text"].asText())
        val messages = argumentCaptor<List<ChatMessage>>().also { verify(model).chat(it.capture()) }.firstValue
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().content.contains("Не придумывай суммы"))
        assertTrue(messages.last().content.contains("Долговая нагрузка:"), "the reference article is in the prompt")
        assertTrue(messages.last().content.contains("64% выручки"), "the facts are in the prompt")
    }

    @Test
    fun `another company's application is not found, a long question is refused`() {
        whenever(applications.facts(eq(applicationId), any())).thenThrow(NotFoundException("Заявка не найдена"))
        assertEquals(404, ask("Что с заявкой?").status)
        assertEquals(400, ask("а".repeat(501), application = null).status)
    }
}
