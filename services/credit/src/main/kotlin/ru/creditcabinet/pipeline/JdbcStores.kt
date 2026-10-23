package ru.creditcabinet.pipeline

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.InfoItem
import ru.creditcabinet.application.domain.InfoRequest
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionOutcome
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.assessment.domain.EnrichmentStore
import ru.creditcabinet.assessment.domain.FactorScore
import ru.creditcabinet.assessment.domain.ManualReason
import ru.creditcabinet.assessment.domain.Rating
import ru.creditcabinet.assessment.domain.Recommendation
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.RuleSet
import ru.creditcabinet.assessment.domain.RuleSetVersion
import ru.creditcabinet.assessment.domain.RuleSets
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.StopFactor
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.offer.Loans
import ru.creditcabinet.offer.Offer
import ru.creditcabinet.offer.OfferKind
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.offer.OfferStatus
import ru.creditcabinet.offer.SigningSessionRecord
import ru.creditcabinet.offer.SigningSessions
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

private fun ResultSet.uuid(column: String): UUID? = getObject(column, UUID::class.java)

private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

private fun ts(instant: Instant?): Timestamp? = instant?.let(Timestamp::from)

@Suppress("UNCHECKED_CAST")
private fun ResultSet.strings(column: String): List<String> = (getArray(column)?.array as Array<String>?)?.toList().orEmpty()

@Component
class JdbcRuleSets(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) : RuleSets {
    private data class Params(
        val maxLoad: Double,
        val revenueMismatch: Double,
        val ratingCutoffs: Map<Rating, Int>,
        val ratingMargins: Map<Rating, Double>,
        val lowRiskOkved: Set<String>,
        val highRiskOkved: Set<String>,
        val weakAmountShare: Double,
        val weakRevenueShare: Double,
    )

    private val rowMapper = RowMapper { rs, _ ->
        val params = mapper.readValue<Params>(rs.getString("params"))
        with(params) {
            RuleSet(
                rs.getInt(
                    "version",
                ),
                maxLoad, revenueMismatch, ratingCutoffs, ratingMargins, lowRiskOkved, highRiskOkved, weakAmountShare, weakRevenueShare,
            )
        }
    }

    override fun current(): RuleSet = jdbc.query("select * from rule_sets order by version desc limit 1", rowMapper).first()

    override fun find(version: Int): RuleSet? = jdbc.query("select * from rule_sets where version = :v", mapOf("v" to version), rowMapper).firstOrNull()

    override fun history(): List<RuleSetVersion> =
        jdbc.query("select version, created_at, created_by, comment from rule_sets order by version desc", emptyMap<String, Any>()) { rs, _ ->
            RuleSetVersion(rs.getInt("version"), rs.instant("created_at")!!, rs.uuid("created_by"), rs.getString("comment"))
        }

    override fun create(rules: RuleSet, createdBy: UUID, comment: String): RuleSet {
        val params =
            with(rules) { Params(maxLoad, revenueMismatch, ratingCutoffs, ratingMargins, lowRiskOkved, highRiskOkved, weakAmountShare, weakRevenueShare) }
        // The primary key settles two administrators saving at once: the second insert fails and is retried by them.
        val version = jdbc.queryForObject(
            """
            insert into rule_sets (version, params, created_by, comment)
            values ((select coalesce(max(version), 0) + 1 from rule_sets), cast(:params as jsonb), :by, :comment)
            returning version
            """.trimIndent(),
            mapOf("params" to mapper.writeValueAsString(params), "by" to createdBy, "comment" to comment),
            Int::class.java,
        )!!
        return rules.copy(version = version)
    }
}

@Component
class JdbcEnrichmentStore(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) : EnrichmentStore {
    override fun save(applicationId: UUID, kind: SourceKind, data: Any, at: Instant) {
        jdbc.update(
            """
            insert into enrichment_results (application_id, source, payload, fetched_at) values (:application, :source, cast(:payload as jsonb), :at)
            on conflict (application_id, source) do update set payload = excluded.payload, fetched_at = excluded.fetched_at
            """.trimIndent(),
            mapOf("application" to applicationId, "source" to kind.name, "payload" to mapper.writeValueAsString(data), "at" to ts(at)),
        )
    }

