package ru.creditcabinet.financing.scenario

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import java.time.LocalDate

data class FinancingScenarioInputs(
    @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) val openingBalance: Long,
    @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) val expectedReceiptAmount: Long,
    val expectedReceiptDate: LocalDate,
    val paymentDate: LocalDate,
    @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) val delayDays: Int,
    @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) val alternativeTermMonths: Int,
    @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) val selectedTermMonths: Int,
    val source: FinancingScenarioSource? = null,
)
