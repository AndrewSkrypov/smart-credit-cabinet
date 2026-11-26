@file:Suppress("ktlint:standard:filename")

package ru.creditcabinet.servicing.domain

import java.util.UUID

interface LoanAccountStore {
    fun existsByApplicationId(applicationId: UUID): Boolean
    fun findByApplicationId(applicationId: UUID): LoanAccount?
    fun findById(id: UUID): LoanAccount?
    fun findByIdForUpdate(id: UUID): LoanAccount?
    fun create(account: LoanAccount)
    fun saveState(account: LoanAccount, newTranche: LoanTranche? = null)
}
