import assert from "node:assert/strict";
import type { PreviewResult, SummaryResult } from "../src/contract.ts";

function withoutObservationTime<T extends { asOf: string }>(value: T): Omit<T, "asOf"> {
  const { asOf, ...stable } = value;
  assert.ok(Number.isFinite(Date.parse(asOf)), "The observation timestamp must be valid");
  return stable;
}

export function assertStableSummary(before: SummaryResult, after: SummaryResult) {
  // Credit stamps every fresh read. A timestamp change is not a business mutation.
  assert.deepEqual(withoutObservationTime(after), withoutObservationTime(before));
}

export function assertFinancialParity(mcp: PreviewResult, web: PreviewResult) {
  // Include exact decimal strings, basis versions, date cut-off, both options and the case link.
  assert.deepEqual(withoutObservationTime(web), withoutObservationTime(mcp));
}

export function webPreviewRequest(mcp: PreviewResult) {
  const inputs = mcp.inputsUsed;
  const integerRubles = (value: string) => {
    const number = Number(value);
    assert.ok(Number.isSafeInteger(number), "The synthetic WEB fixture requires whole safe rubles");
    return number;
  };
  return {
    mode: "WEB_FORM",
    delayDays: inputs.delayDays,
    alternativeTermMonths: inputs.alternativeTermMonths,
    openingBalance: integerRubles(inputs.openingBalanceRub),
    expectedReceiptAmount: integerRubles(inputs.expectedReceiptAmountRub),
    expectedReceiptDate: inputs.expectedReceiptDate,
    paymentDate: inputs.paymentDate,
    draftTermsOverride: {
      amount: integerRubles(inputs.loanAmountRub),
      baseTermMonths: inputs.baseTermMonths,
    },
  };
}
