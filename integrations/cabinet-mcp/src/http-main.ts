import { readFile } from "node:fs/promises";
import { isAbsolute } from "node:path";
import { createSecureContext } from "node:tls";
import { creditForActor } from "./http-credit.ts";
import { identityAuthorization } from "./http-identity.ts";
import { httpListener } from "./http-listener.ts";
import { createHttpHandler } from "./http-server.ts";

async function pem(name: string) {
  const filename = process.env[name] ?? "";
  if (!isAbsolute(filename)) throw new Error("Expected absolute TLS material path.");
  return readFile(filename);
}

try {
  const config = {
    resource: process.env.MCP_RESOURCE ?? "",
    issuer: process.env.MCP_ISSUER ?? "",
    identityOrigin: process.env.MCP_IDENTITY_ORIGIN ?? "",
    serviceToken: process.env.MCP_IDENTITY_SERVICE_TOKEN ?? "",
  };
  if (config.resource !== config.issuer + "/api/mcp") throw new Error("MCP resource must use the issuer origin.");

  const [identityCa, creditCa, creditCert, creditKey] = await Promise.all([
    pem("MCP_IDENTITY_CA_FILE"), pem("MCP_CREDIT_CA_FILE"), pem("MCP_CREDIT_CERT_FILE"), pem("MCP_CREDIT_KEY_FILE"),
  ]);
  const identityTls = { ca: identityCa };
  const creditTls = { ca: creditCa, cert: creditCert, key: creditKey };
  createSecureContext(identityTls);
  createSecureContext(creditTls);
  const verify = identityAuthorization({ ...config, identityTls });
  const providerFor = creditForActor(process.env.MCP_CREDIT_ORIGIN ?? "", creditTls);
  const server = httpListener(config.resource, createHttpHandler(config, verify, providerFor));
  server.on("error", () => {
    console.error("MCP HTTP listener unavailable.");
    process.exitCode = 1;
  });
  server.listen(8080, "0.0.0.0");
  for (const signal of ["SIGINT", "SIGTERM"] as const) process.once(signal, () => server.close());
} catch {
  console.error("MCP HTTP configuration is invalid.");
  process.exitCode = 1;
}
