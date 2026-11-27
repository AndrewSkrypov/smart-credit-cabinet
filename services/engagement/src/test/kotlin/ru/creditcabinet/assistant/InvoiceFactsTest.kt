package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.ObjectMapper
import ru.creditcabinet.assistant.controller.InvoiceFactCandidates
import ru.creditcabinet.assistant.controller.InvoiceFactChoice
import ru.creditcabinet.assistant.controller.InvoiceFactSelection
import ru.creditcabinet.assistant.domain.ChatMessage
import ru.creditcabinet.assistant.domain.LanguageModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InvoiceFactsTest {
    @Test
    fun `model receives capped candidates as data and returns only validated indexes`() {
        val selected = selector { messages ->
            assertEquals(listOf("system", "user"), messages.map { it.role })
            assertEquals(candidates.amounts.single(), mapper.readTree(messages[1].content).path("amounts").get(0).asText())
            """{"amountIndex":0,"dateIndex":0}"""
        }.choose(candidates)

        assertEquals(InvoiceFactChoice(0, 0), selected)
    }

    @Test
    fun `model response cannot introduce values or commands`() {
        for (reply in listOf(
            null,
            "not a JSON object",
            """{"amountIndex":0,"dateIndex":0,"amount":1}""",
            """{"amountIndex":0,"dateIndex":0} {"amountIndex":0,"dateIndex":0}""",
        )) {
            assertEquals(InvoiceFactChoice(), selector { reply }.choose(candidates))
        }
        assertEquals(
            InvoiceFactChoice(),
            selector { """{"amountIndex":"0","dateIndex":20}""" }.choose(candidates),
        )
    }

    @Test
    fun `oversized source lines are rejected before using the model`() {
        val parser = selector { error("Oversized sources should be rejected before calling the model") }

        assertFailsWith<IllegalArgumentException> {
            parser.choose(candidates.copy(amounts = listOf("A".repeat(181))))
        }
    }

    private fun selector(reply: (List<ChatMessage>) -> String?) = InvoiceFactSelection(
        object : LanguageModel {
            override val name = "test"
            override fun available() = true
            override fun chat(messages: List<ChatMessage>) = reply(messages)
        },
        mapper,
    )

    private companion object {
        val mapper = ObjectMapper()
        val candidates = InvoiceFactCandidates(listOf("Итого: 140 000 руб."), listOf("Срок оплаты: 10.12.2026"))
    }
}
