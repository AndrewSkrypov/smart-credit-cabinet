package ru.creditcabinet.financing.invoice

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.financing.invoice.application.InvoiceExtraction
import ru.creditcabinet.financing.invoice.application.InvoiceExtractionMode
import ru.creditcabinet.financing.invoice.application.InvoiceModel
import ru.creditcabinet.financing.invoice.application.InvoiceModelChoice
import ru.creditcabinet.financing.invoice.application.InvoicePdfTooLarge
import ru.creditcabinet.financing.invoice.domain.InvoiceDateLabel
import ru.creditcabinet.financing.invoice.domain.InvoiceEvidence
import ru.creditcabinet.financing.invoice.domain.InvoiceFact
import ru.creditcabinet.financing.invoice.domain.InvoicePdfLine
import ru.creditcabinet.financing.invoice.infrastructure.InvoicePdfReader
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceExtractionTest {
    private val reader = InvoicePdfReader()

    @Test
    fun `model selects indices and response contains the exact real PDF lines`() {
        val result = InvoiceExtraction(
            InvoiceModel { amounts, dates, authorization ->
                assertEquals(listOf("Total: 140 000,00 RUB"), amounts)
                assertEquals(listOf("Due date: 10.12.2026"), dates)
                assertEquals(AUTHORIZATION, authorization)
                InvoiceModelChoice(0, 0)
            },
            reader,
        ).extract(pdf(listOf(), listOf("Invoice #102", "Total: 140 000,00 RUB", "Due date: 10.12.2026")), AUTHORIZATION)

        assertEquals(InvoiceExtractionMode.MODEL, result.mode)
        assertEquals(InvoiceFact(140_000L, 2, "Total: 140 000,00 RUB"), result.amount)
        assertEquals(listOf(InvoiceFact(LocalDate.parse("2026-12-10"), 2, "Due date: 10.12.2026")), result.dates)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `both actual dates are presented for explicit manual selection`() {
        val result = InvoiceExtraction(InvoiceModel { _, _, _ -> error("An ambiguous date must not go to the model") }, reader).extract(
            pdf(listOf("Total: 140 000 RUB", "Due date: 10.12.2026", "Valid until: 24.12.2026")),
            AUTHORIZATION,
        )

        assertEquals(InvoiceExtractionMode.MANUAL, result.mode)
        assertEquals(140_000L, result.amount?.value)
        assertEquals(listOf(LocalDate.parse("2026-12-10"), LocalDate.parse("2026-12-24")), result.dates.map { it.value })
        assertEquals(listOf("Due date: 10.12.2026", "Valid until: 24.12.2026"), result.dates.map { it.quote })
        assertNotNull(result.reason)
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `unavailable or ungrounded model results return a manual result with real candidates`() {
        for (choice in listOf(null, InvoiceModelChoice(0, 400), InvoiceModelChoice(null, 0))) {
            val result = InvoiceExtraction(InvoiceModel { _, _, _ -> choice }, reader).extract(
                pdf(listOf("Total: 140 000 RUB", "Due date: 10.12.2026")),
                AUTHORIZATION,
            )

            assertEquals(InvoiceExtractionMode.MANUAL, result.mode)
            assertEquals(140_000L, result.amount?.value)
            assertEquals(LocalDate.parse("2026-12-10"), result.dates.single().value)
            assertTrue(result.needsConfirmation)
        }
    }

    @Test
    fun `scanner-only PDF has a manual route and a damaged PDF returns 400`() {
        val extraction = InvoiceExtraction(InvoiceModel { _, _, _ -> error("No PDF text") }, reader)
        val result = extraction.extract(pdf(emptyList()), AUTHORIZATION)
        assertEquals(InvoiceExtractionMode.MANUAL, result.mode)
        assertNull(result.amount)
        assertTrue(result.dates.isEmpty())

        assertFailsWith<BadRequestException> { extraction.extract("%PDF- not a PDF".toByteArray(), AUTHORIZATION) }
        val tooManyPages = pdf(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertFailsWith<InvoicePdfTooLarge> { extraction.extract(tooManyPages, AUTHORIZATION) }
    }

    @Test
    fun `rubles and marked dates can be grounded in Russian source lines`() {
        val evidence = InvoiceEvidence.from(
            listOf(
                InvoicePdfLine(1, "Итого к оплате: 140\u202F000,00 ₽"),
                InvoicePdfLine(1, "Ожидаемая дата поступления: 10 декабря 2026 г."),
                InvoicePdfLine(2, "Счёт действителен до: 24.12.2026"),
                InvoicePdfLine(2, "Срок оплаты: 31.11.2026"),
            ),
        )

        assertEquals(140_000L, evidence.amounts.single().value)
        assertEquals(InvoiceDateLabel.PAYMENT, evidence.dates.first().label)
        assertEquals(InvoiceDateLabel.VALIDITY, evidence.dates.last().label)
        assertEquals(listOf(1, 2), evidence.dates.map { it.fact.page })
        assertEquals(LocalDate.parse("2026-12-10"), evidence.dates.first().fact.value)
    }

    private fun pdf(vararg pages: List<String>): ByteArray =
        PDDocument().use { document ->
            for (lines in pages) {
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    content.setLeading(16f)
                    content.newLineAtOffset(50f, 700f)
                    for (line in lines) {
                        content.showText(line)
                        content.newLine()
                    }
                    content.endText()
                }
            }
            ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }

    private companion object {
        const val AUTHORIZATION = "Bearer test-agent"
    }
}
