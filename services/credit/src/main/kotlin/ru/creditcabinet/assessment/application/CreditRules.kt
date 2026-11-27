package ru.creditcabinet.assessment.application

import org.springframework.stereotype.Service
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.application.CreditFailure.NotFound
import ru.creditcabinet.assessment.domain.RuleSet
import ru.creditcabinet.assessment.domain.RuleSetVersion
import ru.creditcabinet.assessment.domain.RuleSets
import ru.creditcabinet.common.security.Roles

data class RuleHistory(val rule: RuleSet, val versions: List<RuleSetVersion>)

interface CreditRules {
    fun current(): RuleHistory

    fun version(version: Int): Pair<RuleSet, RuleSetVersion>

    fun create(candidate: RuleSet, expectedVersion: Int, comment: String): RuleHistory
}

@Service
class CreditRuleAdministration(
    private val ruleSets: RuleSets,
    private val callerContext: CreditCaller,
) : CreditRules {
    override fun current(): RuleHistory {
        callerContext.requireBankStaff()
        return RuleHistory(ruleSets.current(), ruleSets.history())
    }

    override fun version(version: Int): Pair<RuleSet, RuleSetVersion> {
        callerContext.requireBankStaff()
        val rules = ruleSets.find(version) ?: throw NotFound("Версия правил не найдена")
        return rules to ruleSets.history().first { it.version == version }
    }

    override fun create(candidate: RuleSet, expectedVersion: Int, comment: String): RuleHistory {
        val claims = callerContext.requireRole(Roles.RISK_ADMIN)
        val problems = candidate.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        val saved = ruleSets.create(candidate, expectedVersion, claims.userId, comment.trim(), claims.fullName, claims.role)
        return RuleHistory(saved, ruleSets.history())
    }
}
