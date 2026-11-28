package ru.creditcabinet.financing.scenario.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.swagger.v3.oas.annotations.media.Schema
import ru.creditcabinet.financing.scenario.application.FinancingPreview
import ru.creditcabinet.financing.scenario.application.FinancingPreviewForm
import ru.creditcabinet.financing.scenario.domain.ScenarioInputs
import java.time.Instant
import java.time.LocalDate

enum class FinancingScenarioPreviewMode { WEB_FORM, SAVED_SCENARIO }

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class FinancingDraftTermsOverride(
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    @JsonDeserialize(using = PreviewLongDeserializer::class)
    val amount: Long,
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    @JsonDeserialize(using = PreviewIntDeserializer::class)
    val baseTermMonths: Int,
) {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun unknownField(name: String, value: JsonNode) {
        throw IllegalArgumentException("Неизвестное поле в условиях черновика")
    }
}

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
data class FinancingScenarioPreviewRequest(
    val mode: FinancingScenarioPreviewMode,
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    @JsonDeserialize(using = PreviewIntDeserializer::class)
    val delayDays: Int,
    @JsonProperty(required = true)
    @JsonSetter(nulls = Nulls.FAIL)
    @JsonDeserialize(using = PreviewIntDeserializer::class)
    val alternativeTermMonths: Int,
    @JsonDeserialize(using = PreviewLongDeserializer::class)
    val openingBalance: Long? = null,
    @JsonDeserialize(using = PreviewLongDeserializer::class)
    val expectedReceiptAmount: Long? = null,
    val expectedReceiptDate: LocalDate? = null,
    val paymentDate: LocalDate? = null,
    val draftTermsOverride: FinancingDraftTermsOverride? = null,
) {
    @JsonAnySetter
    @Suppress("UNUSED_PARAMETER")
    fun unknownField(name: String, value: JsonNode) {
        throw IllegalArgumentException("Неизвестное поле в параметрах примерки")
    }
}

internal class PreviewIntDeserializer : JsonDeserializer<Int>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Int =
        if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            parser.intValue
        } else {
            context.reportInputMismatch(Int::class.java, "Для расчёта требуется целое число")
        }
}

internal class PreviewLongDeserializer : JsonDeserializer<Long>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): Long =
        if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            parser.longValue
        } else {
            context.reportInputMismatch(Long::class.java, "Для расчёта требуется целое число")
        }
}

internal fun FinancingScenarioPreviewRequest.webForm(): FinancingPreviewForm? = when (mode) {
    FinancingScenarioPreviewMode.SAVED_SCENARIO -> {
        require(
            openingBalance == null &&
                expectedReceiptAmount == null &&
                expectedReceiptDate == null &&
                paymentDate == null &&
                draftTermsOverride == null,
        ) { "Передайте только задержку и второй срок для сохранённого сценария" }
        null
    }
    FinancingScenarioPreviewMode.WEB_FORM -> {
        val terms = requireNotNull(draftTermsOverride) { "Укажите сумму и базовый срок черновика" }
        FinancingPreviewForm(
            terms.amount,
            terms.baseTermMonths,
            ScenarioInputs(
                requireNotNull(openingBalance) { "Укажите исходный остаток" },
                requireNotNull(expectedReceiptAmount) { "Укажите сумму поступления" },
                requireNotNull(expectedReceiptDate) { "Укажите ожидаемую дату поступления" },
                requireNotNull(paymentDate) { "Укажите дату платежа" },
                delayDays,
                alternativeTermMonths,
                terms.baseTermMonths,
            ),
        )
    }
}

data class FinancingScenarioPreviewBasis(
    val applicationVersion: String,
    val productCode: String,
    val productVersion: String,
    val savedScenarioRevision: String?,
)

enum class FinancingPaymentModel { ANNUITY, REVOLVING }

data class FinancingScenarioPreviewInputs(
    val currency: String,
    val paymentModel: FinancingPaymentModel,
    val loanAmountRub: String,
    val annualRatePercent: String,
    val baseTermMonths: Int,
    val alternativeTermMonths: Int,
    val openingBalanceRub: String,
    val expectedReceiptAmountRub: String,
    val expectedReceiptDate: LocalDate,
    val paymentDate: LocalDate,
    val delayDays: Int,
    val receiptIncludedByPayment: Boolean,
    val lastIncludedDelayDays: Long?,
)

data class FinancingScenarioPreviewOption(
    val termMonths: Int,
    val monthlyPaymentRub: String,
    val totalInterestRub: String,
    val balanceOnPaymentDateRub: String,
)

data class FinancingScenarioPreview(
    val source: String,
    val asOf: Instant,
    val preview: Boolean,
    val basis: FinancingScenarioPreviewBasis,
    val inputsUsed: FinancingScenarioPreviewInputs,
    val options: List<FinancingScenarioPreviewOption>,
    val cabinetPath: String,
)

internal fun FinancingPreview.toContract(asOf: Instant) = FinancingScenarioPreview(
    source = "credit",
    asOf = asOf,
    preview = true,
    basis = FinancingScenarioPreviewBasis(
        checkNotNull(application.version).toString(),
        product.code,
        product.version.toString(),
        savedScenarioRevision?.toString(),
    ),
    inputsUsed = FinancingScenarioPreviewInputs(
        currency = "RUB",
        paymentModel = if (product.revolving) FinancingPaymentModel.REVOLVING else FinancingPaymentModel.ANNUITY,
        loanAmountRub = application.terms.amount.toString(),
        annualRatePercent = product.baseRate.toPlainString(),
        baseTermMonths = application.terms.termMonths,
        alternativeTermMonths = inputs.alternativeTermMonths,
        openingBalanceRub = inputs.openingBalance.toString(),
        expectedReceiptAmountRub = inputs.expectedReceiptAmount.toString(),
        expectedReceiptDate = inputs.expectedReceiptDate,
        paymentDate = inputs.paymentDate,
        delayDays = inputs.delayDays,
        receiptIncludedByPayment = calculation.receiptIncludedByPayment,
        lastIncludedDelayDays = calculation.lastIncludedDelayDays,
    ),
    options = calculation.options.map { option ->
        FinancingScenarioPreviewOption(
            option.termMonths,
            option.monthlyPayment.toPlainString(),
            option.totalInterest.toPlainString(),
            option.balanceOnPaymentDate.toPlainString(),
        )
    },
    cabinetPath = "/agent/applications/${application.id}#draft-financing",
)
