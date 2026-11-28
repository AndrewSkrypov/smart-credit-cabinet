import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createInterface } from "node:readline";
import { test } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { listOutput, previewOutput, summaryOutput } from "../dist/contract.js";
import { createFixtures, fixtureIds } from "../dist/fixtures.js";
import { createServer } from "../dist/server.js";
import { call, demoResults, main, names, openStdio, requestTimeout } from "./client.ts";

const { vector, north, other, expired, excludedCompany } = fixtureIds;
const unknown = "f292fa28-84f2-456b-a454-967fe874db37";
const previewArgs = { applicationId: vector, delayDays: 6, alternativeTermMonths: 24 };

function safeError(result: Awaited<ReturnType<typeof call>>, code: string) {
  assert.equal(result.isError, true);
  assert.equal(result.structuredContent, undefined);
  assert.ok(Array.isArray(result.content));
  assert.equal(result.content.length, 1);
  const entry = result.content[0];
  assert.equal(entry.type, "text");
  const parsed = JSON.parse(String(entry.text));
  assert.deepEqual(Object.keys(parsed).sort(), ["code", "message"]);
  assert.equal(parsed.code, code);
  assert.ok(parsed.message.length > 0);
  return parsed;
}

test("stdio SDK negotiates and discovers exactly three closed, read-only tools", async t => {
  const connection = await openStdio("agent");
  t.after(connection.close);
  const { client } = connection;
  const { tools } = await client.listTools(undefined, requestTimeout);
  assert.deepEqual(tools.map(tool => tool.name), Object.values(names));
  for (const tool of tools) {
    assert.equal(tool.annotations?.readOnlyHint, true);
    assert.equal(tool.inputSchema.additionalProperties, false);
    assert.equal(tool.outputSchema?.additionalProperties, false);
  }
  const results = await demoResults(client);
  for (const result of Object.values(results)) {
    assert.notEqual(result.isError, true);
    assert.ok(Array.isArray(result.content));
    assert.deepEqual(JSON.parse(result.content[0].text), result.structuredContent);
  }
  assert.equal(listOutput.parse(results[names.list].structuredContent).items.length, 2);
  assert.equal(summaryOutput.parse(results[names.summary].structuredContent).statementRequirement, "OPEN");
  assert.equal(previewOutput.parse(results[names.preview].structuredContent).source, "synthetic-fixture");
  assert.equal(connection.stderr(), "");
});

test("raw stdio uses JSON-RPC exclusively, negotiates the pinned revision and exits on EOF", { timeout: 8000 }, async t => {
  const child = spawn(process.execPath, [main, "--profile", "agent"], { stdio: ["pipe", "pipe", "pipe"] });
  const exited = once(child, "close");
  t.after(() => { child.kill(); });
  const reader = createInterface({ input: child.stdout });
  const lines = reader[Symbol.asyncIterator]();
  let stderr = "";
  child.stderr.setEncoding("utf8").on("data", chunk => { stderr += chunk; });
  const send = (method: string, params?: object, id?: number) =>
    child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method, params, id }) + "\n");

  send("initialize", { protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "wire", version: "1" } }, 1);
  const initialized = JSON.parse((await lines.next()).value);
  assert.equal(initialized.jsonrpc, "2.0");
  assert.equal(initialized.id, 1);
  assert.equal(initialized.result.protocolVersion, "2025-11-25");
  send("notifications/initialized");
  send("tools/list", {}, 2);
  const listed = JSON.parse((await lines.next()).value);
  assert.equal(listed.jsonrpc, "2.0");
  assert.equal(listed.id, 2);
  assert.equal(listed.result.tools.length, 3);
  for (const [id, name, toolArgs] of [
    [3, names.list, {}], [4, names.summary, { applicationId: north }], [5, names.preview, previewArgs],
  ] as const) {
    send("tools/call", { name, arguments: toolArgs }, id);
    const reply = JSON.parse((await lines.next()).value);
    assert.equal(reply.jsonrpc, "2.0");
    assert.equal(reply.id, id);
    assert.equal(reply.result.structuredContent.source, "synthetic-fixture");
  }
  send("tools/call", {
    name: names.list,
    arguments: JSON.parse('{"__proto__":1}'),
  }, 6);
  const rejected = JSON.parse((await lines.next()).value);
  assert.equal(rejected.jsonrpc, "2.0");
  assert.equal(rejected.id, 6);
  safeError(rejected.result, "INVALID_INPUT");
  child.stdin.end();
  assert.deepEqual(await exited, [0, null]);
  assert.equal((await lines.next()).done, true);
  assert.equal(stderr, "");
});

