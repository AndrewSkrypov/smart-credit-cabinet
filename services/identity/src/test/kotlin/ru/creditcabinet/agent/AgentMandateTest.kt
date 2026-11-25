package ru.creditcabinet.agent

import ru.creditcabinet.agent.domain.AgentMandate
import ru.creditcabinet.agent.domain.AgentMandateStatus
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserRole
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentMandateTest {
    @Test
    fun `agent user cannot belong to a company or sign contracts`() {
        val agent = User(
            fullName = "Мария Соколова",
            email = "agent@demo.test",
            role = UserRole.AGENT,
        )
        assertEquals(UserRole.AGENT, agent.role)
        assertNull(agent.companyId)
        assertFalse(agent.canSign)

        assertFailsWith<IllegalArgumentException> {
            User(
                fullName = "Мария Соколова",
                email = "agent@demo.test",
                role = UserRole.AGENT,
                companyId = UUID.randomUUID(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            User(
                fullName = "Мария Соколова",
                email = "agent@demo.test",
                role = UserRole.AGENT,
                canSign = true,
            )
        }
    }

    @Test
    fun `active mandate transitions to revoked once`() {
        val mandate = AgentMandate(
            agentUserId = UUID.randomUUID(),
            companyId = UUID.randomUUID(),
        )
        assertTrue(mandate.isActive)
        assertNull(mandate.revokedAt)

        val revokedAt = Instant.parse("2026-11-25T10:00:00Z")
        val revoked = mandate.revoke(revokedAt)
        assertFalse(revoked.isActive)
        assertEquals(AgentMandateStatus.REVOKED, revoked.status)
        assertEquals(revokedAt, revoked.revokedAt)

        assertFailsWith<IllegalArgumentException> {
            revoked.revoke(revokedAt)
        }
    }
}
