import { chmod, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { TestContext } from "node:test";
import { createFixtures, fixtureIds } from "../dist/fixtures.js";

export const origin = "http://127.0.0.1:43210";
export const syntheticToken = "synthetic.local.signature";
export const previewArgs = { applicationId: fixtureIds.vector, delayDays: 6, alternativeTermMonths: 24 };

export async function tokenFile(t: TestContext) {
  const dir = await mkdtemp(join(tmpdir(), "cabinet-mcp-token-test-"));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const path = join(dir, "synthetic-token");
  await writeFile(path, syntheticToken + "\n", { mode: 0o600, flag: "wx" });
  await chmod(path, 0o600);
  return path;
}

export async function creditResponses() {
  const { provider } = createFixtures("agent");
  return {
    list: { ...await provider.list({ limit: 1 }), source: "credit" },
    summary: { ...await provider.summary({ applicationId: fixtureIds.vector }), source: "credit" },
    preview: { ...await provider.preview(previewArgs), source: "credit" },
  };
}
