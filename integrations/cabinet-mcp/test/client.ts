import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";

export const main = fileURLToPath(new URL("../dist/main.js", import.meta.url));
export const names = {
  list: "list_agent_next_actions",
  summary: "get_agent_application_summary",
  preview: "preview_agent_financing_scenario",
} as const;
export const requestTimeout = { timeout: 5000 } as const;

export async function openStdio(profile?: string) {
  return connectStdio(profile ? ["--profile", profile] : []);
}

export async function openCreditStdio(origin: string, tokenFile: string) {
  return connectStdio(["--local-credit"], {
    CABINET_MCP_CREDIT_ORIGIN: origin,
    CABINET_MCP_CREDIT_TOKEN_FILE: tokenFile,
  });
}

async function connectStdio(args: string[], env?: Record<string, string>) {
  const client = new Client({ name: "pump-cabinet-verifier", version: "0.1.0" });
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: [main, ...args],
    env,
    stderr: "pipe",
  });
  let stderr = "";
  transport.stderr?.setEncoding("utf8").on("data", chunk => { stderr += chunk; });
  try {
    await client.connect(transport, requestTimeout);
  } catch (error) {
    await transport.close();
    throw error;
  }
  return { client, close: () => client.close(), stderr: () => stderr };
}

export async function call(client: Client, name: string, args: Record<string, unknown> = {}) {
  return client.callTool({ name, arguments: args }, undefined, requestTimeout);
}

export async function demoResults(client: Client) {
  const list = await call(client, names.list);
  const summary = await call(client, names.summary, { applicationId: "6b7a0f51-2958-4228-bb7b-68f23d57b161" });
  const preview = await call(client, names.preview, {
    applicationId: "40188819-ffc2-4caa-a57f-a8f9414391c1", delayDays: 6, alternativeTermMonths: 24,
  });
  return { [names.list]: list, [names.summary]: summary, [names.preview]: preview };
}

export function assertToolError(result: Awaited<ReturnType<typeof call>>, code: string) {
  assert.equal(result.isError, true);
  assert.equal(result.structuredContent, undefined);
  assert.ok(Array.isArray(result.content));
  assert.equal(result.content.length, 1);
  assert.equal(result.content[0].type, "text");
  const error = JSON.parse(String(result.content[0].text));
  assert.equal(error.code, code);
  assert.deepEqual(Object.keys(error).sort(), ["code", "message"]);
  assert.equal(typeof error.message, "string");
}
