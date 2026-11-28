package ru.creditcabinet.application.application

import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.LoanApplication
import java.util.UUID

interface ApplicationOperations : ApplicationAccess {
    fun create(terms: ApplicationTerms): LoanApplication

    fun updateDraft(id: UUID, terms: ApplicationTerms, expectedVersion: Long?): LoanApplication

    fun submit(id: UUID, idempotencyKey: String?, acceptedProductVersion: Long? = null, expectedVersion: Long? = null): LoanApplication

    fun confirmByClient(
        id: UUID,
        bureauConsent: Boolean,
        acceptedProductVersion: Long? = null,
        acceptedScenarioRevision: Long? = null,
    ): LoanApplication

    fun returnToAgentByClient(id: UUID, comment: String?): LoanApplication

    fun withdraw(id: UUID, comment: String?): LoanApplication

    fun provideInfo(id: UUID, answer: String?): LoanApplication

    fun list(statuses: Set<ApplicationStatus>?): List<LoanApplication>

    fun journal(id: UUID): List<JournalEntry>

    fun canResumeIndependently(application: LoanApplication): Boolean

    fun clientReadView(application: LoanApplication): LoanApplication
}
