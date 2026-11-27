package ru.creditcabinet.access.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import ru.creditcabinet.access.application.SecurityMail
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.common.mail.MailCapabilities
import ru.creditcabinet.common.outbox.DurableOutbox
import java.util.UUID

@Component
class OutboxSecurityMail(private val mail: MailCapabilities, private val outbox: DurableOutbox, private val mapper: ObjectMapper) : SecurityMail {
    override fun enabled(): Boolean = mail.enabled()

    override fun enqueue(purpose: LinkPurpose, referenceId: UUID) {
        val key = when (purpose) {
            LinkPurpose.PASSWORD_RESET -> "password-reset:$referenceId"
            LinkPurpose.COMPANY_INVITATION -> "company-invitation:$referenceId"
        }
        outbox.enqueue("email-security", key, mapper.writeValueAsString(SecurityMailReference(purpose, referenceId)))
    }
}

internal data class SecurityMailReference(val purpose: LinkPurpose, val referenceId: UUID)
