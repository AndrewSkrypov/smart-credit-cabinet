package ru.creditcabinet.application.application

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.Transition

interface CommitTransition {
    fun commit(transition: Transition): LoanApplication
}
