import https from "node:https";
import { readFileSync } from "node:fs";
const read = name => readFileSync(process.env[name]);
function request(origin, path, tls, payload) {
    return new Promise((resolve, reject) => {
        const req = https.request(new URL(path, origin), {
            ...tls,
            method: payload ? "POST" : "GET",
            headers: payload ? { "Content-Type": "application/json" } : {},
            timeout: 2500,
        }, res => {
            res.resume();
            resolve(res.statusCode);
        });
        req.on("timeout", () => req.destroy(new Error("timeout")));
        req.on("error", reject);
        req.end(payload ? JSON.stringify(payload) : undefined);
    });
}
const identity = process.env.MCP_IDENTITY_ORIGIN;
const identityTls = { ca: read("MCP_IDENTITY_CA_FILE") };
const credit = process.env.MCP_CREDIT_ORIGIN;
const creditTls = { ca: read("MCP_CREDIT_CA_FILE"),
                    cert: read("MCP_CREDIT_CERT_FILE"), key: read("MCP_CREDIT_KEY_FILE") };
const path = "/internal/mcp/credit/priorities";
const checks = await Promise.all([
  request(identity, "/internal/mcp/authorize", identityTls, { accessToken: "not-a-token" }),
  request(identity, "/internal/mcp/redeem", identityTls, { actorAssertion: "not-an-assertion" }),
  request(identity, "/actuator/health", identityTls),
  request(credit, path, creditTls),
  request(credit, path, { ca: creditTls.ca }).then(() => false, error => error.code === "ERR_SSL_TLSV13_ALERT_CERTIFICATE_REQUIRED"
                                                               || error.code === "ERR_SSL_SSLV3_ALERT_HANDSHAKE_FAILURE"
                                                               || error.code === "ECONNRESET"),
]);
if (JSON.stringify(checks) !== "[401,401,404,401,true]") process.exit(1);