    override fun load(applicationId: UUID): Map<SourceKind, Any> =
        jdbc.query("select source, payload from enrichment_results where application_id = :application", mapOf("application" to applicationId)) { rs, _ ->
            val kind = SourceKind.valueOf(rs.getString("source"))
            val payload = rs.getString("payload")
            kind to when (kind) {
                SourceKind.REGISTRY -> mapper.readValue<RegistryInfo>(payload)
                SourceKind.TAX -> mapper.readValue<TaxInfo>(payload)
                SourceKind.BUREAU -> mapper.readValue<BureauInfo>(payload)
                SourceKind.BANK -> mapper.readValue<BankInfo>(payload)
            }
        }.toMap()
}

@Component
class JdbcDecisionRepository(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) : DecisionRepository {
    private val rowMapper = RowMapper { rs, _ ->
        Decision(
            id = rs.uuid("id")!!,
            applicationId = rs.uuid("application_id")!!,
            ruleSetVersion = rs.getInt("rule_set_version"),
            decidedByType = ActorType.valueOf(rs.getString("decided_by_type")),
            decidedBy = rs.uuid("decided_by"),
            score = rs.getObject("score") as Int?,
            rating = rs.getString("rating")?.let(Rating::valueOf),
            stopFactors = rs.strings("stop_factors").map(StopFactor::valueOf),
            factors = mapper.readValue(rs.getString("factors"), FACTORS),
            outcome = DecisionOutcome.valueOf(rs.getString("outcome")),
            reasonCode = rs.getString("reason_code"),
            manualReasons = rs.strings("manual_reasons").map(ManualReason::valueOf),
            recommendation = rs.getString("recommendation")?.let { mapper.readValue<Recommendation>(it) },
            inputs = mapper.readValue(rs.getString("inputs"), INPUTS),
            comment = rs.getString("comment"),
            createdAt = rs.instant("created_at")!!,
        )
    }

    override fun save(decision: Decision) {
        jdbc.update(
            """
            insert into decisions (id, application_id, rule_set_version, decided_by_type, decided_by, score, rating, stop_factors, factors, outcome,
                                   reason_code, manual_reasons, recommendation, inputs, comment, created_at)
            values (:id, :application, :version, :byType, :by, :score, :rating, :stops, cast(:factors as jsonb), :outcome,
                    :reason, :manual, cast(:recommendation as jsonb), cast(:inputs as jsonb), :comment, :at)
            """.trimIndent(),
            mapOf(
                "id" to decision.id,
                "application" to decision.applicationId,
                "version" to decision.ruleSetVersion,
                "byType" to decision.decidedByType.name,
                "by" to decision.decidedBy,
                "score" to decision.score,
                "rating" to decision.rating?.name,
                "stops" to decision.stopFactors.map { it.name }.toTypedArray(),
                "factors" to mapper.writeValueAsString(decision.factors),
                "outcome" to decision.outcome.name,
                "reason" to decision.reasonCode,
                "manual" to decision.manualReasons.map { it.name }.toTypedArray(),
                "recommendation" to decision.recommendation?.let(mapper::writeValueAsString),
                "inputs" to mapper.writeValueAsString(decision.inputs),
                "comment" to decision.comment,
                "at" to ts(decision.createdAt),
            ),
        )
    }

    override fun find(id: UUID): Decision? = jdbc.query("select * from decisions where id = :id", mapOf("id" to id), rowMapper).firstOrNull()

    override fun latest(applicationId: UUID): Decision? =
        jdbc.query("select * from decisions where application_id = :a order by seq desc limit 1", mapOf("a" to applicationId), rowMapper).firstOrNull()

    override fun latestAutomatic(applicationId: UUID): Decision? =
        jdbc.query(
            "select * from decisions where application_id = :a and decided_by_type = 'SYSTEM' order by seq desc limit 1",
            mapOf("a" to applicationId),
            rowMapper,
        ).firstOrNull()

