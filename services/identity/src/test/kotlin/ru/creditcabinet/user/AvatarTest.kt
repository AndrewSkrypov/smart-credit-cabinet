package ru.creditcabinet.user

import ru.creditcabinet.user.domain.Avatar
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AvatarTest {
    private fun dataUrl(format: String, bytes: ByteArray) = "data:image/$format;base64," + Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `blank means initials and presets are checked against the list`() {
        assertNull(Avatar.normalize(null))
        assertNull(Avatar.normalize("  "))
        assertEquals("preset:bank", Avatar.normalize(" preset:bank "))
        assertFailsWith<IllegalArgumentException> { Avatar.normalize("preset:../../etc") }
    }

    @Test
    fun `photos must be small rasters whose bytes match the declared format`() {
        val jpeg = dataUrl("jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00))
        assertEquals(jpeg, Avatar.normalize(jpeg))
        val webp = dataUrl("webp", "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))
        assertEquals(webp, Avatar.normalize(webp))
        assertFailsWith<IllegalArgumentException> { Avatar.normalize(dataUrl("webp", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()))) }
        assertFailsWith<IllegalArgumentException> { Avatar.normalize(dataUrl("gif", "GIF89a".toByteArray())) }
        assertFailsWith<IllegalArgumentException> { Avatar.normalize("https://example.test/me.png") }
        val tooBig = ByteArray(Avatar.MAX_IMAGE_BYTES + 1).also {
            it[0] = 0xff.toByte()
            it[1] = 0xd8.toByte()
            it[2] = 0xff.toByte()
        }
        assertFailsWith<IllegalArgumentException> { Avatar.normalize(dataUrl("jpeg", tooBig)) }
    }
}
