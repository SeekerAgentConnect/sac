/**
 * The sidecar: GET /healthz, the MCP endpoint at /mcp, and the phone's Connect API, on one
 * loopback HTTP server. The Stage 1 live diagnostic stays in memory; durable requests and pairing
 * live in the SQLite database at DATABASE_PATH.
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
import { pairingRoutes } from "./pairing/service.ts";
import { phoneRoutes } from "./phone-api.ts";
import { requestRoutes } from "./requests/phone-service.ts";
import {
  openDatabase,
  schemaVersion,
  type DatabaseSync,
} from "./storage/database.ts";
import { PairingStore } from "./storage/pairing-store.ts";
import { RequestStore } from "./storage/request-store.ts";

export interface Sidecar {
  /** Base URL, for example http://127.0.0.1:8080. */
  readonly url: string;
  /** The sidecar's lasting ID, which pairing codes and PairResponse carry. */
  readonly serverId: string;
  /**
   * Cancels the in-flight live command, ends every stream, stops listening, and closes the
   * database. Durable requests stay as they are.
   */
  close(): Promise<void>;
}

export interface SidecarOptions {
  /** Receives one line per event; lines never contain tokens, command text, or notes. */
  readonly log?: (message: string) => void;
  /** The clock for requests and pairing codes, in epoch milliseconds. Tests replace it. */
  readonly now?: () => number;
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
  const db = openDatabase(config.databasePath);
  try {
    return await serve(config, db, log, options.now);
  } catch (error) {
    db.close();
    throw error;
  }
}

async function serve(
  config: SidecarConfig,
  db: DatabaseSync,
  log: (message: string) => void,
  now: (() => number) | undefined,
): Promise<Sidecar> {
  // Nothing is executed at startup: stored requests wait for the phone and the agent.
  const requests = new RequestStore(db, {
    defaultTtlSeconds: config.requestTtlSeconds,
    pendingLimit: config.pendingLimit,
    now,
  });
  const pairing = new PairingStore(db, { now });
  const serverId = pairing.serverId();
  const phone = pairing.activeConnection();
  log(
    `requests are stored in ${config.databasePath} (schema version ${schemaVersion(db)}); server ${serverId}; ` +
      (phone === undefined
        ? "no phone is paired: run pnpm pair"
        : `paired phone: connection ${phone.connectionId}`),
  );
  log(
    config.demoTools === true
      ? "the demo tool vault_request_ack is on (MCP_DEMO_TOOLS=true)"
      : "the demo tool vault_request_ack is off; MCP_DEMO_TOOLS=true serves it",
  );

  const bridge = new LiveCommandBridge({
    timeoutSeconds: config.liveCommandTimeoutSeconds,
    log,
  });
  const mcp = createMcpEndpoint(bridge, requests, config.mcpToken, log, {
    allowedHosts: config.mcpAllowedHosts,
    demoTools: config.demoTools,
  });
  const phoneApi = connectNodeAdapter({
    routes: (router) => {
      // The Stage 1 diagnostic keeps its development token; the durable API needs a paired phone.
      phoneRoutes(bridge, config.phoneToken, log)(router);
      requestRoutes(requests, pairing, log)(router);
      pairingRoutes(pairing, log)(router);
    },
    readMaxBytes: PHONE_API_MAX_MESSAGE_BYTES,
  });

  const server = createServer((req, res) => {
    let path: string;
    try {
      path = new URL(req.url ?? "/", "http://sidecar").pathname;
    } catch {
      // A request target that isn't a path, such as "//[", must not stop the sidecar.
      res.writeHead(400).end();
      return;
    }
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
      phoneApi(req, res);
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
    serverId,
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
        db.close();
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
