package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.tokenFor
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class ProductPickAndSummaryTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @MockitoBean private lateinit var model: LanguageModel

    @MockitoBean private lateinit var applications: CreditApplications

    @MockitoBean private lateinit var products: CreditProducts

    private val client by lazy { verifier.tokenFor(UUID.randomUUID(), "CLIENT", UUID.randomUUID()) }
    private val underwriter by lazy { verifier.tokenFor(UUID.randomUUID(), "UNDERWRITER") }

    private fun post(path: String, token: String, body: Any) =
        mockMvc.post(path) {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
        }.andReturn().response

    private fun ask(question: String, history: List<Map<String, String>> = emptyList()) =
        mapper.readTree(post("/api/v1/assistant/messages", client, mapOf("question" to question, "history" to history)).getContentAsString(Charsets.UTF_8))

    private val matches by lazy {
        mapper.readTree(
            """
        [{"product":{"code":"WORKING_CAPITAL","name":"Кредит на пополнение оборота","baseRate":20},"fits":true,"reasons":[],"adjustments":[],
          "termMonths":12,"monthlyPayment":185270.37,"overpayment":223244.44},
         {"product":{"code":"OVERDRAFT","name":"Овердрафт","baseRate":19},"fits":false,"reasons":[],"adjustments":["сумма больше лимита"],
          "termMonths":12,"monthlyPayment":null,"overpayment":null}]
        """,
        )
    }

    @Test
    fun `the need is read from ordinary words`() {
        assertEquals(FinancingNeed("WORKING_CAPITAL", 2_000_000, 12), NeedParser.parse("Нужно 2 млн на закупку товара на год"))
        assertEquals(FinancingNeed("EQUIPMENT", 1_500_000, 36), NeedParser.parse("полтора миллиона на станок на 3 года"))
        assertEquals(FinancingNeed("CASH_GAP", 500_000, 6), NeedParser.parse("кассовый разрыв 500 тыс, полгода"))
        assertEquals(FinancingNeed(null, 2_000_000, 24), NeedParser.parse("2 000 000 на 24 месяца"))
        assertTrue(!NeedParser.asksForProduct("Что лучше для закупки товара: овердрафт или кредит?"), "a comparison stays with the reference")
    }

    @Test
    fun `a request for a product is answered with the matcher's products and payments, never by the model`() {
        whenever(model.available()).thenReturn(true)
        whenever(products.purposes(any())).thenReturn(mapOf("WORKING_CAPITAL" to "Закупка товара или сырья"))
        whenever(products.match(eq("WORKING_CAPITAL"), eq(2_000_000L), eq(12), any())).thenReturn(matches)
        val answer = ask("Подбери кредит: 2 млн на закупку товара на год")
        assertEquals("RULES", answer["mode"].asText())
        val text = answer["text"].asText()
        assertTrue("Кредит на пополнение оборота" in text && "185 270,37 ₽" in text && "Овердрафт" !in text, text)
        assertEquals("/client/new?purpose=WORKING_CAPITAL&amount=2000000&termMonths=12", answer["link"]["path"].asText())
        verify(model, never()).chat(any())
    }

    @Test
    fun `a missing amount is asked for, and the answer to it completes the need from the conversation`() {
        whenever(products.purposes(any())).thenReturn(mapOf("WORKING_CAPITAL" to "Закупка товара или сырья"))
        whenever(
            products.catalogue(any()),
        ).thenReturn(mapper.readTree("""[{"name":"Кредит на пополнение оборота","purposes":["WORKING_CAPITAL"],"maxAmount":30000000,"baseRate":20}]"""))
        val first = ask("Посоветуй кредит на закупку сырья")
        assertTrue(ProductAdvice.ASK_AMOUNT in first["text"].asText(), first["text"].asText())

        whenever(products.match(eq("WORKING_CAPITAL"), eq(3_000_000L), eq(24), any())).thenReturn(matches)
        val second = ask(
            "3 млн на 2 года",
            listOf(
                mapOf("role" to "user", "text" to "Посоветуй кредит на закупку сырья"),
                mapOf(
                    "role" to "assistant",
                    "text" to first["text"].asText(),
                ),
            ),
        )
        assertTrue("185 270,37 ₽" in second["text"].asText(), second["text"].asText())
    }

    @Test
    fun `the underwriter's summary retells the advice, and a client may not ask for it`() {
        val applicationId = UUID.randomUUID()
        whenever(applications.advice(eq(applicationId), any())).thenReturn(
            mapper.readTree(
                """{"verdict":"Перед решением: рисков — 1.","modelVersion":1,"items":[{"title":"Нагрузка 47% при пороге 35%","text":"С новым платежом нагрузка выше политики."}]}""",
            ),
        )
        whenever(model.chat(any())).thenReturn("Нагрузка 47% выше порога 35%: перед решением стоит обсудить меньшую сумму.")
        val summary = mapper.readTree(
            post("/api/v1/assistant/underwriting-summary", underwriter, mapOf("applicationId" to applicationId)).getContentAsString(Charsets.UTF_8),
        )
        assertEquals("MODEL", summary["mode"].asText(), summary.toString())

        whenever(model.chat(anyOrNull())).thenReturn("Нагрузка 52% — отказать.")
        val invented = mapper.readTree(
            post("/api/v1/assistant/underwriting-summary", underwriter, mapOf("applicationId" to applicationId)).getContentAsString(Charsets.UTF_8),
        )
        assertEquals("RULES", invented["mode"].asText())
        assertTrue(invented["text"].asText().startsWith("Перед решением"), invented.toString())

        assertEquals(403, post("/api/v1/assistant/underwriting-summary", client, mapOf("applicationId" to applicationId)).status)
    }
}
