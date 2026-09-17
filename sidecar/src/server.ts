/**
 * The sidecar: GET /healthz, the phone's Connect API, and — when the MCP adapter is configured —
 * the agent endpoint at /mcp, all on one listener. A configured TLS listener also carries
 * production gRPC/HTTP2 updates; loopback development may put those updates on a separate h2c
 * port. Durable requests and pairing live in SQLite.
 *
 * MCP is optional (SEE-87, docs/wiki/mcp-adapter.md). Everything below the adapter — the request
 * store and its lifecycle, pairing, the phone API, updates, push, and both listeners — is built
 * and closed the same way whether or not MCP_ENABLED is on, and with it off no endpoint is
 * constructed and /mcp is not served.
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
import { createMcpEndpoint, type McpEndpoint } from "./mcp-endpoint.ts";
import {
  PROTECTED_RESOURCE_PATHS,
  protectedResourceMetadata,
  type OAuthConfig,
} from "./oauth.ts";
import { pairingRoutes } from "./pairing/service.ts";
import { phoneRoutes } from "./phone-api.ts";
import { createFcmSender, type FcmSender } from "./push/fcm.ts";
import { FcmInvalidationDispatcher } from "./push/invalidation.ts";
import { agentRequests, type AgentRequests } from "./requests/agent-api.ts";
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
import { observeCommittedRequestUpdates } from "./storage/update-store.ts";
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
  /** Replaces Firebase Admin construction in tests; never called when FCM_PROJECT_ID is absent. */
  readonly fcmSenderFactory?: (projectId: string) => FcmSender;
}

// How long close() lets in-flight responses, such as a CANCELLED tool result, finish.
const CLOSE_GRACE_MS = 1000;
// Keep every phone request bounded without imposing the same cap on existing response contracts.
const PHONE_API_MAX_REQUEST_BYTES = 64 * 1024;

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
  let fcmSender: FcmSender | undefined;
  try {
    fcmSender =
      config.fcmProjectId === undefined
        ? undefined
        : (options.fcmSenderFactory ?? createFcmSender)(config.fcmProjectId);
    return await serve(
      config,
      db,
      log,
      options.now,
      options.updatePollMs,
      fcmSender,
    );
  } catch (error) {
    await fcmSender?.close().catch(() => undefined);
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
  fcmSender: FcmSender | undefined,
): Promise<Sidecar> {
  // Nothing is executed at startup: stored requests wait for the phone and the agent.
  const requests = new RequestStore(db, {
    defaultTtlSeconds: config.requestTtlSeconds,
    pendingLimit: config.pendingLimit,
    now,
  });
  const pairing = new PairingStore(db, { now });
  const invalidations =
    fcmSender === undefined
      ? undefined
      : new FcmInvalidationDispatcher(pairing, fcmSender, log);
  const stopInvalidations =
    invalidations === undefined
      ? undefined
      : observeCommittedRequestUpdates(db, (update) =>
          invalidations.invalidate(update),
        );
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
    fcmSender === undefined
      ? "FCM sender is off; FCM_PROJECT_ID is not configured"
      : "FCM sender is configured through Application Default Credentials",
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
  // The one place an adapter reaches the request core (SEE-87). It forwards and decides nothing:
  // idempotency, validation, the lifecycle and the pending limit stay in the store.
  const core = agentRequests(requests, { preparer, tracker });
  const mcp = mcpAdapter(config, bridge, core, log);
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
      readMaxBytes: PHONE_API_MAX_REQUEST_BYTES,
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
    } else if (PROTECTED_RESOURCE_PATHS.includes(path)) {
      protectedResource(req, res, config.oauth);
    } else if (path === "/mcp") {
      // Not served rather than refused: a deployment without the adapter has no such endpoint,
      // and saying 401 would suggest a credential would open one.
      if (mcp === undefined) {
        response.writeHead(404, { "Content-Type": "application/json" });
        response.end(JSON.stringify({ error: "not_found" }));
        return;
      }
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
    `listening on ${url}: ${mcp === undefined ? "no MCP endpoint (MCP_ENABLED=false)" : `MCP at ${url}/mcp`}, phone API at ${url}/${LiveCommandService.typeName}`,
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
        stopInvalidations?.();
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
        await mcp?.close();
        closeAll(server);
        if (updateServer !== undefined) closeAll(updateServer);
        for (const session of updateSessions) session.destroy();
        await Promise.all([stopped, updatesStopped]);
        await invalidations?.close();
        await fcmSender?.close().catch(() => undefined);
        db.close();
        log("stopped");
      })();
      return closing;
    },
  };
}

/**
 * Builds the agent-facing MCP adapter, or nothing at all when the deployment doesn't serve it
 * (SEE-87, docs/wiki/mcp-adapter.md).
 *
 * Adapter construction lives here so generic startup does not have to know what an adapter is:
 * `serve` builds the core, calls this once, and from then on treats the result as an optional
 * route. Every MCP-only setting is read in this function and nowhere else.
 */
function mcpAdapter(
  config: SidecarConfig,
  bridge: LiveCommandBridge,
  core: AgentRequests,
  log: (message: string) => void,
): McpEndpoint | undefined {
  if (config.mcpToken === undefined) {
    log(
      "the MCP adapter is off (MCP_ENABLED=false): /mcp is not served, and no MCP token is needed" +
        (config.ignoredSettings === undefined
          ? ""
          : `; ignoring ${config.ignoredSettings.join(", ")}`),
    );
    return undefined;
  }
  const endpoint = createMcpEndpoint(bridge, core, config.mcpToken, log, {
    allowedHosts: config.mcpAllowedHosts,
    demoTools: config.demoTools,
    ...(config.oauth === undefined ? {} : { oauth: config.oauth }),
  });
  log(
    config.demoTools === true
      ? "the demo tool vault_request_ack is on (MCP_DEMO_TOOLS=true)"
      : "the demo tool vault_request_ack is off; MCP_DEMO_TOOLS=true serves it",
  );
  log(
    config.oauth === undefined
      ? "MCP OAuth is off; /mcp takes MCP_TOKEN (docs/integrations/claude.md configures a hosted client)"
      : `MCP OAuth is on: /mcp takes an access token issued by ${config.oauth.issuer} for ${config.oauth.resource}`,
  );
  return endpoint;
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

/**
 * RFC 9728's protected-resource metadata: the public document that tells a hosted MCP client which
 * authorization server to send its user to (SAW-036). It is deliberately readable by anyone — it
 * names no request, no owner, and no credential — and it exists only while OAuth is configured, so
 * a deployment that uses MCP_TOKEN advertises no authorization it doesn't have.
 */
function protectedResource(
  req: MainRequest,
  res: MainResponse,
  oauth: OAuthConfig | undefined,
): void {
  const response = res as ServerResponse;
  if (oauth === undefined) {
    response.writeHead(404, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ error: "not_found" }));
    return;
  }
  if (req.method !== "GET" && req.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  response.writeHead(200, {
    "Content-Type": "application/json",
    // A discovery document a client may fetch from a browser context, and cache for an hour.
    "Access-Control-Allow-Origin": "*",
    "Cache-Control": "public, max-age=3600",
  });
  if (req.method === "GET")
    response.end(JSON.stringify(protectedResourceMetadata(oauth)));
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
