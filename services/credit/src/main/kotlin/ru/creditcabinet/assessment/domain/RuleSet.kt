package ru.creditcabinet.assessment.domain

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
