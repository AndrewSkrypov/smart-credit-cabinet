package ru.creditcabinet.application.service

import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.Transition

/** Saves a moved application together with its journal entry, in the caller's transaction. */
@Component
class Transitions(private val applications: ApplicationRepository, private val journal: ApplicationJournal) {
    fun commit(transition: Transition): LoanApplication {
        val saved = applications.save(transition.application)
        journal.append(transition.entry)
        return saved
    }
}
