import assert from "node:assert/strict";
import { test } from "node:test";
import { fixtureIds } from "../dist/fixtures.js";
import { readScope, verifyGrant } from "../dist/http-auth.js";
import { createHttpHandler } from "../dist/http-server.js";
import { creditResponses } from "./credit-fixtures.ts";
import { names } from "./client.ts";

const resource = "https://cabinet.example.test/api/mcp";
const issuer = "https://identity.example.test";
const token = "mcp_at_" + "a".repeat(43);
const subject = "56488ad1-6a19-4e25-aeac-033ace444ac3";
const assertion = (serial: number) => "mcp_ca_" + String(serial).padStart(43, "0");
const active = { actorId: subject, role: "AGENT", resource, scope: readScope, actorAssertion: assertion(0) };

function request(method: string, params: unknown = {}, bearer: string | null = token, route = resource) {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json, text/event-stream",
    "MCP-Protocol-Version": "2025-11-25",
  };
  if (bearer !== null) headers.Authorization = "Bearer " + bearer;
  return new Request(route, { method: "POST", headers, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }) });
}

test("discover exact resource and OAuth issuer without an access token", async () => {
  const handle = createHttpHandler({ resource, issuer }, () => Promise.reject(), () => { throw new Error(); });
  const denied = await handle(request("tools/list", {}, null));
  assert.equal((await handle(request("tools/list", {}, "synthetic.browser.jwt"))).status, 401);
  assert.equal(denied.status, 401);
  const challenge = denied.headers.get("WWW-Authenticate");
  assert.match(challenge, /resource_metadata="https:\/\/cabinet\.example\.test\/\.well-known\/oauth-protected-resource\/api\/mcp"/);
  const metadata = await handle(new Request(new URL("/.well-known/oauth-protected-resource/api/mcp", resource)));
  assert.deepEqual(await metadata.json(), {
    resource, authorization_servers: [issuer], scopes_supported: [readScope], bearer_methods_supported: ["header"],
  });
  assert.equal(metadata.headers.get("Cache-Control"), "no-store");
});

test("sessionless Streamable HTTP initializes, discovers and calls the three read-only tools", async () => {
  const responses = await creditResponses();
  const subjects: string[] = [];
  let verifications = 0;
  const verify = verifyGrant(resource, async access => {
    assert.equal(access, token);
    verifications++;
    return active;
  });
  const handle = createHttpHandler({ resource, issuer }, verify, principal => {
    subjects.push(principal.subject);
    assert.deepEqual(Object.keys(principal).sort(), ["actorAssertion", "subject"]);
    return { async list() { return responses.list; }, async summary() { return responses.summary; },
      async preview() { return responses.preview; } };
  });

  const init = await handle(request("initialize", {
    protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "http-test", version: "1" },
  }));
  assert.equal(init.status, 200);
  assert.equal(init.headers.get("Mcp-Session-Id"), null);
  assert.equal((await init.json()).result.protocolVersion, "2025-11-25");
  const listed = await handle(request("tools/list"));
  const tools = (await listed.json()).result.tools;
  assert.deepEqual(tools.map(tool => tool.name), Object.values(names));
  assert.ok(tools.every(tool => tool.annotations.readOnlyHint));
  assert.deepEqual(subjects, []);
  const args = { applicationId: fixtureIds.vector };
  for (const [name, input, expected] of [
    [names.list, { limit: 1 }, responses.list],
    [names.summary, args, responses.summary],
    [names.preview, { ...args, delayDays: 6, alternativeTermMonths: 24 }, responses.preview],
  ]) {
    const called = await handle(request("tools/call", { name, arguments: input }));
    assert.equal(called.status, 200);
    assert.deepEqual((await called.json()).result.structuredContent, expected);
  }
  assert.deepEqual(subjects, [subject, subject, subject]);
  assert.equal(verifications, 5);
});

test("wrong resource, wrong role, malformed assertion, inactive grant and wrong scope fail before Credit", async () => {
  let value: unknown = active;
  let online = true;
  const verify = verifyGrant(resource, async () => {
    if (!online) throw new Error("private backend failure");
    return value;
  });
  const handler = createHttpHandler({ resource, issuer }, verify, () => { throw new Error("unexpected provider"); });
  assert.equal((await handler(request("tools/list"))).status, 200);
  for (const changed of [
    null, { ...active, role: "CLIENT" }, { ...active, resource: "https://other.example.test/api/mcp" },
    { ...active, actorAssertion: "malformed" }, { ...active, unrecognized: true },
  ]) {
    value = changed;
    const denied = await handler(request("tools/list"));
    assert.equal(denied.status, 401);
    assert.deepEqual(await denied.json(), { error: "invalid_token" });
  }
  value = { ...active, scope: "cabinet:mcp:read-other" };
  assert.equal((await handler(request("tools/list"))).status, 403);
  online = false;
  const down = await handler(request("tools/list"));
  assert.equal(down.status, 503);
  assert.deepEqual(await down.json(), { error: "temporarily_unavailable" });
});

test("rejects browser cross-origin, SSE GET, query tokens, malformed and oversized bodies", async () => {
  const verify = verifyGrant(resource, async () => active);
  const handler = createHttpHandler({ resource, issuer }, verify, () => { throw new Error(); });
  const cross = request("tools/list");
  cross.headers.set("Origin", "https://other.example.test");
  assert.equal((await handler(cross)).status, 403);
  assert.equal((await handler(new Request(resource))).status, 405);
  assert.equal((await handler(request("tools/list", {}, token, resource + "?access_token=sample"))).status, 400);
  assert.equal((await handler(request("tools/list", { padding: "a".repeat(16384) }))).status, 413);
  const batch = new Request(resource, { method: "POST",
    headers: { Authorization: "Bearer " + token, "Content-Type": "application/json" }, body: "[]" });
  assert.equal((await handler(batch)).status, 400);
});