test("portfolio filters access before limiting and matches the summary and private request projection", async t => {
  const { client, close } = await openStdio("agent");
  t.after(close);
  const first = listOutput.parse((await call(client, names.list, { limit: 1 })).structuredContent);
  assert.deepEqual(first.items.map(item => item.applicationId), [vector]);
  const full = listOutput.parse((await call(client, names.list)).structuredContent);
  assert.deepEqual(full.items.map(item => item.applicationId), [vector, north]);
  for (const item of full.items) {
    const summary = summaryOutput.parse((await call(client, names.summary, { applicationId: item.applicationId })).structuredContent);
    assert.deepEqual(summary.application, item);
    assert.deepEqual(Object.keys(summary).sort(), [
      "application", "applicationVersion", "asOf", "savedScenarioRevision", "source", "statementRequirement",
    ]);
  }
  assert.equal(full.items[0].actionOwner, "AGENT");
  assert.equal(full.items[0].actionDeadline, "2026-12-15T09:00:00Z");
  assert.equal(full.items[1].actionDeadline, null);
  assert.equal(full.items[1].cabinetPath, "/agent/applications/" + north + "#application-info-request");
  const empty = await openStdio("empty");
  t.after(empty.close);
  assert.deepEqual(listOutput.parse((await call(empty.client, names.list)).structuredContent).items, []);
});

test("invalid and extraneous input has a stable safe tool error", async t => {
  const { client, close } = await openStdio("agent");
  t.after(close);
  for (const [name, args] of [
    [names.list, { limit: 0 }], [names.list, { limit: 21 }], [names.list, { companyId: "other" }],
    [names.summary, { applicationId: "invalid" }], [names.summary, { applicationId: vector, role: "AGENT" }],
    [names.preview, { ...previewArgs, delayDays: 5.5 }], [names.preview, { ...previewArgs, delayDays: 366 }],
    [names.preview, { ...previewArgs, alternativeTermMonths: 13 }], [names.preview, { ...previewArgs, annualRatePercent: "20" }],
    [names.preview, { ...previewArgs, alternativeTermMonths: 0 }], [names.preview, { ...previewArgs, delayDays: -1 }],
    ["write_agent_application", { applicationId: vector }],
  ] as const) {
    safeError(await call(client, name, args), "INVALID_INPUT");
  }
});

test("authorization errors distinguish session and role without leaking record existence", async t => {
  for (const [profile, code] of [
    [undefined, "UNAUTHENTICATED"], ["expired-session", "UNAUTHENTICATED"], ["client", "FORBIDDEN"],
  ] as const) {
    const connection = await openStdio(profile);
    t.after(connection.close);
    const { client } = connection;
    safeError(await call(client, names.list), code);
    safeError(await call(client, names.summary, { applicationId: vector }), code);
    safeError(await call(client, names.preview, previewArgs), code);
  }
  const scoped = await openStdio("summary-only");
  t.after(scoped.close);
  safeError(await call(scoped.client, names.list), "FORBIDDEN");
  safeError(await call(scoped.client, names.preview, previewArgs), "FORBIDDEN");
  assert.ok((await call(scoped.client, names.summary, { applicationId: vector })).structuredContent);
  const agent = await openStdio("agent");
  t.after(agent.close);
  const denied = safeError(await call(agent.client, names.summary, { applicationId: unknown }), "CASE_NOT_FOUND");
  for (const applicationId of [other, expired, excludedCompany, unknown]) {
    assert.deepEqual(safeError(await call(agent.client, names.summary, { applicationId }), "CASE_NOT_FOUND"), denied);
    assert.deepEqual(safeError(await call(agent.client, names.preview, { ...previewArgs, applicationId }), "CASE_NOT_FOUND"), denied);
  }
});

