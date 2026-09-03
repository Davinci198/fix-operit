/**
 * Tool registry and execution system
 */

import { z, ZodSchema, infer } from '../schema';
import { createContext, Context, Service, Plugin, definePlugin } from '../cordis';

export interface Tool<TParams = any, TResult = any> {
  name: string;
  description: string;
  parameters: ZodSchema<TParams>;
  execute(params: TParams): Promise<TResult> | TResult;
}

export interface ToolRegistry extends Service<ToolRegistry> {
  register<TParams, TResult>(tool: Tool<TParams, TResult>): void;
  unregister(name: string): void;
  get(name: string): Tool | undefined;
  list(): Tool[];
  execute<TParams, TResult>(name: string, params: TParams): Promise<TResult>;
}

export const ToolRegistry: Service<ToolRegistry> = class ToolRegistryImpl implements ToolRegistry {
  private tools = new Map<string, Tool>();

  register<TParams, TResult>(tool: Tool<TParams, TResult>) {
    this.tools.set(tool.name, tool);
  }

  unregister(name: string) {
    this.tools.delete(name);
  }

  get(name: string) {
    return this.tools.get(name);
  }

  list() {
    return Array.from(this.tools.values());
  }

  async execute<TParams, TResult>(name: string, params: TParams): Promise<TResult> {
    const tool = this.tools.get(name);
    if (!tool) throw new Error(`Tool not found: ${name}`);
    const validated = tool.parameters.parse(params);
    return tool.execute(validated);
  }
};

// Helper to create tools easily
export function createTool<TParams, TResult>(
  name: string,
  description: string,
  parameters: ZodSchema<TParams>,
  execute: (params: TParams) => Promise<TResult> | TResult
): Tool<TParams, TResult> {
  return { name, description, parameters, execute };
}

// Plugin for tool registry
export const toolRegistryPlugin: Plugin = definePlugin({
  name: 'tool-registry',
  apply(ctx: Context) {
    ctx.register(ToolRegistry);
  },
});

// Tool calling types for LLM
export interface ToolCall {
  id: string;
  name: string;
  arguments: string; // JSON string
}

export interface ToolResult {
  callId: string;
  result: any;
  error?: string;
}

export function parseToolCalls(content: string): ToolCall[] {
  // Simple parsing for tool calls in format: <tool:name>{"args": "value"}</tool>
  const calls: ToolCall[] = [];
  const regex = /<tool:(\w+)>([\s\S]*?)<\/tool>/g;
  let match;
  while ((match = regex.exec(content)) !== null) {
    calls.push({ id: crypto.randomUUID(), name: match[1], arguments: match[2].trim() });
  }
  return calls;
}

export async function executeToolCalls(registry: ToolRegistry, calls: ToolCall[]): Promise<ToolResult[]> {
  const results: ToolResult[] = [];
  for (const call of calls) {
    try {
      const params = JSON.parse(call.arguments);
      const result = await registry.execute(call.name, params);
      results.push({ callId: call.id, result });
    } catch (e: any) {
      results.push({ callId: call.id, result: null, error: e.message });
    }
  }
  return results;
}
