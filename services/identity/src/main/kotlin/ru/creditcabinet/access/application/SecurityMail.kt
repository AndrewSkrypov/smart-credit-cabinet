package ru.creditcabinet.access.application

import ru.creditcabinet.access.domain.LinkPurpose
import java.util.UUID

interface SecurityMail {
    fun enabled(): Boolean
    fun enqueue(purpose: LinkPurpose, referenceId: UUID)
}
