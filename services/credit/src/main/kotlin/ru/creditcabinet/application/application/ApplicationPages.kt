package ru.creditcabinet.application.application

import ru.creditcabinet.application.domain.LoanApplication

data class ApplicationPageRows(
    val items: List<LoanApplication>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val countsByGroup: Map<String, Long>,
    val actionRequiredCount: Long,
    val actionRequiredPreview: List<LoanApplication>,
)

fun interface ApplicationPages {
    fun read(page: Long, size: Int, group: String?, q: String, sort: String): ApplicationPageRows
}