test("Credit allows the dedicated Kubernetes Service only at its configured private names and port", async () => {
  const { serviceOrigin } = await import("../dist/http-upstream.js");
  for (const host of [
    "cabinet-credit-mcp", "cabinet-credit-mcp.cabinet-prod.svc",
    "cabinet-credit-mcp.cabinet-prod.svc.cluster.local", "credit",
  ]) {
    const url = "https://" + host + ":8443";
    assert.equal(serviceOrigin(url, "credit").origin, url);
  }
  for (const url of [
    "https://cabinet-credit-mcp.other.svc.cluster.local:8443",
    "https://cabinet-credit-mcp.example.test:8443",
    "https://cabinet-credit-mcp.cabinet-prod.svc.cluster.local:8080",
  ]) {
    assert.throws(() => serviceOrigin(url, "credit"));
  }
  assert.throws(() => serviceOrigin("https://cabinet-credit-mcp:8443", "identity"));
});

test("the private Identity adapter accepts only the exact installed DNS name and TLS port", async () => {
  const { serviceOrigin } = await import("../dist/http-upstream.js");
  for (const allowed of ["https://identity-mcp:8443", "https://cabinet-mcp-identity:8444"]) {
    assert.equal(serviceOrigin(allowed, "identity").origin, allowed);
    assert.throws(() => serviceOrigin(allowed, "credit"));
  }
  for (const denied of [
    "https://identity-mcp:8444",
    "https://cabinet-mcp-identity:8443",
    "https://cabinet-mcp-identity.example.test:8444",
  ]) assert.throws(() => serviceOrigin(denied, "identity"));
});

test("private hops keep external access tokens, service credentials and actor assertions separate", async () => {
  const { identityAuthorization } = await import("../dist/http-identity.js");
  const { creditForActor } = await import("../dist/http-credit.js");
  assert.throws(() => creditForActor("http://credit:8443", {}));
  assert.throws(() => creditForActor("https://credit:8080", {}));
  assert.throws(() => creditForActor("https://outside.example.test:8443", {}));
  const draft = fixtureIds.vector;
  const credit = await creditResponses();
  const serviceToken = "s".repeat(43);
  let assertions = 0;
  const captured: Array<{ path: string; method: string }> = [];
  const reply = (value: unknown) => ({
    status: 200, contentType: "application/json",
    body: (async function* () { yield Buffer.from(JSON.stringify(value)); })(), close() {},
  });
  const verify = identityAuthorization({ resource, identityOrigin: "https://identity:8443", serviceToken, identityTls: { ca: "identity-fixture" } },
    async (url, options) => {
      assert.equal(url.href, "https://identity:8443/internal/mcp/authorize");
      assert.equal(options.method, "POST");
      assert.deepEqual(options.tls, { ca: "identity-fixture" });
      assert.ok(options.headers.Authorization === "Bearer " + serviceToken);
      assert.deepEqual(JSON.parse(options.body), { accessToken: token });
      assertions++;
      return reply({ ...active, actorAssertion: assertion(assertions) });
    });
  const provider = creditForActor("https://credit:8443", { ca: "credit-fixture", cert: "cert-fixture", key: "key-fixture" }, async (url, options) => {
    assert.equal(url.origin, "https://credit:8443");
    assert.deepEqual(options.tls, { ca: "credit-fixture", cert: "cert-fixture", key: "key-fixture" });
    assert.equal(Object.keys(options.headers).filter(key => key.includes("Assertion")).length, 1);
    assert.ok(options.headers["X-Cabinet-Mcp-Actor-Assertion"] === assertion(assertions));
    assert.equal(options.headers.Authorization, undefined);
    assert.ok(!JSON.stringify(options).includes(token));
    const path = url.pathname + url.search;
    captured.push({ path, method: options.method });
    if (url.pathname.endsWith("/preview")) {
      assert.deepEqual(JSON.parse(options.body), { mode: "SAVED_SCENARIO", delayDays: 6, alternativeTermMonths: 24 });
      return reply(credit.preview);
    }
    return reply(url.pathname.endsWith("/summary") ? credit.summary : credit.list);
  });
  const handler = createHttpHandler({ resource, issuer }, verify, provider);
  await handler(request("tools/list"));
  assert.equal(captured.length, 0);
  for (const [name, args] of [
    [names.list, { limit: 1 }],
    [names.summary, { applicationId: draft }],
    [names.preview, { applicationId: draft, delayDays: 6, alternativeTermMonths: 24 }],
  ]) {
    const response = await handler(request("tools/call", { name, arguments: args }));
    assert.equal(response.status, 200);
    assert.equal((await response.json()).result.structuredContent.source, "credit");
  }
  assert.equal(assertions, 4);
  assert.deepEqual(captured, [
    { path: "/internal/mcp/credit/priorities?limit=1", method: "GET" },
    { path: "/internal/mcp/credit/applications/" + draft + "/summary", method: "GET" },
    { path: "/internal/mcp/credit/applications/" + draft + "/financing-scenario/preview", method: "POST" },
  ]);
});
