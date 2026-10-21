package ru.creditcabinet.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InnTest {
    @Test
    fun `valid numbers of a legal entity and an entrepreneur are accepted`() {
        // Published numbers of the Federal Tax Service and Sberbank: check digits are known to be right.
        assertTrue(Inn.parse("7707083893").isLegalEntity)
        assertFalse(Inn.parse("500100732259").isLegalEntity)
    }

    @Test
    fun `a wrong check digit is refused with a reason`() {
        val error = assertFailsWith<IllegalArgumentException> { Inn.parse("7707083894") }
        assertEquals("В ИНН ошибка: не сходится контрольная цифра", error.message)
    }

    @Test
    fun `wrong length or letters are refused`() {
        assertFalse(Inn.isValid("12345"))
        assertFalse(Inn.isValid("77070838AB"))
        assertFalse(Inn.isValid(""))
    }
}
