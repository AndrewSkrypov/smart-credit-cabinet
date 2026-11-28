import { z } from "zod";

export const readScope = "cabinet:mcp:read";

const grant = z.strictObject({
  actorId: z.uuid(),
  role: z.literal("AGENT"),
  resource: z.string(),
  scope: z.string().max(2048),
  actorAssertion: z.string().regex(/^mcp_ca_[A-Za-z0-9_-]{43}$/),
});

export type Principal = Readonly<{ subject: string; actorAssertion: string }>;
export type Authorization =
  | { status: "authorized"; principal: Principal }
  | { status: "invalid_token" | "insufficient_scope" };
export type Verify = (accessToken: string) => Promise<Authorization>;

export function httpsResource(value: string) {
  const url = new URL(value);
  if (url.protocol !== "https:" || url.username || url.password || url.search || url.hash
    || (url.href !== value && url.origin !== value)) {
    throw new Error("Expected a canonical HTTPS URL.");
  }
  return url;
}

export function verifyGrant(resource: string, authorize: (accessToken: string) => Promise<unknown>): Verify {
  httpsResource(resource);
  return async accessToken => {
    // The private issuer checks expiry, revocation, scope and the current account for every request.
    const result = await authorize(accessToken);
    if (result === null) return { status: "invalid_token" };
    const parsed = grant.safeParse(result);
    if (!parsed.success || parsed.data.resource !== resource) return { status: "invalid_token" };
    if (parsed.data.scope !== readScope) return { status: "insufficient_scope" };
    const { actorId, actorAssertion } = parsed.data;
    return { status: "authorized", principal: Object.freeze({ subject: actorId, actorAssertion }) };
  };
}
