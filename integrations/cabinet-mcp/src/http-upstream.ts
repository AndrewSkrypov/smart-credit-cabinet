import { directRequest } from "./credit.ts";
import type { TLS, Transport } from "./credit.ts";

type Services = "identity" | "credit";

export function serviceOrigin(value: string, service: Services) {
  const url = new URL(value);
  const allowedHosts = new Set([
    service,
    "cabinet-" + service,
    "cabinet-" + service + ".cabinet-prod.svc",
    "cabinet-" + service + ".cabinet-prod.svc.cluster.local",
    ...(service === "credit" ? [
      "cabinet-credit-mcp",
      "cabinet-credit-mcp.cabinet-prod.svc",
      "cabinet-credit-mcp.cabinet-prod.svc.cluster.local",
    ] : []),
  ]);
  if (url.protocol !== "https:" || (service === "credit" && url.port !== "8443") || !allowedHosts.has(url.hostname)
    || url.username || url.password || url.pathname !== "/" || url.search || url.hash) {
    throw new Error("Expected the private Cabinet service origin.");
  }
  return url;
}

export function upstream(origin: URL, tls: TLS, transport: Transport = directRequest) {
  return (path: string, header: Readonly<Record<string, string>>, payload?: unknown) => {
    const body = payload === undefined ? undefined : JSON.stringify(payload);
    return transport(new URL(path, origin), {
      method: body === undefined ? "GET" : "POST",
      body,
      headers: {
        Accept: "application/json",
        ...header,
        ...(body === undefined ? {} : { "Content-Type": "application/json", "Content-Length": String(Buffer.byteLength(body)) }),
      },
      signal: AbortSignal.timeout(3000),
      tls,
    });
  };
}
