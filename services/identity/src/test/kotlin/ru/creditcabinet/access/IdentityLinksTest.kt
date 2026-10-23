package ru.creditcabinet.access

import org.springframework.mock.env.MockEnvironment
import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.access.infrastructure.IdentityLinks
import ru.creditcabinet.common.mail.MailSettings
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class IdentityLinksTest {
    private val key = "a-separate-link-secret-for-the-test-environment-48bytes"
    private val jwt = "a-separate-jwt-secret-for-the-test-environment-48bytes"

    @Test
    fun `references recreate the same secret only with the same key and purpose`() {
        val first = IdentityLinks(key, "1", jwt, MockEnvironment(), MailSettings())
        val restarted = IdentityLinks(key, "1", jwt, MockEnvironment(), MailSettings())
        val rotated = IdentityLinks("another-separate-link-secret-for-test-48bytes-long", "2", jwt, MockEnvironment(), MailSettings())
        val id = UUID.randomUUID()
        val token = first.token(LinkPurpose.PASSWORD_RESET, id)
        assertEquals(token, restarted.token(LinkPurpose.PASSWORD_RESET, id))
        assertEquals(id, AccessGrant.tokenId(token))
        assertEquals(AccessGrant.hash(token), first.hash(LinkPurpose.PASSWORD_RESET, id))
        assertNotEquals(token, first.token(LinkPurpose.COMPANY_INVITATION, id))
        assertNotEquals(token, rotated.token(LinkPurpose.PASSWORD_RESET, id))
        assertFalse(first.link(LinkPurpose.PASSWORD_RESET, id).contains('?'))
    }

    @Test
    fun `production requires an independent key and HTTPS origin`() {
        val prod = MockEnvironment().withProperty("spring.datasource.password", key).apply { setActiveProfiles("prod") }
        assertFailsWith<IllegalArgumentException> { IdentityLinks(key, "1", jwt, prod, MailSettings(publicCabinetUrl = "https://cabinet.example.test")) }
        prod.setProperty("spring.datasource.password", "different-database-secret")
        assertFailsWith<IllegalArgumentException> { IdentityLinks(key, "1", jwt, prod, MailSettings()) }
        assertFailsWith<IllegalArgumentException> { IdentityLinks(jwt, "1", jwt, prod, MailSettings(publicCabinetUrl = "https://cabinet.example.test")) }
        val links = IdentityLinks(key, "1", jwt, prod, MailSettings(publicCabinetUrl = "https://cabinet.example.test"))
        assertEquals("https://cabinet.example.test/accept-invitation", links.link(LinkPurpose.COMPANY_INVITATION, UUID.randomUUID()).substringBefore('#'))
    }
}
