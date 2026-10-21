package ru.creditcabinet.common.model

/**
 * Russian taxpayer number of a business: 10 digits for a legal entity, 12 for an individual
 * entrepreneur. Check digits follow the Federal Tax Service algorithm, so a typo is caught
 * before any registry is asked.
 */
@JvmInline
value class Inn private constructor(val value: String) {
    val isLegalEntity: Boolean get() = value.length == 10

    override fun toString(): String = value

    companion object {
        private val WEIGHTS_10 = intArrayOf(2, 4, 10, 3, 5, 9, 4, 6, 8)
        private val WEIGHTS_11 = intArrayOf(7, 2, 4, 10, 3, 5, 9, 4, 6, 8)
        private val WEIGHTS_12 = intArrayOf(3, 7, 2, 4, 10, 3, 5, 9, 4, 6, 8)

        fun parse(raw: String): Inn {
            val digits = raw.trim()
            require(digits.all(Char::isDigit) && (digits.length == 10 || digits.length == 12)) {
                "ИНН — 10 цифр для организации или 12 для ИП"
            }
            require(checksumValid(digits)) { "В ИНН ошибка: не сходится контрольная цифра" }
            return Inn(digits)
        }

        fun isValid(raw: String): Boolean = runCatching { parse(raw) }.isSuccess

        private fun checkDigit(digits: String, weights: IntArray): Int =
            weights.indices.sumOf { weights[it] * (digits[it] - '0') } % 11 % 10

        private fun checksumValid(digits: String): Boolean =
            if (digits.length == 10) {
                checkDigit(digits, WEIGHTS_10) == digits[9] - '0'
            } else {
                checkDigit(digits, WEIGHTS_11) == digits[10] - '0' && checkDigit(digits, WEIGHTS_12) == digits[11] - '0'
            }
    }
}
