import assert from "node:assert/strict";
import test from "node:test";
import { createFixtures, fixtureIds } from "../dist/fixtures.js";
import { assertFinancialParity, assertStableSummary, webPreviewRequest } from "./live-assertions.ts";

test("fresh observation timestamps do not make unchanged case summaries fail", async () => {
  const { provider } = createFixtures("agent");
  const before = await provider.summary({ applicationId: fixtureIds.vector });
  const after = { ...structuredClone(before), asOf: "2026-11-28T09:00:01Z" };
  assert.doesNotThrow(() => assertStableSummary(before, after));
  assert.throws(() => assertStableSummary(before, { ...after, savedScenarioRevision: "999" }));
  assert.throws(() => assertStableSummary(before, { ...after, applicationVersion: "999" }));
  assert.throws(() => assertStableSummary(before, { ...after, statementRequirement: "OPEN" }));
});

test("WEB and MCP parity checks exact money and product versions for days five and six", async () => {
  const { provider } = createFixtures("agent");
  for (const delayDays of [5, 6]) {
    const mcp = await provider.preview({ applicationId: fixtureIds.vector, delayDays, alternativeTermMonths: 24 });
    const web = { ...structuredClone(mcp), asOf: "2026-11-28T09:00:02Z" };
    assert.doesNotThrow(() => assertFinancialParity(mcp, web));
    const wrongBalance = structuredClone(web);
    wrongBalance.options[0].balanceOnPaymentDateRub = "0.01";
    assert.throws(() => assertFinancialParity(mcp, wrongBalance));
    const wrongInterest = structuredClone(web);
    wrongInterest.options[1].totalInterestRub = "0.01";
    assert.throws(() => assertFinancialParity(mcp, wrongInterest));
    assert.throws(() => assertFinancialParity(mcp, { ...web, basis: { ...web.basis, productVersion: "999" } }));
    assert.deepEqual(webPreviewRequest(mcp), {
      mode: "WEB_FORM", delayDays, alternativeTermMonths: 24, openingBalance: 150000,
      expectedReceiptAmount: 140000, expectedReceiptDate: "2026-12-10", paymentDate: "2026-12-15",
      draftTermsOverride: { amount: 2000000, baseTermMonths: 12 },
    });
  }
});

test("WEB parity refuses a lossy conversion of synthetic monetary inputs", async () => {
  const { provider } = createFixtures("agent");
  const value = await provider.preview({ applicationId: fixtureIds.vector, delayDays: 5, alternativeTermMonths: 24 });
  value.inputsUsed.loanAmountRub = "9007199254740993";
  assert.throws(() => webPreviewRequest(value));
});
