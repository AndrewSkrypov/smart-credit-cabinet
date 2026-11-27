package ru.creditcabinet.financing.scenario.application

internal fun ScenarioRevision.changesSince(previous: ScenarioRevision): List<String> {
    val before = previous.snapshot
    val after = snapshot
    return buildList {
        if (before.loanAmount != after.loanAmount) add("Сумма кредита")
        if (before.annualRatePercent.compareTo(after.annualRatePercent) != 0) add("Ставка")
        if (before.options.first().termMonths != after.options.first().termMonths) add("Первый срок")
        if (before.inputs.openingBalance != after.inputs.openingBalance) add("Исходный остаток")
        if (before.inputs.expectedReceiptAmount != after.inputs.expectedReceiptAmount) add("Ожидаемое поступление")
        if (before.inputs.expectedReceiptDate != after.inputs.expectedReceiptDate) add("Дата поступления")
        if (before.inputs.paymentDate != after.inputs.paymentDate) add("Дата платежа")
        if (before.inputs.delayDays != after.inputs.delayDays) add("Задержка поступления")
        if (before.inputs.alternativeTermMonths != after.inputs.alternativeTermMonths) add("Альтернативный срок")
        if (before.inputs.selectedTermMonths != after.inputs.selectedTermMonths) add("Выбранный срок")
        if (before.inputs.source != after.inputs.source) add("Источник")
        if (before.options != after.options) add("Расчёт")
        if (previous.handedTerms?.purpose != handedTerms?.purpose) add("Цель финансирования")
        if (previous.handedTerms?.purposeComment != handedTerms?.purposeComment) add("Комментарий к цели")
        if (previous.handedTerms?.declaredRevenue12m != handedTerms?.declaredRevenue12m) add("Годовая выручка")
        if (previous.handedTerms?.declaredMonthlyDebtPayments != handedTerms?.declaredMonthlyDebtPayments) add("Платежи по долгам")
        if (previous.documentIds != documentIds) add("Документы")
    }
}
