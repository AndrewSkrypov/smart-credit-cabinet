package ru.creditcabinet.offer.application

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class ContractTextFormatTest {
    @Test
    fun `money is written with grouped thousands and kopecks after a comma`() {
        assertEquals("99 884,66 ₽", ContractTexts.money(BigDecimal("99884.66")))
        assertEquals("1 200 000,00 ₽", ContractTexts.money(BigDecimal("1200000")))
        assertEquals("0,05 ₽", ContractTexts.money(BigDecimal("0.049")))
    }

    @Test
    fun `rate drops trailing zeros and uses a decimal comma`() {
        assertEquals("19,5%", ContractTexts.percent(BigDecimal("19.50")))
        assertEquals("20%", ContractTexts.percent(BigDecimal("20.00")))
    }
}
