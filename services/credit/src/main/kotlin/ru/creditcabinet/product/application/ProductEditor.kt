package ru.creditcabinet.product.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.ProductVersions
import ru.creditcabinet.product.domain.problems
import java.time.Clock

@Service
class ProductEditor(private val catalog: ProductCatalog, private val versions: ProductVersions, private val clock: Clock) {
    fun list(): List<Product> {
        CurrentCaller.requireBankStaff()
        return versions.list()
    }

    fun history(code: String): List<ProductRevision> {
        CurrentCaller.requireBankStaff()
        if (catalog.latest(code) == null) throw NotFoundException("Продукт не найден")
        return versions.history(code)
    }

    @Transactional
    fun replace(candidate: Product, expectedVersion: Long, reason: String): ProductRevision {
        val actor = CurrentCaller.requireRole(Roles.RISK_ADMIN)
        require(expectedVersion > 0) { "Укажите текущую версию продукта" }
        require(reason.isNotBlank() && reason.length <= 1000) { "Опишите причину изменения — до 1000 символов" }
        val problems = candidate.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        return versions.replace(candidate, expectedVersion, actor.userId, reason.trim(), clock.instant(), actor.fullName, actor.role)
    }
}
