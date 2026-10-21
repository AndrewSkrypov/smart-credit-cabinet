package ru.creditcabinet.application.domain

import java.time.Instant
import java.util.UUID

interface ApplicationRepository {
    fun find(id: UUID): LoanApplication?

    /** Saves and returns the stored state (number and version filled in). A stale version is a 409. */
    fun save(application: LoanApplication): LoanApplication
    fun listByCompany(companyId: UUID): List<LoanApplication>

    /** Applications the bank works with (no drafts), newest status change first. */
    fun listForBank(statuses: Set<ApplicationStatus>?): List<LoanApplication>
    fun hasActive(companyId: UUID, productCode: String): Boolean
}

interface ApplicationJournal {
    fun append(entry: JournalEntry)
    fun list(applicationId: UUID): List<JournalEntry>
}

/** Steps of the credit pipeline, written in the transaction of the transition that needs them. */
enum class PipelineStep { DATA_CHECK, ENRICHMENT, SCORING, DISBURSEMENT, EXPIRE_INFO_REQUEST, EXPIRE_OFFER }

interface PipelineJobs {
    fun enqueue(applicationId: UUID, step: PipelineStep, runAt: Instant)

    /** Pending steps of a withdrawn application are not run. */
    fun cancelPending(applicationId: UUID)
}

interface IdempotencyKeys {
    /** The application a previous request with this key was about, or null for a new key. */
    fun find(userId: UUID, key: String, operation: String): UUID?
    fun record(userId: UUID, key: String, operation: String, applicationId: UUID)
}

/** Companies are owned by identity; credit asks for the ИНН and legal form when an application starts. */
interface CompanyDirectory {
    fun find(companyId: UUID): CompanySnapshot?
}
