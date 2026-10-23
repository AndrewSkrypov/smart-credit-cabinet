package ru.creditcabinet.access

import org.springframework.http.HttpStatus
import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.common.exception.ApiException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessGrantTest {
    @Test
    fun `only a matching token before expiry and without consumption or revocation is usable`() {
        val id = UUID.randomUUID()
        val token = "$id.${"a".repeat(43)}"
        val expires = Instant.parse("2026-10-22T12:00:00Z")
        val grant = AccessGrant(id, AccessGrant.hash(token), expires, null, null)
        assertEquals(id, AccessGrant.tokenId(token))
        assertEquals(64, grant.tokenHash.length)
        grant.requireValid(token, expires.minusSeconds(1))
        assertTrue(grant.available(expires.minusSeconds(1)))
        for (invalid in listOf(grant, grant.copy(usedAt = expires.minusSeconds(10)), grant.copy(revokedAt = expires.minusSeconds(10)))) {
            val at = if (invalid == grant) expires else expires.minusSeconds(1)
            assertFalse(invalid.available(at))
            assertEquals(HttpStatus.GONE, assertFailsWith<ApiException> { invalid.requireValid(token, at) }.httpStatus)
        }
        assertFailsWith<ApiException> { grant.requireValid(token.replace("a", "b"), expires.minusSeconds(1)) }
        for (bad in listOf("", "wrong", "$id.abc", "$id.${"a".repeat(43)}.x", "${"x".repeat(36)}.${"a".repeat(43)}")) {
            assertFailsWith<ApiException> { AccessGrant.tokenId(bad) }
        }
    }
}