    private companion object {
        val FACTORS = object : TypeReference<List<FactorScore>>() {}
        val INPUTS = object : TypeReference<Map<String, Any?>>() {}
    }
}

@Component
class JdbcInfoRequests(private val jdbc: NamedParameterJdbcTemplate, private val mapper: ObjectMapper) : InfoRequests {
    override fun save(request: InfoRequest) {
        jdbc.update(
            """
            insert into info_requests (id, application_id, items, comment, deadline, requested_by_type, requested_by, created_at)
            values (:id, :application, cast(:items as jsonb), :comment, :deadline, :byType, :by, :at)
            """.trimIndent(),
            mapOf(
                "id" to request.id,
                "application" to request.applicationId,
                "items" to mapper.writeValueAsString(request.items),
                "comment" to request.comment,
                "deadline" to ts(request.deadline),
                "byType" to request.requestedBy.type.name,
                "by" to request.requestedBy.id,
                "at" to ts(request.createdAt),
            ),
        )
    }

    override fun open(applicationId: UUID): InfoRequest? =
        jdbc.query(
            "select * from info_requests where application_id = :a and resolved_at is null order by created_at desc limit 1",
            mapOf("a" to applicationId),
        ) { rs, _ ->
            InfoRequest(
                id = rs.uuid("id")!!,
                applicationId = rs.uuid("application_id")!!,
                items = mapper.readValue(rs.getString("items"), ITEMS),
                comment = rs.getString("comment"),
                deadline = rs.instant("deadline")!!,
                requestedBy = Actor(ActorType.valueOf(rs.getString("requested_by_type")), rs.uuid("requested_by")),
                createdAt = rs.instant("created_at")!!,
            )
        }.firstOrNull()

    override fun resolve(id: UUID, at: Instant, responseComment: String?) {
        jdbc.update(
            "update info_requests set resolved_at = :at, response_comment = :comment where id = :id",
            mapOf("id" to id, "at" to ts(at), "comment" to responseComment),
        )
    }

    private companion object {
        val ITEMS = object : TypeReference<List<InfoItem>>() {}
    }
}

@Component
class JdbcOffers(private val jdbc: NamedParameterJdbcTemplate) : OfferRepository {
    override fun save(offer: Offer) {
        jdbc.update(
            """
            insert into offers (id, application_id, decision_id, kind, product_code, amount, term_months, rate, monthly_payment, total_payment,
                                valid_until, status, created_at, product_version)
            values (:id, :application, :decision, :kind, :product, :amount, :term, :rate, :payment, :total, :validUntil, :status, :at, :productVersion)
            """.trimIndent(),
            mapOf(
                "id" to offer.id,
                "application" to offer.applicationId,
                "decision" to offer.decisionId,
                "kind" to offer.kind.name,
                "product" to offer.productCode,
                "productVersion" to offer.productVersion,
                "amount" to offer.amount,
                "term" to offer.termMonths,
                "rate" to offer.rate,
                "payment" to offer.monthlyPayment,
                "total" to offer.totalPayment,
                "validUntil" to ts(offer.validUntil),
                "status" to offer.status.name,
                "at" to ts(offer.createdAt),
            ),
        )
    }

    override fun current(applicationId: UUID): Offer? =
        jdbc.query("select * from offers where application_id = :a order by created_at desc limit 1", mapOf("a" to applicationId)) { rs, _ ->
            Offer(
                id = rs.uuid("id")!!,
                applicationId = rs.uuid("application_id")!!,
                decisionId = rs.uuid("decision_id")!!,
                kind = OfferKind.valueOf(rs.getString("kind")),
                productCode = rs.getString("product_code"),
                productVersion = rs.getLong("product_version"),
                amount = rs.getLong("amount"),
                termMonths = rs.getInt("term_months"),
                rate = rs.getBigDecimal("rate"),
                monthlyPayment = rs.getBigDecimal("monthly_payment"),
                totalPayment = rs.getBigDecimal("total_payment"),
                validUntil = rs.instant("valid_until")!!,
                status = OfferStatus.valueOf(rs.getString("status")),
                createdAt = rs.instant("created_at")!!,
                decidedAt = rs.instant("decided_at"),
            )
        }.firstOrNull()

