package ru.creditcabinet.application

import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.TransitionNotices
import ru.creditcabinet.common.notifications.PlatformNotification
import ru.creditcabinet.product.domain.FinancingPurpose
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransitionNoticesTest {
    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val application = LoanApplication(
        number = 1042,
        company = CompanySnapshot(UUID.randomUUID(), "000100000004", "ИП Котова", LegalForm.ENTREPRENEUR),
        createdBy = UUID.randomUUID(),
        terms = ApplicationTerms("WORKING_CAPITAL", FinancingPurpose.WORKING_CAPITAL, amount = 1_000_000, termMonths = 12),
        status = ApplicationStatus.SCORING,
        createdAt = now,
    )

    private fun notices(event: ApplicationEvent, from: ApplicationStatus = ApplicationStatus.SCORING, reason: String? = null): List<PlatformNotification> {
        val entry = application.copy(status = from).move(
            event,
            Actor.SYSTEM.takeIf { Actor.SYSTEM.type in event.actors } ?: Actor(event.actors.first(), UUID.randomUUID()),
            now,
            reasonCode = reason,
        ).entry
        return TransitionNotices.of(application, entry, "Кредит на пополнение оборота")
    }

    @Test
    fun `a counter offer asks the client's company to act`() {
        val notice = notices(ApplicationEvent.AUTO_OFFER, reason = "COUNTER").single()
        assertEquals(application.company.id, notice.recipientCompanyId)
        assertEquals(PlatformNotification.ACTION_REQUIRED, notice.type)
        assertEquals("Есть решение по заявке № 1042", notice.title)
        assertEquals("/client/applications/${application.id}", notice.link)
    }

    @Test
    fun `manual review tells the client and calls the underwriters`() {
        val notices = notices(ApplicationEvent.TO_MANUAL_REVIEW)
        assertEquals(2, notices.size)
        val bank = notices.single { it.recipientRole == "UNDERWRITER" }
        assertEquals(PlatformNotification.NEW_REVIEW, bank.type)
        assertTrue(bank.body.contains("ИП Котова"))
        assertTrue(bank.link!!.startsWith("/bank/applications/"))
    }

    @Test
    fun `steps the client need not know about stay quiet`() {
        assertTrue(notices(ApplicationEvent.DATA_COMPLETE, from = ApplicationStatus.SUBMITTED).isEmpty())
        assertTrue(notices(ApplicationEvent.ENRICHMENT_DONE, from = ApplicationStatus.ENRICHMENT).isEmpty())
    }
}
