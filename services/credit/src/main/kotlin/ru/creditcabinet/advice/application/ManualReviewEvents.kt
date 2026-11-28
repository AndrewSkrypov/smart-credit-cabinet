package ru.creditcabinet.advice.application

import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.LoanApplication

/** Records a durable outbox event whenever an application enters MANUAL_REVIEW. */
fun interface ManualReviewEvents {
    fun onTransition(application: LoanApplication, entry: JournalEntry)
}
