package ru.creditcabinet.access.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.AccountLinkIssuer
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.access.domain.SecurityMailReference
import ru.creditcabinet.common.mail.MailTransport
import ru.creditcabinet.common.mail.OutgoingMail
import ru.creditcabinet.common.outbox.DeliveryHandler
import ru.creditcabinet.user.domain.UserStatus
import java.time.Clock

@Component
class SecurityMailDelivery(
    private val store: AccountAccessPort,
    private val links: AccountLinkIssuer,
    private val mapper: ObjectMapper,
    private val transport: MailTransport,
    private val clock: Clock = Clock.systemUTC(),
) : DeliveryHandler {
    override val channel = "email-security"

    override fun deliver(payload: String) {
        val reference = mapper.readValue<SecurityMailReference>(payload)
        val id = reference.referenceId
        val recipient: String
        val subject: String
        val text: String
        when (reference.purpose) {
            LinkPurpose.PASSWORD_RESET -> {
                val reset = store.reset(id) ?: return
                if (!reset.grant.available(clock.instant())) return
                val account = store.account(reset.userId) ?: return
                if (account.user.status != UserStatus.ACTIVE || account.user.credentialsVersion != reset.credentialsVersion) return
                check(reset.keyVersion == links.keyVersion && reset.grant.tokenHash == links.hash(reference.purpose, id)) { "Identity link key changed" }
                recipient = account.user.email
                subject = "Восстановление пароля в кабинете"
                text = "Чтобы установить новый пароль, откройте кабинет по ссылке. Ссылка действует 30 минут и используется один раз. " +
                    "Если вы не запрашивали восстановление, не переходите по ссылке."
            }
            LinkPurpose.COMPANY_INVITATION -> {
                val invitation = store.invitation(id) ?: return
                if (!invitation.grant.available(clock.instant())) return
                check(invitation.keyVersion == links.keyVersion && invitation.grant.tokenHash == links.hash(reference.purpose, id)) {
                    "Identity link key changed"
                }
                recipient = invitation.email
                subject = "Приглашение в кабинет компании"
                text = "Чтобы принять приглашение и установить пароль, откройте кабинет по ссылке. Ссылка действует 72 часа и используется один раз. " +
                    "Если вы не ожидали приглашения, не переходите по ссылке."
            }
        }
        transport.send(OutgoingMail(recipient, subject, "$text\n\n${links.link(reference.purpose, id)}", "${reference.purpose}:$id"))
    }
}
