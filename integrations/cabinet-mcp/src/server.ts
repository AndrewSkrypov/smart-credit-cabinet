import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";
import {
  listInput, listOutput, previewInput, previewOutput, summaryInput, summaryOutput, ToolError,
} from "./contract.ts";
import type { Provider } from "./contract.ts";

// Preserve raw argument keys for the tool schema. The SDK record parser normalizes
// prototype-related keys before the tool would otherwise have a chance to reject them.
const callRequest = CallToolRequestSchema.extend({
  params: CallToolRequestSchema.shape.params.extend({ arguments: z.unknown().optional() }),
});

function tool<Input, Output extends Record<string, unknown>>(
  description: string,
  input: z.ZodType<Input>,
  output: z.ZodType<Output>,
  run: (args: Input) => Promise<Output>,
  source: "synthetic-fixture" | "credit",
) {
  const schemaOptions = { target: "draft-07" } as const;
  return {
    definition: {
      description,
      inputSchema: { ...z.toJSONSchema(input, { ...schemaOptions, io: "input" }), type: "object" as const },
      outputSchema: { ...z.toJSONSchema(output, schemaOptions), type: "object" as const },
      annotations: { readOnlyHint: true },
    },
    async invoke(args: unknown) {
      const parsed = input.safeParse(args);
      if (!parsed.success) throw new ToolError("INVALID_INPUT");
      const result = output.safeParse(await run(parsed.data));
      if (!result.success || result.data.source !== source) {
        throw new ToolError("DEPENDENCY_UNAVAILABLE");
      }
      return result.data;
    },
  };
}

export function createServer(provider: Provider, source: "synthetic-fixture" | "credit" = "synthetic-fixture") {
  const tools = new Map(Object.entries({
    list_agent_next_actions: tool(
      "Показывает доступные заявки, по которым есть следующее действие, с ответственным и сроком.",
      listInput, listOutput, args => provider.list(args), source,
    ),
    get_agent_application_summary: tool(
      "Показывает текущий шаг по заявке и состояние запроса выписки.",
      summaryInput, summaryOutput, args => provider.summary(args), source,
    ),
    preview_agent_financing_scenario: tool(
      "Сравнивает два срока для сохранённой примерки по заданной задержке поступления. Примерка не сохраняется.",
      previewInput, previewOutput, args => provider.preview(args), source,
    ),
  }));

  const server = new Server({ name: "pump-cabinet-mcp", version: "0.1.0" }, { capabilities: { tools: {} } });
  server.setRequestHandler(ListToolsRequestSchema, () => ({
    tools: [...tools].map(([name, entry]) => ({ name, ...entry.definition })),
  }));
  server.setRequestHandler(callRequest, async ({ params }) => {
    try {
      const entry = tools.get(params.name);
      if (!entry) throw new ToolError("INVALID_INPUT");
      const structuredContent = await entry.invoke(params.arguments ?? {});
      return { structuredContent, content: [{ type: "text", text: JSON.stringify(structuredContent) }] };
    } catch (error) {
      const safe = error instanceof ToolError ? error : new ToolError("DEPENDENCY_UNAVAILABLE");
      return { isError: true, content: [{ type: "text", text: JSON.stringify({ code: safe.code, message: safe.message }) }] };
    }
  });
  return server;
}
