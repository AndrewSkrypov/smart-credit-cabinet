import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { serviceOrigin } from "../dist/http-upstream.js";

test("image smoke uses the private service origins accepted by the actual HTTP runtime", async () => {
  const script = await readFile(new URL("../../../ci/gitlab/quality/mcp-image.sh", import.meta.url), "utf8");
  for (const [variable, service] of [
    ["MCP_CREDIT_ORIGIN", "credit"],
    ["MCP_IDENTITY_ORIGIN", "identity"],
  ] as const) {
    const assignments = [...script.matchAll(new RegExp("-e\\s+" + variable + "=([^\\s]+)", "g"))];
    assert.equal(assignments.length, 1, "The smoke must provide one explicit private origin");
    const actual = assignments[0][1];
    assert.doesNotThrow(() => serviceOrigin(actual, service));
    assert.throws(() => serviceOrigin("https://" + service + "-smoke.invalid:8443", service));
  }
});
