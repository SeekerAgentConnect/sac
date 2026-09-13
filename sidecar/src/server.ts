/**
 * The sidecar: GET /healthz, the MCP endpoint at /mcp, and the phone's Connect API on one listener.
 * A configured TLS listener also carries production gRPC/HTTP2 updates; loopback development may
 * put those updates on a separate h2c port. Durable requests and pairing live in SQLite.
 */
import { randomUUID } from "node:crypto";
import { once } from "node:events";
import {
  createServer,
  type IncomingMessage,
  type Server as HttpServer,
  type ServerResponse,
} from "node:http";
import {
  createSecureServer,
  createServer as createHttp2Server,
  type Http2Server,
  type Http2ServerRequest,
  type Http2ServerResponse,
  type Http2SecureServer,
  type ServerHttp2Session,
} from "node:http2";
import type { AddressInfo } from "node:net";
import { setTimeout as delay } from "node:timers/promises";

import { create } from "@bufbuild/protobuf";
import { connectNodeAdapter } from "@connectrpc/connect-node";

import { DEFAULT_SOLANA_RPC_TIMEOUT_MS, type SidecarConfig } from "./config.ts";
import { LiveCommandService } from "./gen/seekervault/live/v1/live_pb.js";
import { UpdateCapabilitySchema } from "./gen/seekervault/request/v1/service_pb.js";
import { LiveCommandBridge } from "./live/bridge.ts";
import { createMcpEndpoint } from "./mcp-endpoint.ts";
import { pairingRoutes } from "./pairing/service.ts";
import { phoneRoutes } from "./phone-api.ts";
import { ConfirmationTracker } from "./requests/confirmation.ts";
import { requestRoutes } from "./requests/phone-service.ts";
import { TransactionPreparer } from "./requests/preparation.ts";
import { SolanaRpc } from "./solana/rpc.ts";
import {
  openDatabase,
  schemaVersion,
  type DatabaseSync,
} from "./storage/database.ts";
import { PairingStore } from "./storage/pairing-store.ts";
import { RequestStore } from "./storage/request-store.ts";
import { readTlsIdentity } from "./storage/tls.ts";
import { UpdateStore } from "./storage/update-store.ts";
import {
  UPDATE_PROTOCOL_VERSION,
  UpdateCoordinator,
  updateRoutes,
} from "./updates/service.ts";

