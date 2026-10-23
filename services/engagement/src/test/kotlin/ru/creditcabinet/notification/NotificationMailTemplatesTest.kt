package ru.creditcabinet.notification

import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.notifications.NotificationEmailKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationMailTemplatesTest {
    @Test
    fun `all action and outcome templates use cabinet-only details`() {
        val link = "https://cabinet.example.test/client/applications/1"
        for (kind in NotificationEmailKind.entries) {
            val (subject, text) = NotificationMailTemplates.render(NotificationEmail(kind), link)
            assertTrue(subject.isNotBlank() && text.contains("Откройте кабинет:"))
            assertTrue(text.endsWith(link))
            assertFalse(text.contains("₽") || text.contains("ИНН") || text.contains("R_SCORE"))
            assertFalse(text.contains("одобрение гарантировано"))
        }
    }
}
