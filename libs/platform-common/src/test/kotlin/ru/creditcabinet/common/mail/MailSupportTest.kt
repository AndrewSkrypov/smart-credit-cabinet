package ru.creditcabinet.common.mail

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailSupportTest {
    @Test
    fun `mail is off without configuration and auto mode discovers the optional service`() {
        assertFalse(MailCapabilities(MailSettings()) { true }.enabled())
        assertFalse(MailCapabilities(MailSettings(mode = "auto")) { false }.enabled())
        val reachable = AtomicBoolean(true)
        val capabilities = MailCapabilities(MailSettings(mode = "auto")) { reachable.get() }
        assertTrue(capabilities.enabled())
        reachable.set(false)
        assertTrue(capabilities.enabled(), "SMTP failure does not disable configured preferences or recovery")
        assertTrue(MailCapabilities(MailSettings(mode = "on")) { false }.enabled())
        assertFalse(MailCapabilities(MailSettings(mode = "auto", host = "smtp.example.test")) { true }.enabled())
    }

    @Test
    fun `cabinet links cannot change origin or inherit credentials`() {
        val settings = MailSettings(publicCabinetUrl = "https://cabinet.example.test")
        assertEquals("https://cabinet.example.test/client/applications/1", settings.cabinetLink("/client/applications/1"))
        assertFailsWith<IllegalArgumentException> { settings.cabinetLink("//evil.example/test") }
        assertFailsWith<IllegalArgumentException> { settings.cabinetLink("https://evil.example") }
        assertFailsWith<IllegalArgumentException> { MailSettings(publicCabinetUrl = "https://secret@cabinet.example.test") }
        assertFailsWith<IllegalArgumentException> { MailSettings(publicCabinetUrl = "https://cabinet.example.test/?redirect=evil") }
    }
}
