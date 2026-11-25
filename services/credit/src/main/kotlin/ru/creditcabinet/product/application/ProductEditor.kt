package ru.creditcabinet.product.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductLifecycleStatus
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
    fun create(candidate: Product, reason: String): ProductRevision {
        val actor = CurrentCaller.requireRole(Roles.RISK_ADMIN)
        val normalizedCode = candidate.code.trim()
        require(normalizedCode.isNotBlank() && normalizedCode.length <= 32 && CODE_PATTERN.matches(normalizedCode)) {
            "Код продукта — от 1 до 32 символов (латиница в верхнем регистре, цифры и подчёркивание)"
        }
        require(reason.isNotBlank() && reason.length <= 1000) { "Опишите причину изменения — до 1000 символов" }
        val draft = candidate.copy(
            code = normalizedCode,
            version = 1,
            lifecycleStatus = ProductLifecycleStatus.DRAFT,
        )
        val problems = draft.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        return versions.create(draft, actor.userId, reason.trim(), clock.instant(), actor.fullName, actor.role)
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

    @Transactional
    fun publish(code: String, expectedVersion: Long, reason: String): ProductRevision = transition(
        code = code,
        expectedVersion = expectedVersion,
        reason = reason,
        target = ProductLifecycleStatus.ACTIVE,
        allowedFrom = setOf(ProductLifecycleStatus.DRAFT),
        invalidMessage = "Опубликовать можно только продукт в статусе DRAFT",
    )

    @Transactional
    fun archive(code: String, expectedVersion: Long, reason: String): ProductRevision = transition(
        code = code,
        expectedVersion = expectedVersion,
        reason = reason,
        target = ProductLifecycleStatus.ARCHIVED,
        allowedFrom = setOf(ProductLifecycleStatus.DRAFT, ProductLifecycleStatus.ACTIVE),
        invalidMessage = "Продукт уже находится в архиве",
    )

    @Transactional
    fun restore(code: String, expectedVersion: Long, reason: String): ProductRevision = transition(
        code = code,
        expectedVersion = expectedVersion,
        reason = reason,
        target = ProductLifecycleStatus.ACTIVE,
        allowedFrom = setOf(ProductLifecycleStatus.ARCHIVED),
        invalidMessage = "Восстановить можно только архивный продукт",
    )

    private fun transition(
        code: String,
        expectedVersion: Long,
        reason: String,
        target: ProductLifecycleStatus,
        allowedFrom: Set<ProductLifecycleStatus>,
        invalidMessage: String,
    ): ProductRevision {
        val actor = CurrentCaller.requireRole(Roles.RISK_ADMIN)
        require(expectedVersion > 0) { "Укажите текущую версию продукта" }
        require(reason.isNotBlank() && reason.length <= 1000) { "Опишите причину изменения — до 1000 символов" }
        val current = catalog.latest(code) ?: throw NotFoundException("Продукт не найден")
        if (current.version != expectedVersion) {
            throw ConflictException("Условия только что изменил другой администратор — обновите карточку и проверьте изменения")
        }
        require(current.lifecycleStatus in allowedFrom) { invalidMessage }
        val updated = current.copy(lifecycleStatus = target)
        val problems = updated.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        return versions.replace(updated, expectedVersion, actor.userId, reason.trim(), clock.instant(), actor.fullName, actor.role)
    }

    private companion object {
        val CODE_PATTERN = Regex("^[A-Z0-9_]+$")
    }
}
