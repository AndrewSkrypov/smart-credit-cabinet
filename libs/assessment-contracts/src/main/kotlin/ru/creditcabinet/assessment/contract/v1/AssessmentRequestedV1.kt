package ru.creditcabinet.assessment.contract.v1

import ru.creditcabinet.assessment.domain.BankInfo
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

const val ASSESSMENT_PROTOCOL_V1 = 1

private val requiredRatings = listOf(RatingV1.A, RatingV1.B, RatingV1.C, RatingV1.D)
private val innPattern = Regex("[0-9]{10}(?:[0-9]{2})?")
private val okvedClass = Regex("[0-9]{2}")

data class ProductRefV1(val code: String, val version: Long) {
    init {
        require(code.isNotBlank() && version > 0) { "Invalid product reference" }
    }
}

/** Identifies a single saved enrichment job and its pinned inputs, including the order of alternatives. */
data class AssessmentCycleV1(
    val generation: String,
    val cycleId: UUID,
    val applicationId: UUID,
    val applicationVersion: Long,
    val ruleSetVersion: Int,
    val selectedProduct: ProductRefV1,
    val catalog: List<ProductRefV1>,
    val candidatePreApprovalId: UUID?,
) {
    init {
        require(generation.isNotBlank()) { "Missing assessment generation" }
        require(applicationVersion >= 0 && ruleSetVersion > 0) { "Invalid application or rule-set version" }
        require(catalog.map { it.code }.toSet().size == catalog.size) { "Duplicate product code in cycle catalog" }
        require(catalog.none { it.code == selectedProduct.code && it != selectedProduct }) { "Selected product version differs from catalog" }
    }
}

data class AssessmentRequestedV1(
    val cycle: AssessmentCycleV1,
    val inn: String,
    val terms: AssessmentTermsV1,
    val selectedProduct: ProductSnapshotV1,
    val catalog: List<ProductSnapshotV1>,
    val rules: RuleSnapshotV1,
    val candidatePreApproval: PreApprovalSnapshotV1?,
    val bureauConsent: BureauConsentV1?,
    val bankPrefetch: BankPrefetchV1?,
    val startsAt: Instant,
    val deadline: Instant,
    val protocolVersion: Int = ASSESSMENT_PROTOCOL_V1,
) {
    init {
        require(protocolVersion == ASSESSMENT_PROTOCOL_V1) { "Unsupported assessment protocol version" }
        require(innPattern.matches(inn)) { "Invalid company INN" }
        require(startsAt < deadline) { "Assessment deadline must follow cycle start" }
        require(terms.productCode == selectedProduct.ref.code && selectedProduct.ref == cycle.selectedProduct) {
            "Selected product does not match cycle or terms"
        }
        require(catalog.map { it.ref } == cycle.catalog) { "Catalog snapshot does not match cycle order or versions" }
        require(catalog.none { it.ref == selectedProduct.ref && it != selectedProduct }) { "Selected product snapshots differ" }
        require(rules.version == cycle.ruleSetVersion) { "Rule snapshot does not match cycle version" }
        require(bureauConsent == null || bureauConsent.applicationId == cycle.applicationId) {
            "Bureau consent does not belong to the application"
        }
        require(bankPrefetch == null || bankPrefetch.receivedAt <= startsAt) { "Prefetch receipt follows cycle start" }
        require(candidatePreApproval == null || candidatePreApproval.id == cycle.candidatePreApprovalId) {
            "Preapproval does not match cycle candidate"
        }
        require(candidatePreApproval == null || candidatePreApproval.applicationId == cycle.applicationId) {
            "Preapproval does not belong to the application"
        }
    }
}

data class AssessmentTermsV1(
    val productCode: String,
    val amount: Long,
    val termMonths: Int,
    val declaredRevenue12m: Long?,
) {
    init {
        require(productCode.isNotBlank() && amount > 0 && termMonths > 0) { "Invalid application terms" }
    }
}

data class ProductSnapshotV1(
    val ref: ProductRefV1,
    val name: String,
    val minAmount: Long,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    val baseRate: BigDecimal,
    val autoApprovalLimit: Long,
    val revolving: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Missing product name" }
        require(minAmount > 0 && maxAmount >= minAmount && autoApprovalLimit >= 0) { "Invalid product amount bounds" }
        require(minTermMonths > 0 && maxTermMonths >= minTermMonths && baseRate.signum() > 0) { "Invalid product term or rate" }
    }
}

enum class RatingV1 { A, B, C, D, E }

data class RuleSnapshotV1(
    val version: Int,
    val maxLoad: Double,
    val revenueMismatch: Double,
    val ratingCutoffs: Map<RatingV1, Int>,
    val ratingMargins: Map<RatingV1, Double>,
    val lowRiskOkved: Set<String>,
    val highRiskOkved: Set<String>,
    val weakAmountShare: Double,
    val weakRevenueShare: Double,
) {
    init {
        require(version > 0 && maxLoad in 0.1..0.8 && revenueMismatch in 0.05..1.0) { "Invalid rule set version or limits" }
        require(requiredRatings.all { ratingCutoffs[it]?.let { cutoff -> cutoff in 1..100 } == true }) { "Missing or invalid A–D rating thresholds" }
        require(requiredRatings.zipWithNext().all { (a, b) -> ratingCutoffs.getValue(a) > ratingCutoffs.getValue(b) }) {
            "Rating thresholds must descend"
        }
        require(requiredRatings.all { ratingMargins[it]?.let { margin -> margin in 0.0..20.0 } == true }) {
            "Missing or invalid A–D rating margins"
        }
        require(requiredRatings.zipWithNext().all { (a, b) -> ratingMargins.getValue(a) <= ratingMargins.getValue(b) }) {
            "Rating margins must be nondecreasing"
        }
        require((lowRiskOkved + highRiskOkved).all(okvedClass::matches) && lowRiskOkved.intersect(highRiskOkved).isEmpty()) {
            "Invalid or overlapping OKVED risk classes"
        }
        require(weakAmountShare in 0.0..1.0 && weakRevenueShare in 0.0..1.0) { "Invalid weak-rating shares" }
    }
}

data class PreApprovalSnapshotV1(
    val id: UUID,
    val applicationId: UUID,
    val productCode: String,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    val rate: BigDecimal,
    val validUntil: Instant,
) {
    init {
        require(productCode.isNotBlank() && maxAmount > 0) { "Invalid preapproval product or amount" }
        require(minTermMonths > 0 && maxTermMonths >= minTermMonths && rate.signum() > 0) { "Invalid preapproval term or rate" }
    }
}

data class BureauConsentV1(val applicationId: UUID)

data class BankPrefetchV1(val fact: BankInfo, val receivedAt: Instant)
