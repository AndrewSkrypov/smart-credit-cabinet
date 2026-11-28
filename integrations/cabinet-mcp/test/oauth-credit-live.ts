import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { open, constants } from "node:fs/promises";
import { applicationId, listOutput, previewOutput, summaryOutput } from "../dist/contract.js";
import { names } from "./client.ts";
import { assertFinancialParity, assertStableSummary, webPreviewRequest } from "./live-assertions.ts";

const scope = "cabinet:mcp:read";
let stage = "configuration";

async function run() {
  assert.equal(process.env.CABINET_MCP_SYNTHETIC_ACCEPTANCE, "1");
  const origin = process.env.CABINET_MCP_ACCEPTANCE_ORIGIN ?? "";
  const endpoint = new URL(origin);
  assert.equal(endpoint.protocol, "https:");
  assert.equal(endpoint.origin, origin);
  const file = process.env.CABINET_MCP_ACCEPTANCE_FIXTURE ?? "";
  assert.ok(file.startsWith("/"));
  const fixtureFile = await open(file, constants.O_RDONLY | constants.O_NOFOLLOW);
  let fixture: { email: string; password: string; clientId: string; redirectUri: string; applicationId: string; foreignApplicationId: string };
  try {
    const info = await fixtureFile.stat();
    assert.ok(info.isFile() && info.uid === process.getuid?.() && (info.mode & 0o077) === 0 && info.size <= 4096);
    fixture = JSON.parse(await fixtureFile.readFile("utf8"));
  } finally {
    await fixtureFile.close();
  }
  const id = applicationId.parse(fixture.applicationId);
  const foreignId = applicationId.parse(fixture.foreignApplicationId);
  const resource = origin + "/api/mcp";
  const redirect = new URL(fixture.redirectUri);
  assert.ok(fixture.email && fixture.password && fixture.clientId && id !== foreignId);
  assert.equal(redirect.protocol, "http:");
  assert.equal(redirect.hostname, "127.0.0.1");
  assert.ok(!redirect.search && !redirect.hash && !redirect.username && !redirect.password);

  const request = (path: string, init: RequestInit = {}) =>
    fetch(origin + path, { ...init, redirect: "manual", signal: AbortSignal.timeout(12000) });
  const form = (path: string, values: Record<string, string>, headers?: HeadersInit) =>
    request(path, { method: "POST", body: new URLSearchParams(values), headers });
  const rpc = (method: string, params: Record<string, unknown>, token?: string) =>
    request("/api/mcp", {
      method: "POST",
      headers: {
        Accept: "application/json, text/event-stream", "Content-Type": "application/json",
        "MCP-Protocol-Version": "2025-11-25", ...(token ? { Authorization: "Bearer " + token } : {}),
      },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }),
    });

  stage = "discovery and public route boundaries";
  const authorization = await request("/.well-known/oauth-authorization-server");
  const protectedResource = await request("/.well-known/oauth-protected-resource/api/mcp");
  assert.equal(authorization.status, 200);
  assert.equal(protectedResource.status, 200);
  assert.equal((await authorization.json()).issuer, origin);
  assert.equal((await protectedResource.json()).resource, resource);
  assert.equal((await rpc("tools/list", {})).status, 401);
  assert.equal((await rpc("tools/list", {}, "synthetic.browser.jwt")).status, 401);
  for (const path of ["/internal/mcp/authorize", "/internal/mcp/redeem", "/internal/mcp/credit/priorities"]) {
    assert.equal((await request(path, { method: "POST", body: "{}" })).status, 404);
  }

  stage = "PKCE consent and token";
  const authorizePath = "/api/mcp/oauth/authorize";
  const registration = { client_id: fixture.clientId, redirect_uri: fixture.redirectUri };
  const verifier = randomBytes(32).toString("base64url");
  const state = randomBytes(24).toString("base64url");
  const query = new URLSearchParams({
    ...registration, state, resource, scope, response_type: "code", code_challenge_method: "S256",
    code_challenge: createHash("sha256").update(verifier).digest("base64url"),
  });
  const consent = await request(authorizePath + "?" + query);
  assert.equal(consent.status, 200);
  const csrf = /name="csrf" value="([A-Za-z0-9_-]{43})"/.exec(await consent.text())?.[1];
  const flowCookie = consent.headers.getSetCookie()[0]?.split(";")[0];
  assert.ok(csrf && flowCookie);
  const approved = await form(authorizePath, {
    csrf, email: fixture.email, password: fixture.password, decision: "approve",
  }, { Cookie: flowCookie, Origin: origin });
  assert.ok(approved.status === 302 || approved.status === 303);
  const callback = new URL(approved.headers.get("Location")!);
  assert.equal(callback.origin + callback.pathname, redirect.href);
  assert.equal(callback.searchParams.get("state"), state);
  assert.equal(callback.searchParams.get("iss"), origin);
  const code = callback.searchParams.get("code");
  assert.ok(code);
  const exchange = { ...registration, resource, code, code_verifier: verifier, grant_type: "authorization_code" };
  const granted = await form("/api/mcp/oauth/token", exchange);
  assert.equal(granted.status, 200);
  const grant = await granted.json();
  const token = grant.access_token;
  assert.match(token, /^mcp_at_[A-Za-z0-9_-]{43}$/);
  let revoked = false;
  async function revoke() {
    const result = await form("/api/mcp/oauth/revoke", { client_id: fixture.clientId, token });
    assert.equal(result.status, 200);
    revoked = true;
  }
  try {
    assert.equal(grant.scope, scope);
    assert.equal((await form("/api/mcp/oauth/token", exchange)).status, 400);
    async function call(method: string, params: Record<string, unknown>) {
      const result = await rpc(method, params, token);
      assert.equal(result.status, 200);
      const body = await result.json();
      assert.ok(body.result && !body.error);
      return body.result;
    }
    stage = "MCP tools reaching Credit";
    const initialized = await call("initialize", {
      protocolVersion: "2025-11-25", capabilities: {}, clientInfo: { name: "synthetic-acceptance", version: "1" },
    });
    assert.equal(initialized.protocolVersion, "2025-11-25");
    const tools = (await call("tools/list", {})).tools;
    assert.deepEqual(tools.map((tool: { name: string }) => tool.name), Object.values(names));
    const invoke = (name: string, args: Record<string, unknown>) => call("tools/call", { name, arguments: args });
    const list = listOutput.parse((await invoke(names.list, { limit: 20 })).structuredContent);
    const summary = summaryOutput.parse((await invoke(names.summary, { applicationId: id })).structuredContent);
    const day5 = previewOutput.parse((await invoke(names.preview, {
      applicationId: id, delayDays: 5, alternativeTermMonths: 24,
    })).structuredContent);
    const day6 = previewOutput.parse((await invoke(names.preview, {
      applicationId: id, delayDays: 6, alternativeTermMonths: 24,
    })).structuredContent);
    assert.ok([list, summary, day5, day6].every(item => item.source === "credit"));
    assert.deepEqual(list.items.find(item => item.applicationId === id), summary.application);
    assert.equal(day5.inputsUsed.receiptIncludedByPayment, true);
    assert.equal(day6.inputsUsed.receiptIncludedByPayment, false);
    assert.deepEqual(day5.basis, day6.basis);
    assertStableSummary(summary, summaryOutput.parse((await invoke(names.summary, { applicationId: id })).structuredContent));

    stage = "WEB and MCP financial parity without saving";
    // This first-party synthetic session is separate from the MCP OAuth token.
    // It is used only against the ordinary WEB routes and never sent to /api/mcp.
    const signedIn = await request("/api/v1/auth/sign-in", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: fixture.email, password: fixture.password }),
    });
    assert.equal(signedIn.status, 200);
    const browserToken = (await signedIn.json()).token;
    assert.ok(typeof browserToken === "string" && /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(browserToken));
    const webPath = "/api/v1/agent/applications/" + id + "/financing-scenario";
    const web = async (path: string, body?: unknown) => {
      const result = await request(path, {
        method: body === undefined ? "GET" : "POST",
        headers: { Authorization: "Bearer " + browserToken, ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      assert.equal(result.status, 200);
      return result.json();
    };
    const savedBefore = await web(webPath);
    for (const mcp of [day5, day6]) {
      const webPreview = previewOutput.parse(await web(webPath + "/preview", webPreviewRequest(mcp)));
      assertFinancialParity(mcp, webPreview);
    }
    assert.deepEqual(await web(webPath), savedBefore);
    assertStableSummary(summary, summaryOutput.parse((await invoke(names.summary, { applicationId: id })).structuredContent));
    const foreign = await invoke(names.summary, { applicationId: foreignId });
    assert.equal(foreign.isError, true);
    assert.equal(JSON.parse(foreign.content[0].text).code, "CASE_NOT_FOUND");

    stage = "token revocation";
    await revoke();
    assert.equal((await rpc("tools/list", {}, token)).status, 401);
  } finally {
    if (!revoked) await revoke();
  }
}
try {
  await run();
  console.log(JSON.stringify({ result: "PASS", flow: "OAuth PKCE → MCP → Credit", tools: 3,
    webFinancialParity: true, savedWebScenarioUnchanged: true, revoked: true }));
} catch {
  console.error(JSON.stringify({ result: "FAIL", stage }));
  process.exitCode = 1;
}
