import assert from "node:assert/strict";
import { chmod, rename, symlink, writeFile } from "node:fs/promises";
import { test } from "node:test";
import type { TestContext } from "node:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createCreditProvider } from "../dist/credit.js";
import { fixtureIds } from "../dist/fixtures.js";
import { createServer } from "../dist/server.js";
import { assertToolError, call, names, openCreditStdio, openStdio, requestTimeout } from "./client.ts";
import { creditResponses, origin, previewArgs, syntheticToken, tokenFile } from "./credit-fixtures.ts";

type Transport = NonNullable<Parameters<typeof createCreditProvider>[1]>;
const json = (status: number, body: unknown, contentType = "application/json; charset=utf-8") => ({
  status, contentType,
  body: (async function* () { yield Buffer.from(JSON.stringify(body)); })(),
  close() {},
});

async function mcpClient(t: TestContext, transport: Transport, path?: string) {
  const provider = createCreditProvider({ origin, tokenFile: path ?? await tokenFile(t) }, transport);
  const server = createServer(provider, "credit");
  const client = new Client({ name: "credit-contract", version: "1" });
  const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
  await server.connect(serverSide);
  await client.connect(clientSide, requestTimeout);
  t.after(() => client.close());
  assert.deepEqual((await client.listTools()).tools.map(tool => tool.name), Object.values(names));
  return client;
}

test("the Credit client accepts a fixed IPv4 loopback origin and an absolute credential path", async t => {
  const path = await tokenFile(t);
  for (const invalid of [
    "http://localhost:8080", "https://outside.invalid:443", "http://127.1:8080", "http://[::1]:8080",
    "http://127.0.0.2:8080", "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:99999",
    "http://127.0.0.1:8080@other.invalid", "http://127.0.0.1:8080/extra", origin + "?q=1", origin + "#x",
  ]) assert.throws(() => createCreditProvider({ origin: invalid, tokenFile: path }));
  assert.throws(() => createCreditProvider({ origin, tokenFile: "relative/path" }));
  assert.doesNotThrow(() => createCreditProvider({ origin: "https://127.0.0.1:8443", tokenFile: path }));
});

test("only three exact operations leave the adapter with the local credential", async t => {
  const responses = await creditResponses();
  const requests: Array<{ method: string; path: string; body?: string }> = [];
  const client = await mcpClient(t, async (url, args) => {
    assert.equal(url.origin, origin);
    assert.ok(args.headers.Authorization === "Bearer " + syntheticToken);
    assert.equal(args.headers.Accept, "application/json");
    assert.equal(args.signal.aborted, false);
    requests.push({ method: args.method, path: url.pathname + url.search, body: args.body });
    const dto = [responses.list, responses.summary, responses.preview][requests.length - 1];
    return json(200, dto);
  });

  assert.deepEqual((await call(client, names.list, { limit: 1 })).structuredContent, responses.list);
  assert.deepEqual((await call(client, names.summary, { applicationId: fixtureIds.vector.toUpperCase() })).structuredContent,
    responses.summary);
  assert.deepEqual((await call(client, names.preview, previewArgs)).structuredContent, responses.preview);
  assertToolError(await call(client, names.preview, { ...previewArgs, alternativeTermMonths: 2147483648 }), "INVALID_INPUT");
  assertToolError(await call(client, names.preview, { ...previewArgs, mode: "CURRENT_FORM" }), "INVALID_INPUT");
  assertToolError(await call(client, names.summary, { applicationId: "../elsewhere" }), "INVALID_INPUT");
  assert.deepEqual(requests, [
    { method: "GET", path: "/api/v1/agent/mcp/priorities?limit=1", body: undefined },
    { method: "GET", path: "/api/v1/agent/mcp/applications/" + fixtureIds.vector + "/summary", body: undefined },
    {
      method: "POST", path: "/api/v1/agent/mcp/applications/" + fixtureIds.vector + "/financing-scenario/preview",
      body: '{"mode":"SAVED_SCENARIO","delayDays":6,"alternativeTermMonths":24}',
    },
  ]);
});

