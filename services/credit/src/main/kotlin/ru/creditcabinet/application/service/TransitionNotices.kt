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
        val agentLink = "/agent/applications/${application.id}"
        fun client(type: String, title: String, body: String) =
            PlatformNotification(
                recipientCompanyId = application.company.id,
                type = type,
                title = title,
                body = body,
                link = clientLink,
                dedupKey = "${entry.id}:client",
            )
        fun agent(type: String, title: String, body: String) =
            application.agentId?.let { agentId ->
                PlatformNotification(
                    recipientId = agentId,
                    type = type,
                    title = title,
                    body = body,
                    link = agentLink,
                    dedupKey = "${entry.id}:agent",
                )
            }
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
            ApplicationEvent.REQUEST_CLIENT_CONFIRMATION.name -> listOf(
                client(
                    PlatformNotification.ACTION_REQUIRED,
                    "Агент подготовил заявку № $number — требуется подтверждение",
                    "Проверьте параметры заявки, дайте согласие на запрос в БКИ и отправьте её в банк.",
                ),
            )
            ApplicationEvent.RETURN_TO_AGENT.name -> listOfNotNull(
                agent(
                    PlatformNotification.ACTION_REQUIRED,
                    "Клиент вернул заявку № $number на доработку",
                    entry.comment?.takeIf { it.isNotBlank() } ?: "Откройте заявку, чтобы внести правки и повторно отправить на подтверждение.",
                ),
            )
            ApplicationEvent.CONFIRM_AND_SUBMIT.name -> listOfNotNull(
                agent(
                    PlatformNotification.APPLICATION_STATUS,
                    "Клиент подтвердил и отправил заявку № $number",
                    "Заявка передана в банк на проверку и скоринг.",
                ),
            )
            ApplicationEvent.DATA_INCOMPLETE.name, ApplicationEvent.REQUEST_INFO.name -> listOfNotNull(
                client(
                    PlatformNotification.ACTION_REQUIRED,
                    "Банк просит дополнить заявку № $number",
                    "Откройте заявку: там список документов и срок ответа.",
                ),
                agent(
                    PlatformNotification.ACTION_REQUIRED,
                    "Банк запросил документы по заявке № $number",
                    "Загрузите недостающие документы в карточке заявки.",
                ),
            )
            ApplicationEvent.AUTO_OFFER.name, ApplicationEvent.APPROVE.name, ApplicationEvent.COUNTER_OFFER.name -> listOfNotNull(
                if (entry.reasonCode == "COUNTER") {
                    client(
                        PlatformNotification.ACTION_REQUIRED,
                        "Есть решение по заявке № $number",
                        "Банк готов выдать кредит на других условиях — сравните их с запрошенными.",
                    )
                } else {
                    client(PlatformNotification.ACTION_REQUIRED, "Заявка № $number одобрена", "Примите предложение банка, чтобы перейти к подписанию.")
                },
                agent(
                    PlatformNotification.APPLICATION_STATUS,
                    "По заявке № $number сформировано предложение банка",
                    "Ожидается решение клиента по предложению.",
                ),
            )
            ApplicationEvent.AUTO_REJECT.name, ApplicationEvent.REJECT.name -> listOfNotNull(
                client(
                    PlatformNotification.APPLICATION_STATUS,
                    "Решение по заявке № $number",
                    "Банк не может выдать кредит на этих условиях. Причины и что можно изменить — в заявке.",
                ),
                agent(PlatformNotification.APPLICATION_STATUS, "Заявка № $number отклонена", "Комиссия по заявке отменена."),
            )
            ApplicationEvent.TO_MANUAL_REVIEW.name, ApplicationEvent.SOURCE_TIMED_OUT.name -> listOfNotNull(
                client(PlatformNotification.APPLICATION_STATUS, "Заявку № $number рассматривает специалист", "Обычно это занимает до одного рабочего дня."),
                underwriters("Заявка № $number ждёт решения", summary),
                agent(
                    PlatformNotification.APPLICATION_STATUS,
                    "Заявка № $number на ручном рассмотрении",
                    "Специалист банка рассмотрит заявку в ближайшее время.",
                ),
            )
            ApplicationEvent.DISBURSED.name -> listOfNotNull(
                client(PlatformNotification.APPLICATION_STATUS, "Деньги по заявке № $number зачислены", "График платежей — в заявке."),
                agent(PlatformNotification.APPLICATION_STATUS, "Кредит по заявке № $number выдан", "Комиссия агента начислена."),
            )
            ApplicationEvent.ACCEPT_OFFER.name -> listOfNotNull(
                client(PlatformNotification.ACTION_REQUIRED, "Договор по заявке № $number готов к подписанию", "Откройте заявку, чтобы подписать договор."),
                agent(PlatformNotification.APPLICATION_STATUS, "Клиент принял предложение по заявке № $number", "Ожидается подписание договора клиентом."),
            )
            ApplicationEvent.INFO_EXPIRED.name, ApplicationEvent.OFFER_EXPIRED.name -> listOfNotNull(
                client(PlatformNotification.APPLICATION_STATUS, "Заявка № $number закрыта", "Срок ответа истёк. Можно подать новую заявку."),
                agent(PlatformNotification.APPLICATION_STATUS, "Заявка № $number закрыта", "Срок ответа истёк, комиссия отменена."),
            )
            ApplicationEvent.DECLINE_OFFER.name -> listOfNotNull(
                agent(PlatformNotification.APPLICATION_STATUS, "Клиент отказался от предложения по заявке № $number", "Заявка закрыта, комиссия отменена."),
            )
            ApplicationEvent.WITHDRAW.name -> listOfNotNull(
                underwriters("Клиент отозвал заявку № $number", summary).takeIf { entry.fromStatus == ApplicationStatus.MANUAL_REVIEW },
                agent(PlatformNotification.APPLICATION_STATUS, "Клиент отозвал заявку № $number", "Заявка отозвана, комиссия отменена."),
            )
            else -> emptyList()
        }
        return notices.map { notice ->
            if (notice.recipientId != null) return@map notice
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
