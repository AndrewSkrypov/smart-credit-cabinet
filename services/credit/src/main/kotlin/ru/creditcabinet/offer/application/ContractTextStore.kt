package ru.creditcabinet.offer.application

import ru.creditcabinet.offer.domain.ContractTextSnapshot
import java.util.UUID

interface ContractTextStore {
    fun find(offerId: UUID): ContractTextSnapshot?

    /** Serializes snapshot creation for one offer inside the signing transaction. */
    fun lockOffer(applicationId: UUID, offerId: UUID)

    fun insert(snapshot: ContractTextSnapshot)
}
