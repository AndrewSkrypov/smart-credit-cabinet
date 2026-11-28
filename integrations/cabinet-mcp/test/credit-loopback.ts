import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer } from "node:http";
import { test } from "node:test";
import { assertToolError, call, names, openCreditStdio } from "./client.ts";
import { creditResponses, previewArgs, syntheticToken, tokenFile } from "./credit-fixtures.ts";

test("real loopback HTTP over stdio preserves the allowlist, rejects redirects, and times out a partial body", async t => {
  const path = await tokenFile(t);
  const responses = await creditResponses();
  const observed: Array<{ method?: string; path?: string; authorized: boolean; body: string }> = [];
  let mode: "normal" | "redirect" | "stall" = "normal";
  const stub = createServer(async (request, response) => {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    observed.push({ method: request.method, path: request.url,
      authorized: request.headers.authorization === "Bearer " + syntheticToken,
      body: Buffer.concat(chunks).toString("utf8") });
    if (mode === "redirect") {
      response.writeHead(302, { location: "/must-not-follow" });
      response.end();
      return;
    }
    response.writeHead(200, { "Content-Type": "application/json" });
    if (mode === "stall") {
      response.write("{");
      return;
    }
    const dto = request.url?.includes("/priorities") ? responses.list
      : request.url?.endsWith("/summary") ? responses.summary : responses.preview;
    response.end(JSON.stringify(dto));
  });
  stub.listen(0, "127.0.0.1");
  await once(stub, "listening");
  t.after(() => {
    stub.closeAllConnections();
    return new Promise<void>((resolve, reject) => stub.close(error => error ? reject(error) : resolve()));
  });
  const address = stub.address();
  assert.ok(address && typeof address === "object");
  const connection = await openCreditStdio("http://127.0.0.1:" + address.port, path);
  t.after(connection.close);

  assert.deepEqual((await call(connection.client, names.list, { limit: 1 })).structuredContent, responses.list);
  assert.deepEqual((await call(connection.client, names.summary, { applicationId: previewArgs.applicationId })).structuredContent,
    responses.summary);
  assert.deepEqual((await call(connection.client, names.preview, previewArgs)).structuredContent, responses.preview);
  mode = "redirect";
  assertToolError(await call(connection.client, names.list, { limit: 1 }), "DEPENDENCY_UNAVAILABLE");
  assert.deepEqual(observed.map(request => request.path), [
    "/api/v1/agent/mcp/priorities?limit=1",
    "/api/v1/agent/mcp/applications/" + previewArgs.applicationId + "/summary",
    "/api/v1/agent/mcp/applications/" + previewArgs.applicationId + "/financing-scenario/preview",
    "/api/v1/agent/mcp/priorities?limit=1",
  ]);
  assert.deepEqual(observed.map(request => request.method), ["GET", "GET", "POST", "GET"]);
  assert.ok(observed.every(request => request.authorized));
  assert.deepEqual(JSON.parse(observed[2].body), { mode: "SAVED_SCENARIO", delayDays: 6, alternativeTermMonths: 24 });

  mode = "stall";
  assertToolError(await call(connection.client, names.list, { limit: 1 }), "DEPENDENCY_UNAVAILABLE");
  assert.equal(observed.length, 5);
  assert.equal(connection.stderr(), "");
});