    override fun updateStatus(id: UUID, status: OfferStatus, at: Instant) {
        jdbc.update("update offers set status = :status, decided_at = :at where id = :id", mapOf("id" to id, "status" to status.name, "at" to ts(at)))
    }
}

@Component
class JdbcSigningSessions(private val jdbc: NamedParameterJdbcTemplate) : SigningSessions {
    override fun save(session: SigningSessionRecord) {
        jdbc.update(
            """
            insert into signing_sessions (id, application_id, offer_id, external_session_id, code, expires_at, created_at)
            values (:id, :application, :offer, :external, :code, :expires, :at)
            """.trimIndent(),
            mapOf(
                "id" to session.id,
                "application" to session.applicationId,
                "offer" to session.offerId,
                "external" to session.externalSessionId,
                "code" to session.code,
                "expires" to ts(session.expiresAt),
                "at" to ts(session.createdAt),
            ),
        )
    }

    override fun latest(applicationId: UUID): SigningSessionRecord? =
        jdbc.query("select * from signing_sessions where application_id = :a order by created_at desc limit 1", mapOf("a" to applicationId)) { rs, _ ->
            SigningSessionRecord(
                id = rs.uuid("id")!!,
                applicationId = rs.uuid("application_id")!!,
                offerId = rs.uuid("offer_id")!!,
                externalSessionId = rs.uuid("external_session_id")!!,
                code = rs.getString("code"),
                expiresAt = rs.instant("expires_at")!!,
                signatureId = rs.uuid("signature_id"),
                signedAt = rs.instant("signed_at"),
                signedBy = rs.uuid("signed_by"),
                createdAt = rs.instant("created_at")!!,
            )
        }.firstOrNull()

    override fun markSigned(id: UUID, signatureId: UUID, signedAt: Instant, signedBy: UUID) {
        jdbc.update(
            "update signing_sessions set signature_id = :signature, signed_at = :at, signed_by = :by where id = :id",
            mapOf("id" to id, "signature" to signatureId, "at" to ts(signedAt), "by" to signedBy),
        )
    }
}

@Component
class JdbcLoans(private val jdbc: NamedParameterJdbcTemplate) : Loans {
    override fun save(loan: Loan) {
        jdbc.update(
            """
            insert into loans (application_id, contract_number, loan_account, settlement_account, amount, term_months, rate, revolving, disbursed_at, maturity_date)
            values (:application, :contract, :loanAccount, :settlement, :amount, :term, :rate, :revolving, :at, :maturity)
            on conflict (application_id) do nothing
            """.trimIndent(),
            mapOf(
                "application" to loan.applicationId,
                "contract" to loan.contractNumber,
                "loanAccount" to loan.loanAccount,
                "settlement" to loan.settlementAccount,
                "amount" to loan.amount,
                "term" to loan.termMonths,
                "rate" to loan.rate,
                "revolving" to loan.revolving,
                "at" to ts(loan.disbursedAt),
                "maturity" to loan.maturityDate,
            ),
        )
    }

    override fun find(applicationId: UUID): Loan? =
        jdbc.query("select * from loans where application_id = :a", mapOf("a" to applicationId)) { rs, _ ->
            Loan(
                applicationId = rs.uuid("application_id")!!,
                contractNumber = rs.getString("contract_number"),
                loanAccount = rs.getString("loan_account"),
                settlementAccount = rs.getString("settlement_account"),
                amount = rs.getLong("amount"),
                termMonths = rs.getInt("term_months"),
                rate = rs.getBigDecimal("rate"),
                revolving = rs.getBoolean("revolving"),
                disbursedAt = rs.instant("disbursed_at")!!,
                maturityDate = rs.getDate("maturity_date").toLocalDate(),
            )
        }.firstOrNull()
}
