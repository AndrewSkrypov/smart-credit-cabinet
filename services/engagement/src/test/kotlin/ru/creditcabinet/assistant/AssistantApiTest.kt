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
    fun `a question about the refusal is answered from the facts even when a model runs`() {
        whenever(model.available()).thenReturn(true)
        whenever(model.chat(any())).thenReturn("Увеличьте сумму до 150% и снизьте ставку до 18%")
        whenever(applications.facts(eq(applicationId), any())).thenReturn(refused)
        val answer = mapper.readTree(ask("Почему мне отказали?").getContentAsString(Charsets.UTF_8))
        assertEquals("RULES", answer["mode"].asText())
        assertTrue(answer["text"].asText().contains("— Долговая нагрузка: платежи — 64% выручки"), answer["text"].asText())
        verify(model, org.mockito.kotlin.never()).chat(any())
    }

    @Test
    fun `with a model the prompt holds the rules, the reference and the facts, and the model answers`() {
        whenever(model.available()).thenReturn(true)
        whenever(model.chat(any())).thenReturn("## Ответ\n**Нагрузка** — доля выручки на платежи.\n1. Считаются все кредиты")
        whenever(applications.facts(eq(applicationId), any())).thenReturn(refused)
        val answer = mapper.readTree(ask("Что такое долговая нагрузка?").getContentAsString(Charsets.UTF_8))
        assertEquals("MODEL", answer["mode"].asText())
        assertEquals("Ответ\nНагрузка — доля выручки на платежи.\n— Считаются все кредиты", answer["text"].asText(), "markup is turned into plain text")
        val messages = argumentCaptor<List<ChatMessage>>().also { verify(model).chat(it.capture()) }.firstValue
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().content.contains("Не придумывай суммы"))
        assertTrue(messages.last().content.contains("Долговая нагрузка:"), "the reference article is in the prompt")
        assertTrue(messages.last().content.contains("64% выручки"), "the facts are in the prompt")
    }

    @Test
    fun `an answer with a figure that is in no data is replaced by the rules answer`() {
        whenever(model.available()).thenReturn(true)
        whenever(model.chat(any())).thenReturn("Нагрузка считается от суммы 500 000 ₽ за 18 месяцев.")
        whenever(applications.facts(eq(applicationId), any())).thenReturn(refused)
        val answer = mapper.readTree(ask("Что такое долговая нагрузка?").getContentAsString(Charsets.UTF_8))
        assertEquals("RULES", answer["mode"].asText())
        assertTrue(answer["text"].asText().startsWith("Долговая нагрузка — доля месячной выручки"), answer["text"].asText())
    }

    @Test
    fun `figures taken from the application pass the check`() {
        assertEquals(emptySet(), Grounding.unknownNumbers("Платёж — 138 951,76 ₽, нагрузка 64%", "платёж 138951.76 ₽ в месяц; платежи — 64% выручки"))
        assertEquals(setOf("500000", "18"), Grounding.unknownNumbers("сумма на 500 000 ₽, срок 18 мес., шаг 2", "нагрузка 64%"))
    }

    @Test
    fun `an answer that contradicts the reference is replaced by the rules answer`() {
        whenever(model.available()).thenReturn(true)
        whenever(model.chat(any())).thenReturn("Овердрафт — лимит на счёте. Он не начисляет проценты за использование, а просто возобновляется.")
        val answer = mapper.readTree(ask("Чем овердрафт отличается от кредита на пополнение оборота?", application = null).getContentAsString(Charsets.UTF_8))
        assertEquals("RULES", answer["mode"].asText())
        assertTrue(answer["text"].asText().contains("Это платный продукт"), answer["text"].asText())
    }

    @Test
    fun `false claims are found, true ones pass`() {
        assertEquals(1, Grounding.falseClaims("Он не начисляет проценты за использование").size)
        assertEquals(1, Grounding.falseClaims("Проценты по овердрафту не начисляются").size)
        assertEquals(1, Grounding.falseClaims("Это бесплатный лимит").size)
        assertEquals(1, Grounding.falseClaims("Одобрение гарантировано").size)
        assertEquals(1, Grounding.falseClaims("Кредит — не платный").size)
        assertEquals(emptyList(), Grounding.falseClaims("Банк не гарантирует одобрение заранее."))
        assertEquals(emptyList(), Grounding.falseClaims("Проценты начисляются на использованную часть лимита за каждый день."))
        assertEquals(emptyList(), Grounding.falseClaims("Банк не обещает одобрения: решение принимается после оценки."))
    }

    @Test
    fun `another script, a ramble and echoed prompt labels do not reach the client`() {
        assertTrue(Grounding.problems("Если данные 齐全, банк проверит", "").any { it.startsWith("foreign script") })
        assertTrue(Grounding.problems("Очень длинно. ".repeat(80), "").any { it.startsWith("too long") })
        assertEquals(emptyList(), Grounding.problems("Договор подписывается кодом из 6 цифр в кабинете.", ""))

        whenever(model.available()).thenReturn(true)
        whenever(model.chat(any())).thenReturn("Справка:\n— Бюро хранит сведения о кредитах.\n\nДанные заявки: Заявка не выбрана.")
        val answer = mapper.readTree(ask("Зачем банку бюро кредитных историй?", application = null).getContentAsString(Charsets.UTF_8))
        assertEquals("MODEL", answer["mode"].asText())
        assertEquals("— Бюро хранит сведения о кредитах.", answer["text"].asText())
    }

    @Test
    fun `rules with thresholds and steps are given as the reference writes them`() {
        whenever(model.available()).thenReturn(true)
        whenever(model.chat(any())).thenReturn("Отчётность нужна от 1 до 3 млн ₽.")
        val answer = mapper.readTree(ask("Какие документы понадобятся?", application = null).getContentAsString(Charsets.UTF_8))
        assertEquals("RULES", answer["mode"].asText())
        assertTrue(answer["text"].asText().startsWith("Набор документов зависит от продукта и суммы"), answer["text"].asText())
        verify(model, org.mockito.kotlin.never()).chat(any())
    }

    @Test
    fun `a question about my refusal without an open application is not given to the model`() {
        whenever(model.available()).thenReturn(true)
        whenever(model.chat(any())).thenReturn("Причина отказа: сложная финансовая ситуация и недавняя регистрация.")
        val answer = mapper.readTree(ask("Почему мне отказали и что можно изменить?", application = null).getContentAsString(Charsets.UTF_8))
        assertEquals("RULES", answer["mode"].asText())
        assertTrue(answer["text"].asText().startsWith("Про вашу заявку я отвечаю по её данным"), answer["text"].asText())
        verify(model, org.mockito.kotlin.never()).chat(any())
        assertTrue(!RuleAnswers.isAboutOwnApplication("Что такое контрпредложение?"))
        assertTrue(!RuleAnswers.isAboutOwnApplication("Сколько рассматривается заявка?"))
        assertTrue(RuleAnswers.isAboutOwnApplication("Что сейчас с моей заявкой?"))
        assertEquals(listOf("load"), Knowledge.relevant("Что такое долговая нагрузка?").map { it.id }, "«долговая» is not «долго»")
    }

    @Test
    fun `another company's application is not found, a long question is refused`() {
        whenever(applications.facts(eq(applicationId), any())).thenThrow(NotFoundException("Заявка не найдена"))
        assertEquals(404, ask("Что с заявкой?").status)
        assertEquals(400, ask("а".repeat(501), application = null).status)
    }
}
