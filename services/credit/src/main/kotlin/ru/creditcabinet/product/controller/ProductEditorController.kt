package ru.creditcabinet.product.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.product.application.ProductEditor
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductRevision
import java.math.BigDecimal

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
    val active: Boolean,
    val reason: String,
) {
    fun product(code: String) = Product(
        code, name.trim(), description.trim(), purposes, minAmount, maxAmount,
        minTermMonths, maxTermMonths, baseRate, autoApprovalLimit, revolving, active = active,
    )
}

@Tag(name = "Версии кредитных продуктов")
@RestController
@RequestMapping("/api/v1/bank/products")
class ProductEditorController(private val editor: ProductEditor) {
    @Operation(summary = "Все действующие и выключенные продукты для сотрудников банка")
    @GetMapping
    fun list(): List<Product> = editor.list()

    @Operation(summary = "Карточка продукта и неизменяемая история условий")
    @GetMapping("/{code}")
    fun history(@PathVariable code: String): List<ProductRevision> = editor.history(code)

    @Operation(summary = "Сохранить новую версию или выключить продукт (риск-администратор)")
    @PutMapping("/{code}")
    fun replace(@PathVariable code: String, @RequestBody request: ProductEdit): ProductRevision =
        editor.replace(request.product(code), request.expectedVersion, request.reason)
}
