package ru.creditcabinet.assessment.domain

enum class Rating { A, B, C, D, E }

/**
 * Thresholds of the credit policy that the risk administrator changes, as one versioned set
 * (table rule_sets). The point bands of each factor are part of the scorecard itself.
 */
data class RuleSet(
    val version: Int,
    /** Highest share of monthly revenue that all loan payments, the new one included, may take. */
    val maxLoad: Double,
    /** Declared revenue may differ from the tax service's by this share before an underwriter looks. */
    val revenueMismatch: Double,
    val ratingCutoffs: Map<Rating, Int>,
    /** Percentage points added to the product's base rate for each rating. */
    val ratingMargins: Map<Rating, Double>,
    /** ОКВЭД classes (first two digits) of low and high risk; the rest are medium. */
    val lowRiskOkved: Set<String>,
    val highRiskOkved: Set<String>,
    /** Rating D gets at most this share of the requested amount… */
    val weakAmountShare: Double,
    /** …and at most this share of the annual revenue. */
    val weakRevenueShare: Double,
) {
    fun ratingOf(score: Int): Rating = listOf(Rating.A, Rating.B, Rating.C, Rating.D).firstOrNull { score >= ratingCutoffs.getValue(it) } ?: Rating.E

    fun industryRisk(okvedCode: String): IndustryRisk {
        val group = okvedCode.take(2)
        return when (group) {
            in highRiskOkved -> IndustryRisk.HIGH
            in lowRiskOkved -> IndustryRisk.LOW
            else -> IndustryRisk.MEDIUM
        }
    }
}

enum class IndustryRisk(val title: String) { LOW("низкий"), MEDIUM("средний"), HIGH("высокий") }

interface RuleSets {
    fun current(): RuleSet
    fun find(version: Int): RuleSet?
}
