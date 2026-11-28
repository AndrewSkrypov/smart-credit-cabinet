import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { createCreditProvider } from "./credit.ts";
import { createFixtures, profiles } from "./fixtures.ts";
import { createServer } from "./server.ts";

function getProfile(args: string[]) {
  if (args.length === 0) return undefined;
  const profile = profiles.find(candidate => candidate === args[1]);
  if (args.length !== 2 || args[0] !== "--profile" || !profile) throw new Error("Invalid launch options.");
  return profile;
}

try {
  const args = process.argv.slice(2);
  const server = args.length === 1 && args[0] === "--local-credit"
    ? createServer(createCreditProvider({
      origin: process.env.CABINET_MCP_CREDIT_ORIGIN ?? "",
      tokenFile: process.env.CABINET_MCP_CREDIT_TOKEN_FILE ?? "",
    }), "credit")
    : createServer(createFixtures(getProfile(args)).provider);
  server.onerror = () => { console.error("Ошибка протокола MCP."); };
  await server.connect(new StdioServerTransport());
} catch {
  console.error("Не удалось запустить локальный MCP. Проверьте параметры запуска.");
  process.exitCode = 1;
}