test("Credit failures have stable codes and never reuse or expose a prior or malformed response", async t => {
  const responses = await creditResponses();
  let next = json(200, responses.summary);
  let requests = 0;
  let closed = 0;
  const client = await mcpClient(t, async () => {
    requests++;
    return { ...next, close: () => { closed++; } };
  });
  const summary = () => call(client, names.summary, { applicationId: fixtureIds.vector });
  assert.equal((await summary()).structuredContent?.source, "credit");
  for (const [status, body, expected] of [
    [400, { code: "INVALID_INPUT" }, "INVALID_INPUT"],
    [401, { internal: "do not forward" }, "UNAUTHENTICATED"],
    [403, { internal: "do not forward" }, "FORBIDDEN"],
    [404, { code: "CASE_NOT_FOUND" }, "CASE_NOT_FOUND"],
    [404, { error: "route missing" }, "DEPENDENCY_UNAVAILABLE"],
    [409, { code: "PREVIEW_NOT_READY" }, "PREVIEW_NOT_READY"],
    [409, { code: "SCENARIO_NOT_APPLICABLE" }, "SCENARIO_NOT_APPLICABLE"],
    [409, { code: "PREVIEW_NOT_READY", trace: "private" }, "DEPENDENCY_UNAVAILABLE"],
    [302, {}, "DEPENDENCY_UNAVAILABLE"],
    [429, { code: "RATE_LIMITED" }, "DEPENDENCY_UNAVAILABLE"],
    [503, { code: "DEPENDENCY_UNAVAILABLE" }, "DEPENDENCY_UNAVAILABLE"],
    [200, { ...responses.summary, source: "synthetic-fixture" }, "DEPENDENCY_UNAVAILABLE"],
    [200, { ...responses.summary, application: { ...responses.summary.application, documentId: "private" } },
      "DEPENDENCY_UNAVAILABLE"],
    [200, { ...responses.summary, application: { ...responses.summary.application, cabinetPath: "https://outside.invalid" } },
      "DEPENDENCY_UNAVAILABLE"],
    [200, { ...responses.summary, application: { ...responses.summary.application,
      cabinetPath: "/agent/applications/" + fixtureIds.other } }, "DEPENDENCY_UNAVAILABLE"],
    [200, { ...responses.summary, application: { ...responses.summary.application, applicationId: fixtureIds.other,
      cabinetPath: "/agent/applications/" + fixtureIds.other } }, "DEPENDENCY_UNAVAILABLE"],
    [200, { ...responses.summary, applicationVersion: 4 }, "DEPENDENCY_UNAVAILABLE"],
    [200, "x".repeat(128 * 1024), "DEPENDENCY_UNAVAILABLE"],
  ] as const) {
    next = json(status, body);
    assertToolError(await summary(), expected);
  }
  next = json(200, responses.summary, "text/html");
  assertToolError(await summary(), "DEPENDENCY_UNAVAILABLE");
  next = json(200, { ...responses.preview, inputsUsed: { ...responses.preview.inputsUsed, delayDays: 5 } });
  assertToolError(await call(client, names.preview, previewArgs), "DEPENDENCY_UNAVAILABLE");
  assert.equal(closed, requests);
});

test("private credential is reread after rotation, and unsafe credential files stop before transport", async t => {
  const path = await tokenFile(t);
  const provided: string[] = [];
  const client = await mcpClient(t, async (_url, options) => {
    provided.push(options.headers.Authorization);
    return json(401, {});
  }, path);
  const invoke = () => call(client, names.list);
  assertToolError(await invoke(), "UNAUTHENTICATED");
  const newToken = "different.synthetic.signature";
  await writeFile(path + ".new", newToken, { mode: 0o600, flag: "wx" });
  await rename(path + ".new", path);
  assertToolError(await invoke(), "UNAUTHENTICATED");
  await chmod(path, 0o640);
  assertToolError(await invoke(), "UNAUTHENTICATED");
  await chmod(path, 0o600);
  await rename(path, path + ".moved");
  await symlink(path + ".moved", path);
  assertToolError(await invoke(), "UNAUTHENTICATED");
  assert.ok(provided.length === 2 && provided[0] === "Bearer " + syntheticToken && provided[1] === "Bearer " + newToken);
});

test("stdio keeps the offline mode by default and opts into Credit explicitly", async t => {
  const offline = await openStdio("agent");
  t.after(offline.close);
  assert.equal((await call(offline.client, names.list)).structuredContent?.source, "synthetic-fixture");
  const local = await openCreditStdio(origin, "/nonexistent/synthetic-token");
  t.after(local.close);
  assertToolError(await call(local.client, names.list), "UNAUTHENTICATED");
  assert.equal(local.stderr(), "");
  await assert.rejects(() => openCreditStdio("http://localhost:8080", "/nonexistent/synthetic-token"));
});
