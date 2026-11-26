package ru.creditcabinet.pipeline.application

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.offer.domain.Loan

fun interface DisbursedLoanAccounts {
    fun openOnDisbursement(application: LoanApplication, loan: Loan)
}