test("revoke after success, outage after success, and no stale results, through one MCP client", async t => {
  const fixtures = createFixtures("agent");
  const server = createServer(fixtures.provider);
  const client = new Client({ name: "state-verifier", version: "1" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  await client.connect(clientTransport, requestTimeout);
  t.after(() => client.close());
  await client.listTools(undefined, requestTimeout);
  const initialList = listOutput.parse((await call(client, names.list)).structuredContent);
  assert.deepEqual(initialList.items.map(item => item.applicationId), [vector, north]);
  const original = await call(client, names.summary, { applicationId: vector });
  assert.equal(summaryOutput.parse(original.structuredContent).application.applicationId, vector);
  assert.ok((await call(client, names.preview, previewArgs)).structuredContent);
  fixtures.revoke();
  assert.deepEqual(
    safeError(await call(client, names.summary, { applicationId: vector }), "CASE_NOT_FOUND"),
    safeError(await call(client, names.summary, { applicationId: unknown }), "CASE_NOT_FOUND"),
  );
  safeError(await call(client, names.preview, previewArgs), "CASE_NOT_FOUND");
  assert.deepEqual(listOutput.parse((await call(client, names.list)).structuredContent).items.map(item => item.applicationId), [north]);
  fixtures.makeUnavailable();
  safeError(await call(client, names.list), "DEPENDENCY_UNAVAILABLE");
  safeError(await call(client, names.summary, { applicationId: north }), "DEPENDENCY_UNAVAILABLE");
  safeError(await call(client, names.preview, previewArgs), "DEPENDENCY_UNAVAILABLE");
});

test("fixed decimal values and inclusive day five versus day six, without writes", async t => {
  const { client, close } = await openStdio("agent");
  t.after(close);
  const before = await call(client, names.summary, { applicationId: vector });
  const five = previewOutput.parse((await call(client, names.preview, { ...previewArgs, delayDays: 5 })).structuredContent);
  const sixResponse = await call(client, names.preview, previewArgs);
  const six = previewOutput.parse(sixResponse.structuredContent);
  assert.equal(five.inputsUsed.receiptIncludedByPayment, true);
  assert.equal(six.inputsUsed.receiptIncludedByPayment, false);
  assert.equal(six.inputsUsed.lastIncludedDelayDays, 5);
  assert.deepEqual(six.basis, five.basis);
  assert.deepEqual(six.basis, { applicationVersion: "4", productCode: "working-capital", productVersion: "2", savedScenarioRevision: "3" });
  assert.deepEqual(five.options.map(option => option.balanceOnPaymentDateRub), ["104730.99", "188208.39"]);
  assert.deepEqual(six.options.map(option => option.balanceOnPaymentDateRub), ["-35269.01", "48208.39"]);
  assert.deepEqual(six.options.map(option => [option.monthlyPaymentRub, option.totalInterestRub]), [
    ["185269.01", "223228.15"], ["101791.61", "442998.50"],
  ]);
  assert.equal(six.cabinetPath, "/agent/applications/" + vector + "#draft-financing");
  assert.deepEqual(await call(client, names.preview, previewArgs), sixResponse);
  assert.deepEqual(await call(client, names.summary, { applicationId: vector }), before);
  safeError(await call(client, names.preview, { ...previewArgs, delayDays: 4 }), "FIXTURE_NOT_AVAILABLE");
  safeError(await call(client, names.preview, { ...previewArgs, applicationId: north }), "SCENARIO_NOT_APPLICABLE");
  const missing = await openStdio("missing-basis");
  t.after(missing.close);
  const summary = await call(missing.client, names.summary, { applicationId: vector });
  assert.equal(summaryOutput.parse(summary.structuredContent).savedScenarioRevision, null);
  safeError(await call(missing.client, names.preview, previewArgs), "PREVIEW_NOT_READY");
});

test("closed response validation fails safe on extra data and blocks the dormant Credit source", async t => {
  const { provider } = createFixtures("agent");
  assert.deepEqual(Object.keys(provider), ["list", "summary", "preview"]);
  assert.ok(Object.isFrozen(provider));
  const server = createServer({
    async list(args) {
      const result = await provider.list(args);
      return { ...result, items: result.items.map(item => ({ ...item, forbiddenField: "blocked" })) };
    },
    async summary(args) {
      return { ...await provider.summary(args), source: "credit" };
    },
    preview: provider.preview,
  });
  const client = new Client({ name: "strict-provider-verifier", version: "1" });
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair();
  await server.connect(serverTransport);
  await client.connect(clientTransport, requestTimeout);
  t.after(() => client.close());
  safeError(await call(client, names.list), "DEPENDENCY_UNAVAILABLE");
  safeError(await call(client, names.summary, { applicationId: vector }), "DEPENDENCY_UNAVAILABLE");
});
