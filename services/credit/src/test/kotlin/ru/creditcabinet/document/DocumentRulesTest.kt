package ru.creditcabinet.document

import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.document.domain.DocumentRequirements
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.FileContent
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentRulesTest {
    private fun product(code: String) =
        Product(code, code, "", setOf(FinancingPurpose.EQUIPMENT), 100_000, 50_000_000, 1, 60, BigDecimal("18"), 5_000_000, false)

    private fun types(code: String, amount: Long, form: LegalForm, bankClient: Boolean? = null) =
        DocumentRequirements.required(product(code), amount, form, bankClient).map { it.type }

    @Test
    fun `a small loan of an entrepreneur needs no documents`() {
        assertEquals(emptyList(), types("WORKING_CAPITAL", 800_000, LegalForm.ENTREPRENEUR))
    }

    @Test
    fun `a large loan needs the tax return, an organisation also its statements`() {
        assertEquals(listOf(DocumentType.TAX_RETURN), types("WORKING_CAPITAL", 4_000_000, LegalForm.ENTREPRENEUR))
        assertEquals(listOf(DocumentType.TAX_RETURN, DocumentType.FINANCIAL_STATEMENTS), types("WORKING_CAPITAL", 4_000_000, LegalForm.LEGAL_ENTITY))
    }

    @Test
    fun `an investment loan needs the invoice, a company without an account the statement`() {
        assertEquals(listOf(DocumentType.EQUIPMENT_INVOICE), types("INVESTMENT", 1_500_000, LegalForm.ENTREPRENEUR))
        assertEquals(
            listOf(DocumentType.EQUIPMENT_INVOICE, DocumentType.BANK_STATEMENT),
            types("INVESTMENT", 1_500_000, LegalForm.ENTREPRENEUR, bankClient = false),
        )
    }

    @Test
    fun `a file must be what its extension says`() {
        assertTrue(FileContent.matches("pdf", "%PDF-1.7 ...".toByteArray()))
        assertFalse(FileContent.matches("pdf", "MZ executable".toByteArray()))
    }
}