export interface Sidecar {
  /** Base URL, for example http://127.0.0.1:8080. */
  readonly url: string;
  /** The sidecar's lasting ID, which pairing codes and PairResponse carry. */
  readonly serverId: string;
  /** Configured gRPC/HTTP2 origin, absent when production updates are not served. */
  readonly updateUrl?: string;
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
  /** Shorter idle housekeeping interval for tests; committed updates wake streams immediately. */
  readonly updatePollMs?: number;
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
    return await serve(config, db, log, options.now, options.updatePollMs);
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
  updatePollMs: number | undefined,
): Promise<Sidecar> {
  // Nothing is executed at startup: stored requests wait for the phone and the agent.
  const requests = new RequestStore(db, {
    defaultTtlSeconds: config.requestTtlSeconds,
    pendingLimit: config.pendingLimit,
    now,
  });
  const pairing = new PairingStore(db, { now });
  const serverId = pairing.serverId();
  const serverInstanceId = randomUUID();
  const updates = new UpdateStore(db, { serverInstanceId, now });
  const updateCoordinator = new UpdateCoordinator();
  const updatesConfigured =
    config.updatePort !== undefined ||
    (config.tlsCertificatePath !== undefined &&
      config.tlsPrivateKeyPath !== undefined);
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

  // Without an endpoint there is no vault_transfer and no preparation: the sidecar offers what it
  // can actually do. The URL may carry an API key, so only its presence is logged.
  const timeoutMs = config.solanaRpcTimeoutMs ?? DEFAULT_SOLANA_RPC_TIMEOUT_MS;
  const endpoint = config.solanaRpcUrl;
  const chain =
    endpoint === undefined ? undefined : new SolanaRpc(endpoint, { timeoutMs });
  const preparer =
    chain === undefined
      ? undefined
      : new TransactionPreparer(requests, chain, now);
  // The same endpoint says what became of a sent transaction (SAW-022). Nothing checks on its
  // own: a check runs when the agent reads the request or the owner asks the phone.
  const tracker =
    chain === undefined || endpoint === undefined
      ? undefined
      : new ConfirmationTracker(requests, chain, endpoint, { now });
  log(
    preparer === undefined
      ? "no Solana RPC endpoint is configured (SOLANA_RPC_URL), so transfers aren't served and nothing can be confirmed on chain"
      : `transfers are served against the configured Solana RPC endpoint (timeout ${timeoutMs} ms); a sent transaction is confirmed against ${tracker?.endpoint ?? ""}`,
  );

  const bridge = new LiveCommandBridge({
    timeoutSeconds: config.liveCommandTimeoutSeconds,
    log,
  });
  const mcp = createMcpEndpoint(bridge, requests, config.mcpToken, log, {
    allowedHosts: config.mcpAllowedHosts,
    demoTools: config.demoTools,
    preparer,
    tracker,
  });
  let updateUrl: string | undefined;
  const routes = (includeUpdates: boolean) =>
    connectNodeAdapter({
      routes: (router) => {
        // The Stage 1 diagnostic keeps its development token; the durable API needs a paired phone.
        phoneRoutes(bridge, config.phoneToken, log)(router);
        requestRoutes(requests, pairing, log, preparer, tracker)(router);
        pairingRoutes(pairing, log, () =>
          updateUrl === undefined
            ? undefined
            : create(UpdateCapabilitySchema, {
                protocolVersion: UPDATE_PROTOCOL_VERSION,
                grpcUrl: updateUrl,
              }),
        )(router);
        if (includeUpdates) {
          updateRoutes(
            pairing,
            requests,
            updates,
            serverInstanceId,
            updateCoordinator,
            log,
            { tracker, pollMs: updatePollMs },
          )(router);
        }
      },
      readMaxBytes: PHONE_API_MAX_MESSAGE_BYTES,
      writeMaxBytes: PHONE_API_MAX_MESSAGE_BYTES,
    });
  const phoneApi = routes(updatesConfigured && config.updatePort === undefined);

  const handler = (req: MainRequest, res: MainResponse) => {
    // HTTP/2's compatibility response implements the HTTP/1 methods used by these shared routes.
    const response = res as ServerResponse;
    let path: string;
    try {
      path = new URL(req.url ?? "/", "http://sidecar").pathname;
    } catch {
      // A request target that isn't a path, such as "//[", must not stop the sidecar.
      response.writeHead(400).end();
      return;
    }
    if (path === "/healthz") {
      health(req, res);
    } else if (path === "/mcp") {
      mcp
        .handle(req as IncomingMessage, res as ServerResponse)
        .catch((error: unknown) => {
          log(
            `MCP request failed: ${error instanceof Error ? error.message : String(error)}`,
          );
          if (response.headersSent) response.destroy();
          else response.writeHead(500).end();
        });
    } else {
      phoneApi(req, res);
    }
  };
  const secure =
    config.tlsCertificatePath !== undefined &&
    config.tlsPrivateKeyPath !== undefined;
  const server: HttpServer | Http2SecureServer = secure
    ? createSecureServer(
        {
          ...readTlsIdentity(
            config.tlsCertificatePath ?? "",
            config.tlsPrivateKeyPath ?? "",
          ),
          allowHTTP1: true,
        },
        handler,
      )
    : createServer(handler);
  const updateSessions = new Set<ServerHttp2Session>();
  if (secure) trackSessions(server as Http2SecureServer, updateSessions);
  server.listen(config.port, config.host);
  await once(server, "listening");

  const { port } = server.address() as AddressInfo;
  const url = `${secure ? "https" : "http"}://${config.host.includes(":") ? `[${config.host}]` : config.host}:${port}`;
  let updateServer: Http2Server | undefined;
  if (config.updatePort !== undefined) {
    const updateApi = routes(true);
    updateServer = createHttp2Server(updateApi);
    trackSessions(updateServer, updateSessions);
    updateServer.listen(config.updatePort, config.host);
    try {
      await once(updateServer, "listening");
    } catch (error) {
      server.close();
      throw error;
    }
    const updateAddress = updateServer.address() as AddressInfo;
    updateUrl = `http://${config.host.includes(":") ? `[${config.host}]` : config.host}:${updateAddress.port}`;
  } else if (secure) {
    updateUrl = new URL(config.publicUrl ?? url).origin;
  }
  log(
    `listening on ${url}: MCP at ${url}/mcp, phone API at ${url}/${LiveCommandService.typeName}`,
  );
  log(
    updateUrl === undefined
      ? "production updates are not configured"
      : `production updates are served as gRPC over HTTP/2 at ${updateUrl}`,
  );

  let closing: Promise<void> | undefined;
  return {
    url,
    serverId,
    updateUrl,
    close() {
      closing ??= (async () => {
        bridge.shutdown();
        updateCoordinator.shutdown();
        const stopped = stopServer(server);
        const updatesStopped =
          updateServer === undefined
            ? Promise.resolve()
            : stopServer(updateServer);
        closeIdle(server);
        if (updateServer !== undefined) closeIdle(updateServer);
        await Promise.race([
          Promise.all([stopped, updatesStopped]),
          delay(CLOSE_GRACE_MS, undefined, { ref: false }),
        ]);
        await mcp.close();
        closeAll(server);
        if (updateServer !== undefined) closeAll(updateServer);
        for (const session of updateSessions) session.destroy();
        await Promise.all([stopped, updatesStopped]);
        db.close();
        log("stopped");
      })();
      return closing;
    },
  };
}

type MainRequest = IncomingMessage | Http2ServerRequest;
type MainResponse = ServerResponse | Http2ServerResponse;
type ListenableServer = HttpServer | Http2Server | Http2SecureServer;

function health(req: MainRequest, res: MainResponse): void {
  const response = res as ServerResponse;
  if (req.method !== "GET" && req.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  response.writeHead(200, { "Content-Type": "application/json" });
  if (req.method === "GET") response.end(JSON.stringify({ status: "ok" }));
  else response.end();
}

function stopServer(server: ListenableServer): Promise<void> {
  return new Promise((resolve) => server.close(() => resolve()));
}

function closeIdle(server: ListenableServer): void {
  if ("closeIdleConnections" in server) server.closeIdleConnections();
}

function closeAll(server: ListenableServer): void {
  if ("closeAllConnections" in server) server.closeAllConnections();
}

function trackSessions(
  server: Http2Server | Http2SecureServer,
  sessions: Set<ServerHttp2Session>,
): void {
  server.on("session", (session) => {
    sessions.add(session);
    session.once("close", () => sessions.delete(session));
  });
}
