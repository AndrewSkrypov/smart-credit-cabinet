package ru.creditcabinet.pipeline.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.offer.domain.Loan
import ru.creditcabinet.pipeline.application.DisbursedLoanAccounts
import ru.creditcabinet.servicing.application.LoanServicingService

@Component
class LoanServicingAdapter(private val servicing: LoanServicingService) : DisbursedLoanAccounts {
    override fun openOnDisbursement(application: LoanApplication, loan: Loan) {
        servicing.openOnDisbursement(application, loan)
    }
}
