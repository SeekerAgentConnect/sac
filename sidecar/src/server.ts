/**
 * The Stage 1 sidecar: GET /healthz, the MCP endpoint at /mcp, and the phone's Connect
 * API, on one loopback HTTP server. All state is in memory.
 */
import { once } from "node:events";
import {
  createServer,
  type IncomingMessage,
  type ServerResponse,
} from "node:http";
import type { AddressInfo } from "node:net";
import { setTimeout as delay } from "node:timers/promises";

import { connectNodeAdapter } from "@connectrpc/connect-node";

import type { SidecarConfig } from "./config.ts";
import { LiveCommandService } from "./gen/seekervault/live/v1/live_pb.js";
import { LiveCommandBridge } from "./live/bridge.ts";
import { createMcpEndpoint } from "./mcp-endpoint.ts";
import { phoneRoutes } from "./phone-api.ts";

export interface Sidecar {
  /** Base URL, for example http://127.0.0.1:8080. */
  readonly url: string;
  /** Cancels the in-flight command, ends every stream, and stops listening. */
  close(): Promise<void>;
}

export interface SidecarOptions {
  /** Receives one line per event; lines never contain tokens or command text. */
  readonly log?: (message: string) => void;
}

// How long close() lets in-flight responses, such as a CANCELLED tool result, finish.
const CLOSE_GRACE_MS = 1000;
const PHONE_API_MAX_MESSAGE_BYTES = 64 * 1024;

/** Starts listening on `config.host:config.port`; port 0 picks a free port. */
export async function startSidecar(
  config: SidecarConfig,
  options: SidecarOptions = {},
): Promise<Sidecar> {
  const log =
    options.log ??
    ((message: string) => {
      console.log(`[sidecar] ${message}`);
    });
  const bridge = new LiveCommandBridge({
    timeoutSeconds: config.liveCommandTimeoutSeconds,
    log,
  });
  const mcp = createMcpEndpoint(bridge, config.mcpToken, log);
  const phone = connectNodeAdapter({
    routes: phoneRoutes(bridge, config.phoneToken, log),
    readMaxBytes: PHONE_API_MAX_MESSAGE_BYTES,
  });

  const server = createServer((req, res) => {
    const path = new URL(req.url ?? "/", "http://sidecar").pathname;
    if (path === "/healthz") {
      health(req, res);
    } else if (path === "/mcp") {
      mcp.handle(req, res).catch((error: unknown) => {
        log(
          `MCP request failed: ${error instanceof Error ? error.message : String(error)}`,
        );
        if (res.headersSent) res.destroy();
        else res.writeHead(500).end();
      });
    } else {
      phone(req, res);
    }
  });
  server.listen(config.port, config.host);
  await once(server, "listening");

  const { port } = server.address() as AddressInfo;
  const url = `http://${config.host.includes(":") ? `[${config.host}]` : config.host}:${port}`;
  log(
    `listening on ${url}: MCP at ${url}/mcp, phone API at ${url}/${LiveCommandService.typeName}`,
  );

  let closing: Promise<void> | undefined;
  return {
    url,
    close() {
      closing ??= (async () => {
        bridge.shutdown();
        const stopped = new Promise<void>((resolve) =>
          server.close(() => resolve()),
        );
        server.closeIdleConnections();
        await Promise.race([
          stopped,
          delay(CLOSE_GRACE_MS, undefined, { ref: false }),
        ]);
        await mcp.close();
        server.closeAllConnections();
        await stopped;
        log("stopped");
      })();
      return closing;
    },
  };
}

function health(req: IncomingMessage, res: ServerResponse): void {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  res.writeHead(200, { "Content-Type": "application/json" });
  res.end(req.method === "GET" ? JSON.stringify({ status: "ok" }) : undefined);
}
