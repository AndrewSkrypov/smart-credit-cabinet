import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { applicationId, listOutput, previewOutput, summaryOutput } from "../dist/contract.js";
import { assertToolError, call, names, openCreditStdio, requestTimeout } from "./client.ts";

let step = "configuration";
try {
  const origin = process.env.CABINET_CREDIT_ORIGIN;
  const tokenFile = process.env.CABINET_CREDIT_TOKEN_FILE;
  const id = applicationId.parse(process.env.CABINET_CREDIT_APPLICATION_ID).toLowerCase();
  const creditSha = process.env.CABINET_CREDIT_SOURCE_SHA;
  assert.ok(origin && tokenFile && creditSha && /^[a-f0-9]{40}$/.test(creditSha));

  step = "stdio initialization";
  const connection = await openCreditStdio(origin, tokenFile);
  try {
    const { client } = connection;
    const { tools } = await client.listTools(undefined, requestTimeout);
    assert.deepEqual(tools.map(tool => tool.name), Object.values(names));
    for (const tool of tools) {
      assert.equal(tool.annotations?.readOnlyHint, true);
      assert.equal(tool.inputSchema.additionalProperties, false);
      assert.equal(tool.outputSchema?.additionalProperties, false);
    }
    const timings: Record<string, number> = {};
    async function timed(name: string, args: Record<string, unknown>, label = name) {
      step = label;
      const start = performance.now();
      const reply = await call(client, name, args);
      timings[label] = Math.round(performance.now() - start);
      assert.notEqual(reply.isError, true);
      return reply.structuredContent;
    }

    const list = listOutput.parse(await timed(names.list, { limit: 20 }));
    const summary = summaryOutput.parse(await timed(names.summary, { applicationId: id }));
    step = "list and summary consistency";
    assert.equal(list.source, "credit");
    assert.equal(summary.source, "credit");
    assert.deepEqual(list.items.find(item => item.applicationId === id), summary.application);
    const first = previewOutput.parse(await timed(names.preview, {
      applicationId: id, delayDays: 5, alternativeTermMonths: 24,
    }, "preview-day-5"));
    const second = previewOutput.parse(await timed(names.preview, {
      applicationId: id, delayDays: 6, alternativeTermMonths: 24,
    }, "preview-day-6"));

    step = "preview fixture values";
    assert.equal(first.source, "credit");
    assert.equal(second.source, "credit");
    assert.ok(first.inputsUsed.receiptIncludedByPayment);
    assert.ok(!second.inputsUsed.receiptIncludedByPayment);
    assert.equal(first.inputsUsed.lastIncludedDelayDays, 5);
    assert.equal(second.inputsUsed.lastIncludedDelayDays, 5);
    assert.deepEqual(first.basis, second.basis);
    assert.equal(summary.applicationVersion, first.basis.applicationVersion);
    assert.equal(summary.savedScenarioRevision, first.basis.savedScenarioRevision);
    assert.deepEqual(first.options.map(item => item.balanceOnPaymentDateRub), ["104730.99", "188208.39"]);
    assert.deepEqual(second.options.map(item => item.balanceOnPaymentDateRub), ["-35269.01", "48208.39"]);
    for (const result of [first, second]) {
      assert.deepEqual(result.options.map(item => [item.monthlyPaymentRub, item.totalInterestRub]), [
        ["185269.01", "223228.15"], ["101791.61", "442998.50"],
      ]);
    }
    step = "stable input and access errors";
    const unknown = "f292fa28-84f2-456b-a454-967fe874db37";
    assertToolError(await call(client, names.list, { limit: 21 }), "INVALID_INPUT");
    assertToolError(await call(client, names.preview, {
      applicationId: id, delayDays: 5, alternativeTermMonths: 25,
    }), "INVALID_INPUT");
    assertToolError(await call(client, names.summary, { applicationId: unknown }), "CASE_NOT_FOUND");
    assertToolError(await call(client, names.preview, {
      applicationId: unknown, delayDays: 5, alternativeTermMonths: 24,
    }), "CASE_NOT_FOUND");

    step = "unauthenticated Credit response";
    const directory = await mkdtemp(join(tmpdir(), "cabinet-mcp-invalid-session-"));
    try {
      const invalidPath = join(directory, "token");
      await writeFile(invalidPath, "synthetic.invalid.signature", { mode: 0o600, flag: "wx" });
      const unauthenticated = await openCreditStdio(origin, invalidPath);
      try {
        assertToolError(await call(unauthenticated.client, names.list), "UNAUTHENTICATED");
      } finally {
        await unauthenticated.close();
      }
    } finally {
      await rm(directory, { recursive: true, force: true });
    }

    step = "stdio diagnostics";
    assert.equal(connection.stderr(), "");
    console.log(JSON.stringify({
      result: "PASS", source: "credit", creditSha, tools: Object.values(names),
      fixture: "day-5/day-6", applicationVersion: first.basis.applicationVersion,
      productVersion: first.basis.productVersion, savedScenarioRevision: first.basis.savedScenarioRevision,
      timingsMs: timings,
    }));
  } finally {
    await connection.close();
  }
} catch {
  console.error(JSON.stringify({ result: "FAIL", stage: step }));
  process.exitCode = 1;
}
