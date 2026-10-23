package ru.creditcabinet.application.service

import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.notifications.NotificationEmailKind
import ru.creditcabinet.common.notifications.PlatformNotification
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.product.domain.ProductMatcher.rubles

/**
 * What people are told about a transition, inside the cabinet (no e-mail or SMS in the scenario).
 * The client's company hears about what needs them and about outcomes; underwriters hear about
 * applications that wait for them. Steps in between (data complete, sources fetched) stay quiet.
 */
object TransitionNotices {
    fun of(application: LoanApplication, entry: JournalEntry, productName: String): List<PlatformNotification> {
        val number = application.number
        val clientLink = "/client/applications/${application.id}"
        val bankLink = "/bank/applications/${application.id}"
        fun client(type: String, title: String, body: String) =
            PlatformNotification(
                recipientCompanyId = application.company.id,
                type = type,
                title = title,
                body = body,
                link = clientLink,
                dedupKey = "${entry.id}:client",
            )
        fun underwriters(title: String, body: String) =
            PlatformNotification(
                recipientRole = Roles.UNDERWRITER,
                type = PlatformNotification.NEW_REVIEW,
                title = title,
                body = body,
                link = bankLink,
                dedupKey = "${entry.id}:underwriters",
            )
        val summary = "${application.company.name ?: "ИНН ${application.company.inn}"} · $productName · ${rubles(application.terms.amount)}"

        val notices = when (entry.event) {
            ApplicationEvent.DATA_INCOMPLETE.name, ApplicationEvent.REQUEST_INFO.name -> listOf(
                client(PlatformNotification.ACTION_REQUIRED, "Банк просит дополнить заявку № $number", "Откройте заявку: там список документов и срок ответа."),
            )
            ApplicationEvent.AUTO_OFFER.name, ApplicationEvent.APPROVE.name, ApplicationEvent.COUNTER_OFFER.name -> listOf(
                if (entry.reasonCode == "COUNTER") {
                    client(
                        PlatformNotification.ACTION_REQUIRED,
                        "Есть решение по заявке № $number",
                        "Банк готов выдать кредит на других условиях — сравните их с запрошенными.",
                    )
                } else {
                    client(PlatformNotification.ACTION_REQUIRED, "Заявка № $number одобрена", "Примите предложение банка, чтобы перейти к подписанию.")
                },
            )
            ApplicationEvent.AUTO_REJECT.name, ApplicationEvent.REJECT.name -> listOf(
                client(
                    PlatformNotification.APPLICATION_STATUS,
                    "Решение по заявке № $number",
                    "Банк не может выдать кредит на этих условиях. Причины и что можно изменить — в заявке.",
                ),
            )
            ApplicationEvent.TO_MANUAL_REVIEW.name, ApplicationEvent.SOURCE_TIMED_OUT.name -> listOf(
                client(PlatformNotification.APPLICATION_STATUS, "Заявку № $number рассматривает специалист", "Обычно это занимает до одного рабочего дня."),
                underwriters("Заявка № $number ждёт решения", summary),
            )
            ApplicationEvent.DISBURSED.name -> listOf(
                client(PlatformNotification.APPLICATION_STATUS, "Деньги по заявке № $number зачислены", "График платежей — в заявке."),
            )
            ApplicationEvent.ACCEPT_OFFER.name -> listOf(
                client(PlatformNotification.ACTION_REQUIRED, "Договор по заявке № $number готов к подписанию", "Откройте заявку, чтобы подписать договор."),
            )
            ApplicationEvent.INFO_EXPIRED.name, ApplicationEvent.OFFER_EXPIRED.name -> listOf(
                client(PlatformNotification.APPLICATION_STATUS, "Заявка № $number закрыта", "Срок ответа истёк. Можно подать новую заявку."),
            )
            ApplicationEvent.WITHDRAW.name ->
                if (entry.fromStatus == ApplicationStatus.MANUAL_REVIEW) listOf(underwriters("Клиент отозвал заявку № $number", summary)) else emptyList()
            else -> emptyList()
        }
        return notices.map { notice ->
            val kind = when (entry.event) {
                ApplicationEvent.DATA_INCOMPLETE.name, ApplicationEvent.REQUEST_INFO.name -> NotificationEmailKind.DOCUMENTS_REQUESTED
                ApplicationEvent.AUTO_OFFER.name, ApplicationEvent.APPROVE.name, ApplicationEvent.COUNTER_OFFER.name -> NotificationEmailKind.OFFER_READY
                ApplicationEvent.AUTO_REJECT.name, ApplicationEvent.REJECT.name -> NotificationEmailKind.REFUSED
                ApplicationEvent.DISBURSED.name -> NotificationEmailKind.DISBURSED
                ApplicationEvent.ACCEPT_OFFER.name -> NotificationEmailKind.SIGNING_READY
                ApplicationEvent.TO_MANUAL_REVIEW.name, ApplicationEvent.SOURCE_TIMED_OUT.name ->
                    NotificationEmailKind.REVIEW_REQUIRED.takeIf { notice.recipientRole != null }
                else -> null
            }
            val deadline = entry.payload["deadline"]?.toString().takeIf {
                kind in
                    setOf(NotificationEmailKind.DOCUMENTS_REQUESTED, NotificationEmailKind.OFFER_READY)
            }
            notice.copy(email = kind?.let { NotificationEmail(it, deadline) })
        }
    }
}
