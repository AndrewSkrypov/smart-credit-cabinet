package ru.creditcabinet.product.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.product.application.ProductEditor
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductImpact
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import ru.creditcabinet.product.domain.ProductRevision
import java.math.BigDecimal
import java.time.Instant

data class ProductCreate(
    val code: String,
    val name: String,
    val description: String,
    val purposes: Set<FinancingPurpose>,
    val minAmount: Long,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    val baseRate: BigDecimal,
    val autoApprovalLimit: Long,
    val revolving: Boolean,
    val effectiveFrom: Instant? = null,
    val effectiveTo: Instant? = null,
    val agentCommissionRateBps: Int = 150,
    val reason: String,
) {
    fun product() = Product(
        code = code.trim(),
        name = name.trim(),
        description = description.trim(),
        purposes = purposes,
        minAmount = minAmount,
        maxAmount = maxAmount,
        minTermMonths = minTermMonths,
        maxTermMonths = maxTermMonths,
        baseRate = baseRate,
        autoApprovalLimit = autoApprovalLimit,
        revolving = revolving,
        version = 1,
        lifecycleStatus = ProductLifecycleStatus.DRAFT,
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        agentCommissionRateBps = agentCommissionRateBps,
    )
}

data class ProductEdit(
    val expectedVersion: Long,
    val name: String,
    val description: String,
    val purposes: Set<FinancingPurpose>,
    val minAmount: Long,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    val baseRate: BigDecimal,
    val autoApprovalLimit: Long,
    val revolving: Boolean,
    val active: Boolean? = null,
    val lifecycleStatus: ProductLifecycleStatus? = null,
    val effectiveFrom: Instant? = null,
    val effectiveTo: Instant? = null,
    val agentCommissionRateBps: Int = 150,
    val reason: String,
) {
    fun product(code: String) = Product(
        code = code,
        name = name.trim(),
        description = description.trim(),
        purposes = purposes,
        minAmount = minAmount,
        maxAmount = maxAmount,
        minTermMonths = minTermMonths,
        maxTermMonths = maxTermMonths,
        baseRate = baseRate,
        autoApprovalLimit = autoApprovalLimit,
        revolving = revolving,
        lifecycleStatus = lifecycleStatus ?: if (active == false) ProductLifecycleStatus.ARCHIVED else ProductLifecycleStatus.ACTIVE,
        effectiveFrom = effectiveFrom,
        effectiveTo = effectiveTo,
        agentCommissionRateBps = agentCommissionRateBps,
    )
}

data class ProductLifecycleTransition(
    val expectedVersion: Long,
    val reason: String,
)

@Tag(name = "Версии кредитных продуктов")
@RestController
@RequestMapping("/api/v1/bank/products")
class ProductEditorController(private val editor: ProductEditor) {
    @Operation(summary = "Все действующие и выключенные продукты для сотрудников банка")
    @GetMapping
    fun list(): List<Product> = editor.list()

    @Operation(summary = "Создать новый продукт в статусе DRAFT (риск-администратор)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: ProductCreate): ProductRevision =
        editor.create(request.product(), request.reason)

    @Operation(summary = "Карточка продукта и неизменяемая история условий")
    @GetMapping("/{code}")
    fun history(@PathVariable code: String): List<ProductRevision> = editor.history(code)

    @Operation(summary = "Оценка влияния изменений/архивации продукта на действующие заявки и предложения")
    @GetMapping("/{code}/impact")
    fun impact(@PathVariable code: String): ProductImpact = editor.impact(code)

    @Operation(summary = "Сохранить новую версию или выключить продукт (риск-администратор)")
    @PutMapping("/{code}")
    fun replace(@PathVariable code: String, @RequestBody request: ProductEdit): ProductRevision =
        editor.replace(request.product(code), request.expectedVersion, request.reason)

    @Operation(summary = "Опубликовать черновик продукта (DRAFT -> ACTIVE)")
    @PostMapping("/{code}/publish")
    fun publish(@PathVariable code: String, @RequestBody request: ProductLifecycleTransition): ProductRevision =
        editor.publish(code, request.expectedVersion, request.reason)

    @Operation(summary = "Отправить продукт в архив (DRAFT/ACTIVE -> ARCHIVED)")
    @PostMapping("/{code}/archive")
    fun archive(@PathVariable code: String, @RequestBody request: ProductLifecycleTransition): ProductRevision =
        editor.archive(code, request.expectedVersion, request.reason)

    @Operation(summary = "Восстановить архивный продукт (ARCHIVED -> ACTIVE)")
    @PostMapping("/{code}/restore")
    fun restore(@PathVariable code: String, @RequestBody request: ProductLifecycleTransition): ProductRevision =
        editor.restore(code, request.expectedVersion, request.reason)
}
