/**
 * The agent-facing MCP endpoint: Streamable HTTP at /mcp with the Stage 1 tool
 * `vault_display_command`, authenticated with MCP_TOKEN.
 */
import { AsyncLocalStorage } from "node:async_hooks";
import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { z } from "zod";

import { bearerTokenMatches } from "./auth.ts";
import { LiveCommandFailure, type LiveCommandBridge } from "./live/bridge.ts";
import { MAX_COMMAND_TEXT_BYTES } from "./live/command.ts";

export const DISPLAY_COMMAND_TOOL = "vault_display_command";

const INSTRUCTIONS =
  "Stage 1 diagnostic server for seeker-vault. vault_display_command shows text on the " +
  "owner's Seeker phone and returns their OK. It never signs or sends transactions.";

const TOOL_DESCRIPTION =
  "Shows display-only text on the owner's Seeker (its live-test screen must be open) and " +
  'waits until they tap OK, then returns {"id", "result": "OK"}. The text is never executed. ' +
  "Errors start with a code: OFFLINE (no phone connected), BUSY (another command is " +
  "waiting), INVALID_TEXT (empty or too long), TIMEOUT (nobody tapped OK in time), or " +
  "CANCELLED (the phone disconnected or the call was cancelled).";

// Hostnames that pass the Host and Origin checks (a defense against DNS rebinding).
const LOOPBACK_HOSTNAMES = new Set(["127.0.0.1", "localhost", "[::1]", '100.119.134.109']);

export interface McpEndpoint {
  handle(req: IncomingMessage, res: ServerResponse): Promise<void>;
  close(): Promise<void>;
}

export function createMcpEndpoint(
  bridge: LiveCommandBridge,
  mcpToken: string,
  log: (message: string) => void,
): McpEndpoint {
  const sessions = new Map<string, StreamableHTTPServerTransport>();
  // Aborts when the connection carrying a tool call closes before its response is sent.
  const connectionClosed = new AsyncLocalStorage<AbortSignal>();

  function createServer(): McpServer {
    const server = new McpServer(
      { name: "seeker-vault", version: "0.1.0" },
      { instructions: INSTRUCTIONS },
    );
    server.registerTool(
      DISPLAY_COMMAND_TOOL,
      {
        title: "Display text on the Seeker",
        description: TOOL_DESCRIPTION,
        inputSchema: {
          text: z
            .string()
            .describe(
              `Plain text to display, 1 to ${MAX_COMMAND_TEXT_BYTES} UTF-8 bytes.`,
            ),
        },
        outputSchema: {
          id: z.string().describe("The command ID the phone acknowledged."),
          result: z.literal("OK"),
        },
        annotations: {
          readOnlyHint: false,
          destructiveHint: false,
          idempotentHint: false,
          openWorldHint: false,
        },
      },
      async ({ text }, extra): Promise<CallToolResult> => {
        const closed = connectionClosed.getStore();
        const signal =
          closed === undefined
            ? extra.signal
            : AbortSignal.any([extra.signal, closed]);
        try {
          const acknowledgement = await bridge.display(text, signal);
          return {
            content: [{ type: "text", text: JSON.stringify(acknowledgement) }],
            structuredContent: { ...acknowledgement },
          };
        } catch (error) {
          if (!(error instanceof LiveCommandFailure)) throw error;
          // No structuredContent: clients validate it against the success schema.
          return {
            isError: true,
            content: [
              { type: "text", text: `${error.code}: ${error.message}` },
            ],
          };
        }
      },
    );
    return server;
  }

  async function handle(
    req: IncomingMessage,
    res: ServerResponse,
  ): Promise<void> {
    const rejection = rejectionFor(req, mcpToken);
    if (rejection !== undefined) {
      log(`rejected ${req.method ?? "?"} /mcp: ${rejection.reason}`);
      const headers: Record<string, string> =
        rejection.status === 401
          ? { "WWW-Authenticate": 'Bearer realm="seeker-vault"' }
          : {};
      sendError(res, rejection.status, rejection.reason, headers);
      return;
    }

    const sessionId = req.headers["mcp-session-id"];
    let transport =
      typeof sessionId === "string" ? sessions.get(sessionId) : undefined;
    if (typeof sessionId === "string" && transport === undefined) {
      sendError(res, 404, "unknown MCP session; initialize a new one");
      return;
    }
    if (transport === undefined) {
      const created = new StreamableHTTPServerTransport({
        sessionIdGenerator: () => randomUUID(),
        onsessioninitialized: (id) => {
          sessions.set(id, created);
          log("MCP session opened");
        },
      });
      created.onclose = () => {
        if (
          created.sessionId !== undefined &&
          sessions.delete(created.sessionId)
        )
          log("MCP session closed");
      };
      await createServer().connect(created);
      transport = created;
    }

    const active = transport;
    const closed = new AbortController();
    res.on("close", () => {
      if (!res.writableFinished) closed.abort();
    });
    await connectionClosed.run(closed.signal, () =>
      active.handleRequest(req, res),
    );
  }

  async function close(): Promise<void> {
    const open = [...sessions.values()];
    sessions.clear();
    await Promise.all(open.map((transport) => transport.close()));
  }

  return { handle, close };
}

function rejectionFor(
  req: IncomingMessage,
  mcpToken: string,
): { readonly status: 401 | 403; readonly reason: string } | undefined {
  if (!isLoopback(hostnameOf(`http://${req.headers.host ?? ""}`))) {
    return { status: 403, reason: "the Host header is not a loopback address" };
  }
  const origin = req.headers.origin;
  if (origin !== undefined && !isLoopback(hostnameOf(origin))) {
    return {
      status: 403,
      reason: "the Origin header is not a loopback origin",
    };
  }
  if (!bearerTokenMatches(req.headers.authorization, mcpToken)) {
    return { status: 401, reason: "a valid MCP token is required" };
  }
  return undefined;
}

function hostnameOf(url: string): string | undefined {
  try {
    return new URL(url).hostname;
  } catch {
    return undefined;
  }
}

function isLoopback(hostname: string | undefined): boolean {
  return hostname !== undefined && LOOPBACK_HOSTNAMES.has(hostname);
}

function sendError(
  res: ServerResponse,
  status: number,
  message: string,
  headers: Record<string, string> = {},
): void {
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(
    JSON.stringify({
      jsonrpc: "2.0",
      error: { code: -32000, message },
      id: null,
    }),
  );
}
