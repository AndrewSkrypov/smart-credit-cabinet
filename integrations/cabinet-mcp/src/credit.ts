import { constants } from "node:fs";
import { open } from "node:fs/promises";
import { request as httpRequest } from "node:http";
import type { IncomingMessage } from "node:http";
import { request as httpsRequest } from "node:https";
import { isAbsolute } from "node:path";
import type { ConnectionOptions } from "node:tls";
import type { z } from "zod";
import {
  listInput, listOutput, previewInput, previewOutput, summaryInput, summaryOutput, ToolError,
} from "./contract.ts";
import type { CaseItem, Provider } from "./contract.ts";

const maxCredentialBytes = 8192;
const maxResponseBytes = 128 * 1024;
const requestDeadlineMs = 3000;

export type TLS = Pick<ConnectionOptions, "ca" | "cert" | "key">;

export type RequestOptions = {
  method: "GET" | "POST";
  headers: Record<string, string>;
  body?: string;
  signal: AbortSignal;
  tls?: TLS;
};
export type Reply = {
  status: number;
  contentType?: string;
  body: AsyncIterable<Uint8Array>;
  close(): void;
};
export type Transport = (url: URL, options: RequestOptions) => Promise<Reply>;

export function directRequest(url: URL, { body, tls, ...options }: RequestOptions): Promise<Reply> {
  return new Promise((resolve, reject) => {
    // Explicit connection to the trusted origin. No redirects or inherited proxy agent.
    const onResponse = (response: IncomingMessage) => {
      resolve({
        status: response.statusCode ?? 0,
        contentType: response.headers["content-type"],
        body: response,
        close: () => { response.destroy(); },
      });
    };
    const connection = { ...options, agent: false as const };
    const pending = url.protocol === "https:"
      ? httpsRequest(url, { ...connection, ...tls, rejectUnauthorized: true, minVersion: "TLSv1.2" }, onResponse)
      : httpRequest(url, connection, onResponse);
    pending.once("error", reject);
    pending.end(body);
  });
}

async function readCredential(path: string) {
  try {
    // Check the opened descriptor to avoid following a symlink or racing a path replacement.
    const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW | constants.O_NONBLOCK);
    try {
      const info = await file.stat();
      if (!info.isFile() || info.uid !== process.getuid?.() || (info.mode & 0o7777) !== 0o600
        || info.size < 1 || info.size > maxCredentialBytes) {
        throw new Error("Invalid credential file.");
      }
      const bytes = Buffer.alloc(maxCredentialBytes + 1);
      const { bytesRead } = await file.read(bytes);
      if (bytesRead !== info.size) throw new Error("Invalid credential file.");
      const token = bytes.subarray(0, bytesRead).toString("utf8").replace(/\r?\n$/, "");
      if (!/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token)) {
        throw new Error("Invalid credential format.");
      }
      return token;
    } finally {
      await file.close();
    }
  } catch {
    throw new ToolError("UNAUTHENTICATED");
  }
}

export async function json(reply: Reply): Promise<unknown> {
  if (reply.contentType?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
    throw new ToolError("DEPENDENCY_UNAVAILABLE");
  }
  const chunks: Uint8Array[] = [];
  let length = 0;
  for await (const chunk of reply.body) {
    length += chunk.byteLength;
    if (length > maxResponseBytes) throw new ToolError("DEPENDENCY_UNAVAILABLE");
    chunks.push(chunk);
  }
  return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)));
}

export async function rejectStatus(reply: Reply): Promise<never> {
  switch (reply.status) {
    case 401: throw new ToolError("UNAUTHENTICATED");
    case 403: throw new ToolError("FORBIDDEN");
    case 400:
    case 404:
    case 409: {
      const data = await json(reply);
      if (data && typeof data === "object" && Object.keys(data).length === 1 && "code" in data) {
        const code = data.code;
        if ((reply.status === 400 && code === "INVALID_INPUT")
          || (reply.status === 404 && code === "CASE_NOT_FOUND")
          || (reply.status === 409 && (code === "PREVIEW_NOT_READY" || code === "SCENARIO_NOT_APPLICABLE"))) {
          throw new ToolError(code);
        }
      }
      break;
    }
  }
  throw new ToolError("DEPENDENCY_UNAVAILABLE");
}

function parse<T>(schema: z.ZodType<T>, value: unknown, code: "INVALID_INPUT" | "DEPENDENCY_UNAVAILABLE") {
  const parsed = schema.safeParse(value);
  if (!parsed.success) throw new ToolError(code);
  return parsed.data;
}

