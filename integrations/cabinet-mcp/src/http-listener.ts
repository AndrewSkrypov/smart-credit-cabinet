import { createServer } from "node:http";
import { Readable } from "node:stream";

type Handler = (request: Request) => Promise<Response>;

// HTTP is private behind the TLS-terminating gateway. Ignore Host and forwarded headers.
export function httpListener(resource: string, handle: Handler) {
  const origin = new URL(resource).origin;
  return createServer({
    requestTimeout: 10000, headersTimeout: 5000, keepAliveTimeout: 5000, maxHeaderSize: 8192,
  }, async (req, res) => {
    const abort = AbortSignal.timeout(10000);
    try {
      if (!req.url?.startsWith("/") || req.url.startsWith("//") || req.url.includes("\\")) {
        res.writeHead(400).end();
        return;
      }
      const method = req.method ?? "GET";
      const headers = new Headers();
      for (let i = 0; i < req.rawHeaders.length; i += 2) headers.append(req.rawHeaders[i], req.rawHeaders[i + 1]);
      const hasBody = method !== "GET" && method !== "HEAD";
      const request = new Request(new URL(req.url, origin), {
        method, headers, signal: abort,
        ...(hasBody ? { body: Readable.toWeb(req) as ReadableStream, duplex: "half" } : {}),
      });
      const response = await handle(request);
      res.writeHead(response.status, Object.fromEntries(response.headers));
      res.end(response.body === null ? undefined : Buffer.from(await response.arrayBuffer()));
    } catch {
      if (!res.headersSent) res.writeHead(503, { "Cache-Control": "no-store" }).end();
      else res.destroy();
    }
  });
}
