/**
 * The `/mcp` endpoint: sessionful MCP Streamable HTTP, and the two guards in front of it.
 *
 * This server has no OAuth of its own. It takes one bearer token, and it will only answer requests
 * whose `Host` and `Origin` are loopback or explicitly allowed — a browser on the owner's machine
 * must not be able to reach a local server by name and drive it (DNS rebinding), and a bearer token
 * is no defence against that because the browser would not need to know it.
 */
import { randomUUID, timingSafeEqual } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Http2ServerRequest } from "node:http2";
import { writeJson, type AnyRequest, type AnyResponse } from "./http.ts";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import {
  pairingLinkInstruction,
  type AgentRequests,
  type IssuedPairing,
} from "@seeker-vault/server-sdk";
import type { SkrStakingProvider } from "./skr/provider.ts";
import { registerStakingTools } from "./requests/tools.ts";
import {
  CREATE_PAIRING_LINK_TOOL,
  registerPairingLinkTool,
} from "./pairing/mcp-tool.ts";

/** A whole JSON-RPC body may be this large; a bigger one is refused rather than buffered. */
const MAX_BODY_BYTES = 64 * 1024;

const LOOPBACK_HOSTNAMES = ["127.0.0.1", "localhost", "[::1]"];

export interface McpEndpointOptions {
  readonly core: AgentRequests;
  readonly provider: SkrStakingProvider;
  /** Issues the one-use pairing code the owner's phone still has to confirm. */
  readonly issuePairing: () => IssuedPairing;
  readonly mcpToken: string;
  readonly allowedHosts: readonly string[];
  readonly version: string;
  readonly log: (message: string) => void;
}

export interface McpEndpoint {
  handle(request: AnyRequest, response: AnyResponse): Promise<void>;
  close(): Promise<void>;
}

/** The instructions an agent is handed when it connects. */
function instructions(): string {
  return [
    "This server acts on one owner's SKR staking position on Solana mainnet, through their own",
    "wallet, and only with their approval.",
    "",
    "get_staking_status reads the chain and answers at once. The four request_ tools each create an",
    "approval request and stop: nothing is signed or sent when you call them, and the owner decides",
    "on their phone. A tool returning PENDING means the question was asked, not answered.",
    "",
    "To follow a request, call the same tool again with the same idempotency_key and the same",
    "parameters: you get the original request as it stands now. Poll until terminal is true. An",
    "UNKNOWN request is not finished and must never be replaced with a new one.",
    "",
    "Unstaking is not withdrawing. request_unstake starts a cooldown and moves no tokens;",
    "request_withdraw moves the tokens once that cooldown has finished. Amounts are SKR base units",
    "as decimal strings, and SKR has 6 decimals.",
    "",
    pairingLinkInstruction(CREATE_PAIRING_LINK_TOOL),
    "It pairs this server, which is the owner's own connection for staking and is not the general",
    "Seeker Agent Connect MCP server: a phone paired there cannot answer these requests.",
  ].join("\n");
}

export function mcpEndpoint(options: McpEndpointOptions): McpEndpoint {
  const { core, provider, log } = options;
  const sessions = new Map<string, StreamableHTTPServerTransport>();
  const hostnames = new Set([
    ...LOOPBACK_HOSTNAMES,
    ...options.allowedHosts.map((host) => host.toLowerCase()),
  ]);

  function createServer(): McpServer {
    const server = new McpServer(
      { name: "seeker-skr-staking", version: options.version },
      { instructions: instructions() },
    );
    registerStakingTools(server, core, provider, log);
    registerPairingLinkTool(server, options.issuePairing, log);
    return server;
  }

  return {
    async handle(request, response): Promise<void> {
      const rejection = rejectionFor(request, hostnames, options.mcpToken);
      if (rejection !== undefined) {
        log(`rejected ${request.method ?? "?"} /mcp: ${rejection.reason}`);
        send(response, rejection.status, rejection.reason, rejection.headers);
        return;
      }

      let body: unknown;
      if (request.method === "POST") {
        const read = await readJsonBody(request);
        if (!read.ok) {
          log(`rejected POST /mcp: ${read.message}`);
          send(response, read.status, read.message);
          return;
        }
        body = read.value;
      }

      const sessionId = request.headers["mcp-session-id"];
      let transport =
        typeof sessionId === "string" ? sessions.get(sessionId) : undefined;
      if (typeof sessionId === "string" && transport === undefined) {
        send(response, 404, "unknown MCP session; initialize a new one");
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
          ) {
            log("MCP session closed");
          }
        };
        await createServer().connect(created);
        transport = created;
      }
      await transport.handleRequest(
        request as IncomingMessage,
        response as ServerResponse,
        body,
      );
    },

    async close(): Promise<void> {
      const open = [...sessions.values()];
      sessions.clear();
      await Promise.allSettled(open.map((transport) => transport.close()));
    },
  };
}

