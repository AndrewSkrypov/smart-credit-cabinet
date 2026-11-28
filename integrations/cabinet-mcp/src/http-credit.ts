import { creditOperations, json, rejectStatus } from "./credit.ts";
import type { TLS, Transport } from "./credit.ts";
import { ToolError } from "./contract.ts";
import type { Principal } from "./http-auth.ts";
import { serviceOrigin, upstream } from "./http-upstream.ts";

const agentPrefix = "/api/v1/agent/";
const actorHeader = "X-Cabinet-Mcp-Actor-Assertion";

function internalPath(path: string) {
  const relative = path.slice(agentPrefix.length);
  return "/internal/mcp/credit/" + (relative.startsWith("mcp/") ? relative.slice(4) : relative);
}

export function creditForActor(origin: string, tls: TLS, transport?: Transport) {
  const submit = upstream(serviceOrigin(origin, "credit"), tls, transport);
  return ({ actorAssertion }: Principal) => creditOperations(async (path, schema, payload) => {
    const reply = await submit(internalPath(path), { [actorHeader]: actorAssertion }, payload);
    try {
      if (reply.status !== 200) return await rejectStatus(reply);
      const result = schema.safeParse(await json(reply));
      if (!result.success || result.data.source !== "credit") throw new ToolError("DEPENDENCY_UNAVAILABLE");
      return result.data;
    } finally {
      reply.close();
    }
  });
}
