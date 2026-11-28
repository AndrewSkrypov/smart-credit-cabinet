import { json } from "./credit.ts";
import type { TLS, Transport } from "./credit.ts";
import { verifyGrant } from "./http-auth.ts";
import { serviceOrigin, upstream } from "./http-upstream.ts";

export function identityAuthorization(config: {
  resource: string; identityOrigin: string; serviceToken: string; identityTls: TLS;
}, transport?: Transport) {
  if (!/^[A-Za-z0-9_-]{32,128}$/.test(config.serviceToken)) {
    throw new Error("Expected the scoped MCP Identity service credential.");
  }
  const submit = upstream(serviceOrigin(config.identityOrigin, "identity"), config.identityTls, transport);
  return verifyGrant(config.resource, async accessToken => {
    const reply = await submit("/internal/mcp/authorize", {
      Authorization: "Bearer " + config.serviceToken,
    }, { accessToken });
    try {
      if (reply.status === 401 || reply.status === 403) return null;
      if (reply.status !== 200) throw new Error("Identity authorization unavailable.");
      return await json(reply);
    } finally {
      reply.close();
    }
  });
}