interface Rejection {
  readonly status: number;
  readonly reason: string;
  readonly headers?: Readonly<Record<string, string>>;
}

function rejectionFor(
  request: IncomingMessage | Http2ServerRequest,
  hostnames: ReadonlySet<string>,
  mcpToken: string,
): Rejection | undefined {
  const host = requestHost(request);
  if (host === undefined || !hostnames.has(hostnameOf(host))) {
    return {
      status: 403,
      reason: "this endpoint answers only the hosts it was configured for",
    };
  }
  const origin = request.headers.origin;
  if (typeof origin === "string" && origin !== "") {
    let parsed: URL;
    try {
      parsed = new URL(origin);
    } catch {
      return { status: 403, reason: "the Origin header is not a URL" };
    }
    if (!hostnames.has(hostnameOf(parsed.host))) {
      return { status: 403, reason: "that Origin is not allowed here" };
    }
  }
  if (!bearerMatches(request.headers.authorization, mcpToken)) {
    return {
      status: 401,
      reason: "a bearer token is required",
      headers: { "WWW-Authenticate": 'Bearer realm="seeker-skr-staking"' },
    };
  }
  return undefined;
}

/** HTTP/1 sends `host`; HTTP/2 sends `:authority`. */
function requestHost(
  request: IncomingMessage | Http2ServerRequest,
): string | undefined {
  const headers = request.headers as Record<string, string | undefined>;
  return headers.host ?? headers[":authority"];
}

/** The host name without its port, keeping a bracketed IPv6 literal intact. */
function hostnameOf(host: string): string {
  const lower = host.toLowerCase();
  if (lower.startsWith("[")) {
    const end = lower.indexOf("]");
    return end === -1 ? lower : lower.slice(0, end + 1);
  }
  const colon = lower.lastIndexOf(":");
  return colon === -1 ? lower : lower.slice(0, colon);
}

/** Constant-time, so a wrong token cannot be found one character at a time. */
function bearerMatches(
  authorization: string | undefined,
  expected: string,
): boolean {
  if (authorization === undefined) return false;
  const match = /^Bearer (.+)$/.exec(authorization.trim());
  if (match === null) return false;
  const given = Buffer.from(match[1] ?? "", "utf8");
  const wanted = Buffer.from(expected, "utf8");
  if (given.length !== wanted.length) return false;
  return timingSafeEqual(given, wanted);
}

type ReadResult =
  | { readonly ok: true; readonly value: unknown }
  | { readonly ok: false; readonly status: number; readonly message: string };

async function readJsonBody(
  request: IncomingMessage | Http2ServerRequest,
): Promise<ReadResult> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk)
      ? chunk
      : Buffer.from(chunk as string);
    size += buffer.length;
    if (size > MAX_BODY_BYTES) {
      return {
        ok: false,
        status: 413,
        message: `the body is larger than ${MAX_BODY_BYTES} bytes`,
      };
    }
    chunks.push(buffer);
  }
  if (size === 0) return { ok: true, value: undefined };
  try {
    return {
      ok: true,
      value: JSON.parse(Buffer.concat(chunks).toString("utf8")),
    };
  } catch {
    return { ok: false, status: 400, message: "the body is not valid JSON" };
  }
}

function send(
  response: AnyResponse,
  status: number,
  message: string,
  headers: Readonly<Record<string, string>> = {},
): void {
  writeJson(
    response,
    status,
    { jsonrpc: "2.0", error: { code: -32000, message }, id: null },
    headers,
  );
}
