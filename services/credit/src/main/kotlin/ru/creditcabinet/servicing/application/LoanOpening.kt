package ru.creditcabinet.servicing.application

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.offer.Loan
import java.time.Instant
import java.util.UUID

interface LoanOpening {
    /** Transactional application/job lock; a superseded lease cannot publish any result. */
    fun lockExecution(job: ru.creditcabinet.application.domain.PipelineJob): Boolean
    fun bookedLoan(applicationId: UUID): Loan?
    fun requireSignedSnapshot(applicationId: UUID, cycleId: UUID)

    /** Called inside the same Credit transaction that saves the booked immutable Loan. */
    fun prepareOrConfirmed(application: LoanApplication, loan: Loan, cycleId: UUID): Boolean

    /** Waiting for a durable receipt is not an execution failure and does not spend its budget. */
    fun awaitReceipt(jobId: UUID, until: Instant)
}
