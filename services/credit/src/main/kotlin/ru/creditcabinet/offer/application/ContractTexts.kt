package ru.creditcabinet.offer.application

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.offer.domain.ContractTextSnapshot
import ru.creditcabinet.offer.domain.Offer
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductMatcher.rubles
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ContractTextView(
    val snapshot: ContractTextSnapshot?,
    val text: String,
    val legacySummary: Boolean,
    val signable: Boolean,
    val signedAt: Instant?,
)

@Component
class ContractTexts(private val snapshots: ContractTextStore, private val clock: Clock) {
    fun find(offerId: UUID): ContractTextSnapshot? = snapshots.find(offerId)

    /** A new signing code always refers to this same immutable text for the accepted offer. */
    @Transactional
    fun freeze(application: LoanApplication, offer: Offer, product: Product): ContractTextSnapshot {
        snapshots.lockOffer(application.id, offer.id)
        find(offer.id)?.let { return it }
        val text = render(application, offer, product)
        val snapshot = ContractTextSnapshot(UUID.randomUUID(), application.id, offer.id, 1, text, sha256(text), clock.instant())
        snapshots.insert(snapshot)
        return snapshot
    }

    fun render(application: LoanApplication, offer: Offer, product: Product): String {
        val calculation = LoanMath.calculate(offer.amount, offer.rate, offer.termMonths, product.revolving)
        val rows = calculation.schedule
        return """
            Кредитный договор по заявке № ${application.number}
            Демонстрационный текст: реальные кредиты не выдаются.
            Заёмщик: ${application.company.name ?: "ИНН ${application.company.inn}"}, ИНН ${application.company.inn}
            Продукт: ${product.name} (версия ${offer.productVersion})
            Предложение: ${offer.id}
            Сумма: ${rubles(offer.amount)}; срок: ${offer.termMonths} мес.; ставка: ${percent(offer.rate)} годовых
            Первый платёж: ${money(rows.first().payment)}; максимальный платёж: ${money(
            rows.maxOf {
                it.payment
            },
        )}; последний платёж: ${money(rows.last().payment)}
            Всего к возврату: ${money(calculation.totalPayment)}; переплата: ${money(calculation.overpayment)}
            ${if (product.revolving) "Возобновляемый лимит: расчёт при полном использовании. Платежи меняются; остаток возвращается в последний месяц." else "Последний платёж учитывает округление и закрывает долг."}
            В графике указаны номера месяцев; календарные даты платежей не определены.
        """.trimIndent()
    }

    fun legacySummary(application: LoanApplication, offer: Offer, product: Product): String =
        "Сводка сохранённых условий. Исходный подписываемый текст не сохранён.\n" + render(application, offer, product).substringAfter('\n')

    companion object {
        private val NBSP = '\u00A0'

        /** 99 884,66 ₽ — the way amounts are written in Russian documents. */
        fun money(amount: java.math.BigDecimal): String {
            val scaled = amount.setScale(2, java.math.RoundingMode.HALF_UP)
            val whole = String.format(java.util.Locale.ROOT, "%,d", scaled.toBigInteger()).replace(',', NBSP)
            val kopecks = scaled.remainder(java.math.BigDecimal.ONE).abs().movePointRight(2).toInt()
            return "$whole,${"%02d".format(kopecks)}$NBSP₽"
        }

        /** 19,5% rather than 19.50%. */
        fun percent(rate: java.math.BigDecimal): String = rate.stripTrailingZeros().toPlainString().replace('.', ',') + "%"

        fun sha256(
            text: String,
        ): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
