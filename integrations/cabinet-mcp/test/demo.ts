import { demoResults, openStdio, requestTimeout } from "./client.ts";

const connection = await openStdio("agent");
try {
  const { tools } = await connection.client.listTools(undefined, requestTimeout);
  const responses = await demoResults(connection.client);
  if (tools.length !== 3 || Object.values(responses).some(result => result.isError || !result.structuredContent)) {
    throw new Error("Synthetic MCP verification failed.");
  }
  console.log(JSON.stringify(
    Object.fromEntries(Object.entries(responses).map(([name, result]) => [name, result.structuredContent])), null, 2,
  ));
} finally {
  await connection.close();
}
