package ru.creditcabinet.agent.application

import java.util.UUID

internal fun interface McpActorAssertionPort {
    fun redeem(assertion: String): UUID
}
