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

    /** What makes this set unusable, in words the risk administrator can act on; empty when it is fine. */
    fun problems(): List<String> =
        buildList {
            if (maxLoad !in 0.1..0.8) add("Предельная нагрузка — от 10% до 80% выручки")
            if (revenueMismatch !in 0.05..1.0) add("Допустимое расхождение выручки — от 5% до 100%")
            val cutoffs = listOf(Rating.A, Rating.B, Rating.C, Rating.D).map { ratingCutoffs[it] }
            if (cutoffs.any { it == null || it !in 1..100 }) {
                add("Границы рейтингов A–D — баллы от 1 до 100")
            } else if (cutoffs.zipWithNext().any { (higher, lower) -> higher!! <= lower!! }) {
                add("Граница каждого рейтинга должна быть выше следующего: A > B > C > D")
            }
            val margins = listOf(Rating.A, Rating.B, Rating.C, Rating.D).map { ratingMargins[it] }
            if (margins.any { it == null || it !in 0.0..20.0 }) {
                add("Надбавки к ставке — от 0 до 20 п.п.")
            } else if (margins.zipWithNext().any { (better, worse) -> better!! > worse!! }) {
                add("Надбавка для худшего рейтинга не может быть меньше, чем для лучшего")
            }
            val okved = lowRiskOkved + highRiskOkved
            if (okved.any { !it.matches(OKVED_CLASS) }) add("Классы ОКВЭД — две цифры, например 62")
            if (lowRiskOkved.intersect(highRiskOkved).isNotEmpty()) add("Класс ОКВЭД не может быть одновременно низкого и высокого риска")
            if (weakAmountShare !in 0.0..1.0 || weakRevenueShare !in 0.0..1.0) add("Доли для рейтинга D — от 0 до 100%")
        }

    fun industryRisk(okvedCode: String): IndustryRisk {
        val group = okvedCode.take(2)
        return when (group) {
            in highRiskOkved -> IndustryRisk.HIGH
            in lowRiskOkved -> IndustryRisk.LOW
            else -> IndustryRisk.MEDIUM
        }
    }
}

private val OKVED_CLASS = Regex("\\d{2}")

enum class IndustryRisk(val title: String) { LOW("низкий"), MEDIUM("средний"), HIGH("высокий") }

data class RuleSetVersion(
    val version: Int,
    val createdAt: java.time.Instant,
    val createdBy: java.util.UUID?,
    val comment: String?,
    val authorName: String? = null,
    val authorRole: String? = null,
)

interface RuleSets {
    fun current(): RuleSet
    fun find(version: Int): RuleSet?
    fun history(): List<RuleSetVersion>

    /** Stores [rules] as the next version and returns it; earlier versions stay for the decisions made with them. */
    fun create(rules: RuleSet, expectedVersion: Int, createdBy: java.util.UUID, comment: String, authorName: String?, authorRole: String?): RuleSet
}
