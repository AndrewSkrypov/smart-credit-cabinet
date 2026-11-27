package ru.creditcabinet.application.domain

import java.time.Instant
import java.util.UUID

interface ApplicationRepository {
    fun find(id: UUID): LoanApplication?

    /** Locks the current application until the caller's transaction completes. */
    fun findForUpdate(id: UUID): LoanApplication?

    /** Saves and returns the stored state (number and version filled in). A stale version is a 409. */
    fun save(application: LoanApplication): LoanApplication

    /** A concurrent creation rejected by the repository is reported as [ApplicationCreationConflict]. */
    fun create(application: LoanApplication): LoanApplication = save(application)
    fun listByCompany(companyId: UUID): List<LoanApplication>
    fun listByAgent(agentId: UUID): List<LoanApplication>

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

data class PipelineJob(
    val id: UUID,
    val applicationId: UUID,
    val step: PipelineStep,
    val attempts: Int,
    val leaseUntil: Instant? = null,
    val businessFailures: Int = 0,
)

interface PipelineJobs {
    /** Adds a step; a step already pending or running for the application is not added twice. */
    fun enqueue(applicationId: UUID, step: PipelineStep, runAt: Instant)

    /** Cancel open steps so late worker callbacks cannot revive a withdrawn application's work. */
    fun cancelPending(applicationId: UUID)

    /**
     * Takes up to [limit] due jobs for this worker until [leaseUntil]: pending ones whose time has
     * come and running ones whose worker died. Other workers skip the rows taken here.
     */
    fun claim(now: Instant, leaseUntil: Instant, limit: Int): List<PipelineJob>
    fun complete(id: UUID)
    fun retry(id: UUID, runAt: Instant, error: String?)
    fun fail(id: UUID, error: String?)

    /** A port outage keeps business attempts and uses durable, capped transport backoff. */
    fun retryDocumentsUnavailable(job: PipelineJob, now: Instant)
    fun retryClaim(job: PipelineJob, now: Instant, runAt: Instant, error: String?)
    fun failClaim(job: PipelineJob, now: Instant, error: String?)
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
