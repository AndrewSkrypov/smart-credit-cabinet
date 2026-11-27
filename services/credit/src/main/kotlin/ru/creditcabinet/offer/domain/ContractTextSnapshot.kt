package ru.creditcabinet.offer.domain

import java.time.Instant
import java.util.UUID

data class ContractTextSnapshot(
    val id: UUID,
    val applicationId: UUID,
    val offerId: UUID,
    val templateVersion: Int,
    val text: String,
    val sha256: String,
    val createdAt: Instant,
)
