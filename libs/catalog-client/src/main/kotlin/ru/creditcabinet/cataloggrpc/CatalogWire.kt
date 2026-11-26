package ru.creditcabinet.cataloggrpc

import com.google.protobuf.Timestamp
import ru.creditcabinet.contracts.catalog.v1.GetSubmissionSnapshotResponse
import ru.creditcabinet.contracts.catalog.v1.ProductVersion
import ru.creditcabinet.contracts.catalog.v1.ProductVersionRef
import ru.creditcabinet.contracts.catalog.v1.RevisionAudit
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.SubmissionProductSnapshot
import ru.creditcabinet.product.domain.problems
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import ru.creditcabinet.contracts.catalog.v1.FinancingPurpose as WirePurpose
import ru.creditcabinet.contracts.catalog.v1.ProductLifecycleStatus as WireLifecycleStatus

private val productCodePattern = Regex("[A-Z0-9_]{1,32}")

fun validProductCode(code: String) = productCodePattern.matches(code)

fun productRef(code: String, version: Long): ProductVersionRef =
    ProductVersionRef.newBuilder().setCode(code).setVersion(version).build()

fun Product.toWire(): ProductVersion = ProductVersion.newBuilder()
    .setRef(productRef(code, version))
    .setName(name)
    .setDescription(description)
    .addAllPurposes(purposes.map { WirePurpose.valueOf("FINANCING_PURPOSE_${it.name}") })
    .setMinAmountRub(minAmount)
    .setMaxAmountRub(maxAmount)
    .setMinTermMonths(minTermMonths)
    .setMaxTermMonths(maxTermMonths)
    .setBaseRateBps(baseRate.movePointRight(2).intValueExact())
    .setAutoApprovalLimitRub(autoApprovalLimit)
    .setRevolving(revolving)
    .setLifecycleStatus(WireLifecycleStatus.valueOf("PRODUCT_LIFECYCLE_STATUS_${lifecycleStatus.name}"))
    .setAgentCommissionRateBps(agentCommissionRateBps)
    .apply {
        this@toWire.effectiveFrom?.let { setEffectiveFrom(it.toWireTimestamp()) }
        this@toWire.effectiveTo?.let { setEffectiveTo(it.toWireTimestamp()) }
    }
    .build()

fun ProductRevision.toWire(): ProductVersion = product.toWire().toBuilder()
    .setAudit(
        RevisionAudit.newBuilder()
            .setReason(reason)
            .setCreatedAt(createdAt.toWireTimestamp())
            .apply {
                this@toWire.authorId?.let { setAuthorId(it.toString()) }
                this@toWire.authorName?.let { setAuthorName(it) }
                this@toWire.authorRole?.let { setAuthorRole(it) }
            },
    )
    .build()

fun ProductVersion.toRevision(): ProductRevision {
    require(hasAudit() && audit.hasCreatedAt() && audit.reason.length <= 1000)
    require(!audit.hasAuthorName() || audit.authorName.length <= 255)
    require(!audit.hasAuthorRole() || audit.authorRole.length <= 40)
    return ProductRevision(
        toProduct(),
        audit.authorId.takeIf { audit.hasAuthorId() }?.let(UUID::fromString),
        audit.reason,
        audit.createdAt.toJavaTime(),
        audit.authorName.takeIf { audit.hasAuthorName() },
        audit.authorRole.takeIf { audit.hasAuthorRole() },
    )
}

fun SubmissionProductSnapshot.toWire(): GetSubmissionSnapshotResponse =
    GetSubmissionSnapshotResponse.newBuilder()
        .setEvaluatedAt(evaluatedAt.toWireTimestamp())
        .setSelected(productRef(selected.code, selected.version))
        .addAllProducts(if (revisions.isEmpty()) products.map(Product::toWire) else revisions.map(ProductRevision::toWire))
        .build()

fun ProductVersion.toProduct(): Product {
    require(hasRef() && validProductCode(ref.code) && ref.version > 0 && hasAgentCommissionRateBps())
    require(purposesList.distinct().size == purposesCount)
    return Product(
        code = ref.code,
        name = name,
        description = description,
        purposes = purposesList.map { FinancingPurpose.valueOf(it.name.removePrefix("FINANCING_PURPOSE_")) }.toSet(),
        minAmount = minAmountRub,
        maxAmount = maxAmountRub,
        minTermMonths = minTermMonths,
        maxTermMonths = maxTermMonths,
        baseRate = BigDecimal.valueOf(baseRateBps.toLong(), 2),
        autoApprovalLimit = autoApprovalLimitRub,
        revolving = revolving,
        version = ref.version,
        lifecycleStatus = ProductLifecycleStatus.valueOf(lifecycleStatus.name.removePrefix("PRODUCT_LIFECYCLE_STATUS_")),
        effectiveFrom = effectiveFrom.takeIf { hasEffectiveFrom() }?.toJavaTime(),
        effectiveTo = effectiveTo.takeIf { hasEffectiveTo() }?.toJavaTime(),
        agentCommissionRateBps = agentCommissionRateBps,
    ).also { require(it.problems().isEmpty()) }
}

fun GetSubmissionSnapshotResponse.toSnapshot(requestedCode: String): SubmissionProductSnapshot {
    require(hasEvaluatedAt() && hasSelected() && selected.code == requestedCode)
    val at = evaluatedAt.toJavaTime()
    val products = productsList.map(ProductVersion::toProduct)
    require(products.distinctBy(Product::code).size == products.size)
    require(products.all { it.active && it.isEffectiveAt(at) })
    val selectedProduct = requireNotNull(products.firstOrNull { it.code == selected.code && it.version == selected.version })
    val revisions = if (productsList.all(ProductVersion::hasAudit)) productsList.map(ProductVersion::toRevision) else emptyList()
    return SubmissionProductSnapshot(at, selectedProduct, products, revisions)
}

private fun Instant.toWireTimestamp(): Timestamp = Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

private fun Timestamp.toJavaTime(): Instant {
    require(seconds in -62_135_596_800L..253_402_300_799L && nanos in 0..999_999_999)
    return Instant.ofEpochSecond(seconds, nanos.toLong())
}