function assertCasePath(item: CaseItem) {
  const base = "/agent/applications/" + item.applicationId.toLowerCase();
  const path = item.cabinetPath.toLowerCase();
  if (path !== base && path !== base + "#application-info-request") throw new ToolError("DEPENDENCY_UNAVAILABLE");
}

export function createCreditProvider(
  config: { origin: string; tokenFile: string },
  transport: Transport = directRequest,
): Provider {
  if (!/^https?:\/\/127\.0\.0\.1:[1-9]\d{0,4}\/?$/.test(config.origin)
    || !isAbsolute(config.tokenFile)) {
    throw new Error("Expected an explicit IPv4 loopback origin and an absolute credential path.");
  }
  const origin = new URL(config.origin);
  const tokenFile = config.tokenFile;

  async function operation<T extends { source: string }>(
    path: string,
    schema: z.ZodType<T>,
    payload?: { mode: "SAVED_SCENARIO"; delayDays: number; alternativeTermMonths: number },
  ): Promise<T> {
    const token = await readCredential(tokenFile);
    const headers: Record<string, string> = { Accept: "application/json", Authorization: "Bearer " + token };
    const body = payload === undefined ? undefined : JSON.stringify(payload);
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      headers["Content-Length"] = String(Buffer.byteLength(body));
    }
    try {
      const reply = await transport(new URL(path, origin), {
        method: body === undefined ? "GET" : "POST", headers, body,
        signal: AbortSignal.timeout(requestDeadlineMs),
      });
      try {
        if (reply.status !== 200) return await rejectStatus(reply);
        const result = parse(schema, await json(reply), "DEPENDENCY_UNAVAILABLE");
        if (result.source !== "credit") throw new ToolError("DEPENDENCY_UNAVAILABLE");
        return result;
      } finally {
        reply.close();
      }
    } catch (error) {
      if (error instanceof ToolError) throw error;
      throw new ToolError("DEPENDENCY_UNAVAILABLE");
    }
  }

  return creditOperations(operation);
}

export type CreditOperation = <T extends { source: string }>(
  path: string,
  schema: z.ZodType<T>,
  payload?: { mode: "SAVED_SCENARIO"; delayDays: number; alternativeTermMonths: number },
) => Promise<T>;

export function creditOperations(operation: CreditOperation): Provider {
  return Object.freeze({
    async list(args) {
      const { limit } = parse(listInput, args, "INVALID_INPUT");
      const result = await operation("/api/v1/agent/mcp/priorities?limit=" + limit, listOutput);
      if (result.items.length > limit) throw new ToolError("DEPENDENCY_UNAVAILABLE");
      for (const item of result.items) {
        assertCasePath(item);
        if (item.actionOwner === null) throw new ToolError("DEPENDENCY_UNAVAILABLE");
      }
      return result;
    },
    async summary(args) {
      const { applicationId } = parse(summaryInput, args, "INVALID_INPUT");
      const id = applicationId.toLowerCase();
      const result = await operation("/api/v1/agent/mcp/applications/" + id + "/summary", summaryOutput);
      assertCasePath(result.application);
      if (result.application.applicationId.toLowerCase() !== id) throw new ToolError("DEPENDENCY_UNAVAILABLE");
      return result;
    },
    async preview(args) {
      const { applicationId, delayDays, alternativeTermMonths } = parse(previewInput, args, "INVALID_INPUT");
      if (alternativeTermMonths > 2147483647) throw new ToolError("INVALID_INPUT");
      const id = applicationId.toLowerCase();
      const result = await operation("/api/v1/agent/mcp/applications/" + id + "/financing-scenario/preview",
        previewOutput, { mode: "SAVED_SCENARIO", delayDays, alternativeTermMonths });
      if (result.cabinetPath.toLowerCase() !== "/agent/applications/" + id + "#draft-financing"
        || result.basis.savedScenarioRevision === null
        || result.inputsUsed.delayDays !== delayDays || result.inputsUsed.alternativeTermMonths !== alternativeTermMonths
        || result.options[0].termMonths !== result.inputsUsed.baseTermMonths
        || result.options[1].termMonths !== alternativeTermMonths) {
        throw new ToolError("DEPENDENCY_UNAVAILABLE");
      }
      return result;
    },
  } satisfies Provider);
}
