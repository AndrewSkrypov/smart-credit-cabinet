package ru.creditcabinet.offer

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductMatcher.rubles
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ContractTextSnapshot(
    val id: UUID,
    val applicationId: UUID,
    val offerId: UUID,
    val templateVersion: Int,
    val text: String,
    val sha256: String,
    val createdAt: Instant,
)

data class ContractTextView(
    val snapshot: ContractTextSnapshot?,
    val text: String,
    val legacySummary: Boolean,
    val signable: Boolean,
    val signedAt: Instant?,
)

@Component
class ContractTexts(private val jdbc: JdbcTemplate, private val clock: Clock) {
    fun find(offerId: UUID): ContractTextSnapshot? = jdbc.query(
        "select * from contract_text_snapshots where offer_id=?",
        { rs, _ ->
            ContractTextSnapshot(
                rs.getObject("id", UUID::class.java),
                rs.getObject("application_id", UUID::class.java),
                rs.getObject("offer_id", UUID::class.java),
                rs.getInt("template_version"),
                rs.getString("contract_text"),
                rs.getString("sha256"),
                rs.getTimestamp("created_at").toInstant(),
            )
        },
        offerId,
    ).firstOrNull()

    /** A new signing code always refers to this same immutable text for the accepted offer. */
    @Transactional
    fun freeze(application: LoanApplication, offer: Offer, product: Product): ContractTextSnapshot {
        jdbc.queryForObject("select id from offers where id=? and application_id=? for update", UUID::class.java, offer.id, application.id)
        find(offer.id)?.let { return it }
        val text = render(application, offer, product)
        val snapshot = ContractTextSnapshot(UUID.randomUUID(), application.id, offer.id, 1, text, sha256(text), clock.instant())
        jdbc.update(
            "insert into contract_text_snapshots(id,application_id,offer_id,template_version,contract_text,sha256,created_at) values(?,?,?,?,?,?,?)",
            snapshot.id,
            snapshot.applicationId,
            snapshot.offerId,
            snapshot.templateVersion,
            snapshot.text,
            snapshot.sha256,
            Timestamp.from(snapshot.createdAt),
        )
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
            Сумма: ${rubles(offer.amount)}; срок: ${offer.termMonths} мес.; ставка: ${offer.rate}% годовых
            Первый платёж: ${rows.first().payment} ₽; максимальный платёж: ${rows.maxOf { it.payment }} ₽; последний платёж: ${rows.last().payment} ₽
            Всего к возврату: ${calculation.totalPayment} ₽; переплата: ${calculation.overpayment} ₽
            ${if (product.revolving) "Возобновляемый лимит: расчёт при полном использовании. Платежи меняются; остаток возвращается в последний месяц." else "Последний платёж учитывает округление и закрывает долг."}
            В графике указаны номера месяцев; календарные даты платежей не определены.
        """.trimIndent()
    }

    fun legacySummary(application: LoanApplication, offer: Offer, product: Product): String =
        "Сводка сохранённых условий. Исходный подписываемый текст не сохранён.\n" + render(application, offer, product).substringAfter('\n')

    companion object {
        fun sha256(
            text: String,
        ): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
