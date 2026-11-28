import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import type { Provider } from "./contract.ts";
import { httpsResource, readScope } from "./http-auth.ts";
import type { Principal, Verify } from "./http-auth.ts";
import { createServer } from "./server.ts";

const maxRequestBytes = 16 * 1024;
type ProviderFor = (principal: Principal) => Provider;

export function createHttpHandler(
  config: { resource: string; issuer: string },
  verify: Verify,
  providerFor: ProviderFor,
) {
  const resource = httpsResource(config.resource);
  httpsResource(config.issuer);
  if (resource.pathname !== "/api/mcp") throw new Error("Expected the cabinet MCP resource at /api/mcp.");

  const metadataPath = "/.well-known/oauth-protected-resource" + resource.pathname;
  const metadataUrl = new URL(metadataPath, resource);
  const challenge = 'Bearer resource_metadata="' + metadataUrl.href + '", scope="' + readScope + '"';
  const headers = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };
  const error = (status: number, code: string, extra: Record<string, string> = {}) =>
    Response.json({ error: code }, { status, headers: { ...headers, ...extra } });

  return async (request: Request): Promise<Response> => {
    const { pathname, search } = new URL(request.url);
    if (search) return error(400, "invalid_request");
    if (pathname === metadataPath) {
      if (request.method !== "GET") return error(405, "method_not_allowed", { Allow: "GET" });
      return Response.json({
        resource: resource.href,
        authorization_servers: [config.issuer],
        scopes_supported: [readScope],
        bearer_methods_supported: ["header"],
      }, { headers });
    }
    if (pathname === "/healthz" && request.method === "GET") {
      return Response.json({ status: "ok" }, { headers });
    }
    if (pathname !== resource.pathname) return error(404, "not_found");

    const origin = request.headers.get("origin");
    if (origin !== null && origin !== resource.origin) return error(403, "forbidden_origin");
    if (request.method !== "POST") return error(405, "method_not_allowed", { Allow: "POST" });
    const authorization = request.headers.get("authorization") ?? "";
    const token = /^Bearer (mcp_at_[A-Za-z0-9_-]{43})$/i.exec(authorization)?.[1];
    if (!token) return error(401, "invalid_token", { "WWW-Authenticate": challenge });
    let verified: Awaited<ReturnType<Verify>>;
    try {
      verified = await verify(token);
    } catch {
      return error(503, "temporarily_unavailable");
    }
    if (verified.status !== "authorized") {
      return error(verified.status === "invalid_token" ? 401 : 403, verified.status, {
        "WWW-Authenticate": challenge + ', error="' + verified.status + '"',
      });
    }

    if (request.headers.get("content-type")?.split(";", 1)[0].trim().toLowerCase() !== "application/json") {
      return error(415, "unsupported_media_type");
    }

    let body: unknown;
    try {
      const chunks: Uint8Array[] = [];
      let size = 0;
      const reader = request.body?.getReader();
      if (!reader) return error(400, "invalid_request");
      const abortReading = () => { void reader.cancel().catch(() => {}); };
      request.signal.addEventListener("abort", abortReading, { once: true });
      try {
        request.signal.throwIfAborted();
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          size += value.byteLength;
          if (size > maxRequestBytes) {
            void reader.cancel().catch(() => {});
            return error(413, "request_too_large");
          }
          chunks.push(value);
        }
        request.signal.throwIfAborted();
      } finally {
        request.signal.removeEventListener("abort", abortReading);
        reader.releaseLock();
      }
      body = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)));
    } catch {
      return error(400, "invalid_request");
    }
    if (Array.isArray(body)) return error(400, "invalid_request");

    const transport = new WebStandardStreamableHTTPServerTransport({
      sessionIdGenerator: undefined,
      enableJsonResponse: true,
    });
    const server = createServer({
      list: args => providerFor(verified.principal).list(args),
      summary: args => providerFor(verified.principal).summary(args),
      preview: args => providerFor(verified.principal).preview(args),
    }, "credit");
    try {
      await server.connect(transport);
      const response = await transport.handleRequest(request, { parsedBody: body });
      for (const [name, value] of Object.entries(headers)) response.headers.set(name, value);
      return response;
    } catch {
      return error(503, "temporarily_unavailable");
    } finally {
      await server.close();
    }
  };
}
