@file:Suppress("ktlint:standard:filename")

package ru.creditcabinet.servicing.domain

import java.util.UUID

/** A client operation already applied to the account, identified by its key and request fingerprint. */
data class LoanOperationReceipt(val operationKey: UUID, val fingerprint: String)

interface LoanAccountStore {
    fun existsByApplicationId(applicationId: UUID): Boolean
    fun findByApplicationId(applicationId: UUID): LoanAccount?
    fun findById(id: UUID): LoanAccount?
    fun findByIdForUpdate(id: UUID): LoanAccount?
    fun create(account: LoanAccount)
    fun saveState(account: LoanAccount, newTranche: LoanTranche? = null)
    fun findOperation(loanId: UUID, operationKey: UUID): LoanOperationReceipt?
    fun recordOperation(loanId: UUID, receipt: LoanOperationReceipt)
}
