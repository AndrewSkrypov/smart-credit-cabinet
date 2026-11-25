package ru.creditcabinet.offer.application

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.offer.ContractTextView
import ru.creditcabinet.offer.domain.Offer
import ru.creditcabinet.offer.domain.SigningSessionRecord
import java.util.UUID

interface OfferActions {
    fun accept(id: UUID): LoanApplication

    fun decline(id: UUID, comment: String?): LoanApplication

    fun requestCode(id: UUID): SigningSessionRecord

    fun sign(id: UUID, code: String): LoanApplication

    fun current(applicationId: UUID): Offer?

    fun contract(id: UUID): ContractTextView
}
