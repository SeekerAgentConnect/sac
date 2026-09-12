/**
 * The agent-facing MCP endpoint: Streamable HTTP at /mcp, authenticated with MCP_TOKEN. It serves
 * the Stage 1 tool `vault_display_command` and the durable request tools (requests/mcp-tools.ts),
 * with the demo tool `vault_request_ack` only when MCP_DEMO_TOOLS is set.
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
import { registerRequestTools } from "./requests/mcp-tools.ts";
import type { RequestStore } from "./storage/request-store.ts";

export const DISPLAY_COMMAND_TOOL = "vault_display_command";

/** What the server tells an agent when it connects; it names only the tools it serves. */
function instructionsFor(demoTools: boolean): string {
  return [
    "seeker-vault puts an agent's requests in front of the owner on their Seeker phone.",
    "Every request waits for the owner to approve it by hand; vault_get_capabilities says what this sidecar actually serves.",
    "vault_display_command is a live diagnostic: it shows text on the open live-test screen and waits for the owner's OK.",
    "vault_get_address reads the wallet the owner connected, and vault_sign_message asks that wallet to sign a message, returning at once with a request_id.",
    ...(demoTools
      ? [
          "vault_request_ack, a development and demo tool, queues text for the owner to acknowledge later, and returns at once with a request_id.",
        ]
      : []),
    "Read a request's outcome later with vault_get_request, and withdraw a pending one with vault_cancel_request.",
    "The sidecar holds no keys and signs nothing itself: the owner's own wallet signs, and nothing here sends a transaction.",
  ].join(" ");
}

const TOOL_DESCRIPTION =
  "Shows display-only text on the owner's Seeker (its live-test screen must be open) and " +
  'waits until they tap OK, then returns {"id", "result": "OK"}. The text is never executed. ' +
  "Errors start with a code: OFFLINE (no phone connected), BUSY (another command is " +
  "waiting), INVALID_TEXT (empty or too long), TIMEOUT (nobody tapped OK in time), or " +
  "CANCELLED (the phone disconnected or the call was cancelled).";

// Hostnames that pass the Host and Origin checks (a defense against DNS rebinding). A deployment
// that reaches /mcp through a VPN address adds it with MCP_ALLOWED_HOSTS, not here.
const LOOPBACK_HOSTNAMES = ["127.0.0.1", "localhost", "[::1]"];

// The largest JSON-RPC body /mcp reads. Every tool's arguments fit in a small fraction of it.
const MAX_BODY_BYTES = 64 * 1024;

export interface McpEndpoint {
  handle(req: IncomingMessage, res: ServerResponse): Promise<void>;
  close(): Promise<void>;
}

export interface McpEndpointOptions {
  /** Host names besides loopback that pass the Host and Origin checks (MCP_ALLOWED_HOSTS). */
  readonly allowedHosts?: readonly string[];
  /** Serves the demo tool vault_request_ack (MCP_DEMO_TOOLS). */
  readonly demoTools?: boolean;
}

export function createMcpEndpoint(
  bridge: LiveCommandBridge,
  requests: RequestStore,
  mcpToken: string,
  log: (message: string) => void,
  options: McpEndpointOptions = {},
): McpEndpoint {
  const demoTools = options.demoTools === true;
  const hostnames: ReadonlySet<string> = new Set([
    ...LOOPBACK_HOSTNAMES,
    ...(options.allowedHosts ?? []),
  ]);
  const sessions = new Map<string, StreamableHTTPServerTransport>();
  // Aborts when the connection carrying a tool call closes before its response is sent.
  const connectionClosed = new AsyncLocalStorage<AbortSignal>();

  function createServer(): McpServer {
    const server = new McpServer(
      { name: "seeker-vault", version: "0.1.0" },
      { instructions: instructionsFor(demoTools) },
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
    registerRequestTools(server, requests, log, { demoTools });
    return server;
  }

  async function handle(
    req: IncomingMessage,
    res: ServerResponse,
  ): Promise<void> {
    const rejection = rejectionFor(req, mcpToken, hostnames);
    if (rejection !== undefined) {
      log(`rejected ${req.method ?? "?"} /mcp: ${rejection.reason}`);
      const headers: Record<string, string> =
        rejection.status === 401
          ? { "WWW-Authenticate": 'Bearer realm="seeker-vault"' }
          : {};
      sendError(res, rejection.status, rejection.reason, headers);
      return;
    }

    let body: unknown;
    if (req.method === "POST") {
      const read = await readJsonBody(req);
      if (!read.ok) {
        log(`rejected POST /mcp: ${read.message}`);
        sendError(res, read.status, read.message, read.headers, read.code);
        return;
      }
      body = read.value;
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
      active.handleRequest(req, res, body),
    );
  }

  async function close(): Promise<void> {
    const open = [...sessions.values()];
    sessions.clear();
    await Promise.all(open.map((transport) => transport.close()));
  }

  return { handle, close };
}

type JsonBody =
  | { readonly ok: true; readonly value: unknown }
  | {
      readonly ok: false;
      readonly status: 400 | 413;
      readonly code: number;
      readonly message: string;
      readonly headers: Record<string, string>;
    };

/**
 * Reads a POST body of at most MAX_BODY_BYTES, and parses it as JSON. A larger declared length
 * is refused before reading, and the connection closes after the answer. A larger chunked body is
 * drained without being kept.
 */
function readJsonBody(req: IncomingMessage): Promise<JsonBody> {
  const tooLarge: JsonBody = {
    ok: false,
    status: 413,
    code: -32000,
    message: `the request body is over ${MAX_BODY_BYTES} bytes`,
    headers: { Connection: "close" },
  };
  if (Number(req.headers["content-length"]) > MAX_BODY_BYTES) {
    return Promise.resolve(tooLarge);
  }
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size <= MAX_BODY_BYTES) chunks.push(chunk);
    });
    req.once("error", reject);
    req.once("end", () => {
      if (size > MAX_BODY_BYTES) {
        resolve(tooLarge);
        return;
      }
      try {
        resolve({
          ok: true,
          value: JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown,
        });
      } catch {
        resolve({
          ok: false,
          status: 400,
          code: -32700,
          message: "Parse error: Invalid JSON",
          headers: {},
        });
      }
    });
  });
}

function rejectionFor(
  req: IncomingMessage,
  mcpToken: string,
  hostnames: ReadonlySet<string>,
): { readonly status: 401 | 403; readonly reason: string } | undefined {
  if (!isAllowed(hostnameOf(`http://${req.headers.host ?? ""}`), hostnames)) {
    return {
      status: 403,
      reason:
        "the Host header is not a loopback address or an MCP_ALLOWED_HOSTS entry",
    };
  }
  const origin = req.headers.origin;
  if (origin !== undefined && !isAllowed(hostnameOf(origin), hostnames)) {
    return {
      status: 403,
      reason:
        "the Origin header is not a loopback origin or an MCP_ALLOWED_HOSTS entry",
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

function isAllowed(
  hostname: string | undefined,
  hostnames: ReadonlySet<string>,
): boolean {
  return hostname !== undefined && hostnames.has(hostname);
}

function sendError(
  res: ServerResponse,
  status: number,
  message: string,
  headers: Record<string, string> = {},
  code = -32000,
): void {
  res.writeHead(status, { "Content-Type": "application/json", ...headers });
  res.end(
    JSON.stringify({
      jsonrpc: "2.0",
      error: { code, message },
      id: null,
    }),
  );
}
