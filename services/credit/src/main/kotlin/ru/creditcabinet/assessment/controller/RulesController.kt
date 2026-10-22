package ru.creditcabinet.assessment.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.assessment.domain.Rating
import ru.creditcabinet.assessment.domain.RuleSet
import ru.creditcabinet.assessment.domain.RuleSetVersion
import ru.creditcabinet.assessment.domain.RuleSets
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles

/** The thresholds of the credit policy; the same shape is read and written. */
data class RuleParams(
    val maxLoad: Double,
    val revenueMismatch: Double,
    val ratingCutoffs: Map<Rating, Int>,
    val ratingMargins: Map<Rating, Double>,
    val lowRiskOkved: Set<String>,
    val highRiskOkved: Set<String>,
    val weakAmountShare: Double,
    val weakRevenueShare: Double,
) {
    fun toRuleSet(version: Int) =
        RuleSet(
            version, maxLoad, revenueMismatch, ratingCutoffs, ratingMargins,
            lowRiskOkved.map(
                String::trim,
            ).toSet(),
            highRiskOkved.map(String::trim).toSet(), weakAmountShare, weakRevenueShare,
        )

    companion object {
        fun of(rules: RuleSet) =
            with(rules) { RuleParams(maxLoad, revenueMismatch, ratingCutoffs, ratingMargins, lowRiskOkved, highRiskOkved, weakAmountShare, weakRevenueShare) }
    }
}

data class RulesResponse(val version: Int, val params: RuleParams, val versions: List<RuleSetVersion>)

data class NewRulesRequest(
    @field:Valid val params: RuleParams,
    @field:NotBlank(message = "Опишите, что и зачем меняется — это попадёт в историю версий")
    @field:Size(max = 1000)
    val comment: String,
)

@Tag(name = "Версии кредитной политики")
@RestController
@RequestMapping("/api/v1/rules")
class RulesController(private val ruleSets: RuleSets) {
    @Operation(summary = "Действующие пороги кредитной политики и история версий")
    @GetMapping
    fun current(): RulesResponse {
        CurrentCaller.requireBankStaff()
        val current = ruleSets.current()
        return RulesResponse(current.version, RuleParams.of(current), ruleSets.history())
    }

    /**
     * A new version of the thresholds; it applies to assessments from now on, decisions already
     * made keep the version they were made with.
     */
    @Operation(summary = "Сохранить новую версию порогов (риск-администратор)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: NewRulesRequest): RulesResponse {
        val claims = CurrentCaller.requireRole(Roles.RISK_ADMIN)
        val candidate = request.params.toRuleSet(version = 0)
        val problems = candidate.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
        val saved = try {
            ruleSets.create(candidate, claims.userId, request.comment.trim())
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("Правила только что изменил другой администратор — обновите страницу")
        }
        return RulesResponse(saved.version, RuleParams.of(saved), ruleSets.history())
    }
}
