@file:Suppress("ktlint:standard:filename")

package ru.creditcabinet.servicing.domain

import java.util.UUID

data class LoanOperationReceipt(val fingerprint: String, val result: LoanAccount)

interface LoanAccountStore {
    fun existsByApplicationId(applicationId: UUID): Boolean
    fun findByApplicationId(applicationId: UUID): LoanAccount?
    fun findByIdForUpdate(id: UUID, companyId: UUID): LoanAccount?
    fun create(account: LoanAccount)
    fun saveState(account: LoanAccount, newTranche: LoanTranche? = null)
    fun findOperation(loanId: UUID, key: UUID): LoanOperationReceipt?
    fun recordOperation(loanId: UUID, key: UUID, receipt: LoanOperationReceipt)
}
