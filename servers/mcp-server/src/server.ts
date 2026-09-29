/**
 * The sidecar: GET /healthz, the phone's Connect API, and — when the MCP adapter is configured —
 * the agent endpoint at /mcp, all on one listener. A configured TLS listener also carries
 * production gRPC/HTTP2 updates; loopback development may put those updates on a separate h2c
 * port; a TLS-terminating HTTP/2 proxy may put h2c on the main port (SIDECAR_H2C). Durable
 * requests and pairing live in SQLite.
 *
 * MCP is optional (SEE-87, docs/wiki/mcp-adapter.md). Everything below the adapter — the request
 * store and its lifecycle, pairing, the phone API, updates, push, and both listeners — is built
 * and closed the same way whether or not MCP_ENABLED is on, and with it off no endpoint is
 * constructed and /mcp is not served.
 */
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

import {
  handlePairingLink,
  isPairingLinkPath,
  openDirectServer,
  ProviderUnavailable,
  solanaNetworkName,
  UnsupportedPreparation,
  type AgentRequests,
  type ConfirmationProvider,
  type IssuedPairing,
  type LiveCommandBridge,
  type TransferProvider,
} from "@seeker_agent_connect/server-sdk";
import { LiveCommandService } from "@seeker_agent_connect/server-sdk/protocol";

import { DEFAULT_SOLANA_RPC_TIMEOUT_MS, type SidecarConfig } from "./config.ts";
import { createMcpEndpoint, type McpEndpoint } from "./mcp-endpoint.ts";
import { PAIRING_PAGE, qrModulePath } from "./pairing/landing-page.ts";
import {
  PROTECTED_RESOURCE_PATHS,
  protectedResourceMetadata,
  type OAuthConfig,
} from "./oauth.ts";
import { createFcmSender, type FcmSender } from "./push/fcm.ts";
import { isApprovedTransaction } from "./solana/confirmation.ts";
import { SolanaRpc } from "./solana/rpc.ts";
import { ChainUnavailable } from "./solana/rpc.ts";
import {
  UnsupportedTransfer,
  assertNetwork,
  assertSupportedAsset,
  buildTransfer,
} from "./solana/transfer.ts";
import { readTlsIdentity } from "./storage/tls.ts";
import { acquireInstanceLock } from "./storage/instance-lock.ts";

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

/** Starts listening on `config.host:config.port`; port 0 picks a free port. */
export async function startSidecar(
  config: SidecarConfig,
  options: SidecarOptions = {},
): Promise<Sidecar> {
  const log =
    options.log ??
    ((message: string) => {
      console.log(`[mcp-server] ${message}`);
    });
  let fcmSender: FcmSender | undefined;
  try {
    fcmSender =
      config.fcmProjectId === undefined
        ? undefined
        : (options.fcmSenderFactory ?? createFcmSender)(config.fcmProjectId);
    return await serve(
      config,
      log,
      options.now,
      options.updatePollMs,
      fcmSender,
    );
  } catch (error) {
    await fcmSender?.close().catch(() => undefined);
    throw error;
  }
}

async function serve(
  config: SidecarConfig,
  log: (message: string) => void,
  now: (() => number) | undefined,
  updatePollMs: number | undefined,
  fcmSender: FcmSender | undefined,
): Promise<Sidecar> {
  // Without an endpoint there is no vault_transfer and no preparation: the sidecar offers what it
  // can actually do. The URL may carry an API key, so only its presence is logged.
  const timeoutMs = config.solanaRpcTimeoutMs ?? DEFAULT_SOLANA_RPC_TIMEOUT_MS;
  const endpoint = config.solanaRpcUrl;
  const chain =
    endpoint === undefined ? undefined : new SolanaRpc(endpoint, { timeoutMs });
  const transferProvider: TransferProvider | undefined =
    chain === undefined
      ? undefined
      : {
          checkAsset: (asset) =>
            providerCall(() => assertSupportedAsset(chain, asset)),
          buildTransfer: (action, at) =>
            providerCall(() => buildTransfer(chain, action, at)),
        };
  const confirmationProvider: ConfirmationProvider | undefined =
    chain === undefined || endpoint === undefined
      ? undefined
      : {
          endpointUrl: endpoint,
          assertNetwork: (network) =>
            providerCall(() => assertNetwork(chain, network)),
          signatureStatus: (signature, searchHistory) =>
            providerCall(() => chain.signatureStatus(signature, searchHistory)),
          confirmedTransaction: (signature) =>
            providerCall(() => chain.confirmedTransaction(signature)),
          blockHeight: () => providerCall(() => chain.blockHeight()),
          matchesApprovedTransaction: isApprovedTransaction,
        };
  let listeningUrl: string | undefined;
  const instanceLock = acquireInstanceLock(config.databasePath);
  let direct;
  try {
    direct = openDirectServer({
      databasePath: config.databasePath,
      publicOrigin: () => config.publicUrl ?? listeningUrl ?? "",
      // SEE-174: what SAC_SUPPORTED_NETWORKS says, and nothing when it is unset — never a default
      // the operator didn't choose.
      supportedNetworks: config.supportedNetworks ?? [],
      requestTtlSeconds: config.requestTtlSeconds,
      pendingLimit: config.pendingLimit,
      pairingTokenTtlSeconds: config.pairingTokenTtlSeconds ?? 600,
      liveCommandTimeoutSeconds: config.liveCommandTimeoutSeconds,
      log,
      now,
      updatePollMs,
      transferProvider,
      confirmationProvider,
      invalidationSender: fcmSender,
      // The other way of waking a phone (SEE-144): the gateway's operator holds the Firebase
      // credential and this server holds a scoped relay credential. Configuring both is refused
      // in config.ts, so at most one of these two lines is ever a value.
      ...(config.relay === undefined ? {} : { relay: config.relay }),
    });
  } catch (error) {
    instanceLock.release();
    throw error;
  }
  try {
    const phone = direct.pairing.active();
    log(
      `requests are stored in ${config.databasePath} (schema version ${direct.databaseSchemaVersion}); server ${direct.serverId}; ` +
        (phone === undefined
          ? "no phone is paired: run seeker-agent-connect-mcp pair"
          : `paired phone: connection ${phone.connectionId}`),
    );
    log(
      fcmSender === undefined
        ? "FCM sender is off; FCM_PROJECT_ID is not configured"
        : "FCM sender is configured through Application Default Credentials",
    );
    // The gateway and this server's own identity there are not secrets — the gateway's origin is
    // what a phone compares, and the server ID is in every manifest. The relay credential is, and
    // it is not in this line or any other.
    log(
      config.relay === undefined
        ? "gateway push relay is off; RELAY_URL is not configured"
        : `gateway push relay is configured: ${config.relay.relayUrl} as server ${config.relay.serverId}`,
    );
    log(
      transferProvider === undefined
        ? "no Solana RPC endpoint is configured (SOLANA_RPC_URL), so transfers aren't served and nothing can be confirmed on chain"
        : `transfers are served against the configured Solana RPC endpoint (timeout ${timeoutMs} ms); a sent transaction is confirmed against ${direct.requests.confirmations?.endpoint ?? ""}`,
    );

    const core = direct.requests;
    const mcp = mcpAdapter(config, direct.liveCommands, core, log, () =>
      direct.pairing.issue(),
    );
    let updateUrl: string | undefined;
    const secure =
      config.tlsCertificatePath !== undefined &&
      config.tlsPrivateKeyPath !== undefined;
    const h2c = config.h2c === true;
    const routes = (includeUpdates: boolean, includeLegacy: boolean) =>
      direct.phoneHandler({
        ...(includeLegacy ? { legacyPhoneToken: config.phoneToken } : {}),
        updateUrl: () => updateUrl,
        includeUpdates,
      });
    const phoneApi = routes(
      (secure || h2c) && config.updatePort === undefined,
      !secure && !h2c,
    );

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
        health(req, res, secure || h2c);
      } else if (isPairingLinkPath(path)) {
        handlePairingLink(req as IncomingMessage, response, {
          publicOrigin: config.publicUrl ?? listeningUrl ?? "",
          identity: PAIRING_PAGE,
          qrModulePath,
        });
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
    const server: ListenableServer = secure
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
      : h2c
        ? createHttp2Server(handler)
        : createServer(handler);
    const updateSessions = new Set<ServerHttp2Session>();
    if (secure || h2c)
      trackSessions(server as Http2Server | Http2SecureServer, updateSessions);
    server.listen(config.port, config.host);
    await once(server, "listening");

    const { port } = server.address() as AddressInfo;
    const url = `${secure ? "https" : "http"}://${config.host.includes(":") ? `[${config.host}]` : config.host}:${port}`;
    listeningUrl = url;
    let updateServer: Http2Server | undefined;
    if (config.updatePort !== undefined) {
      const updateApi = routes(true, false);
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
    } else if (secure || h2c) {
      updateUrl = new URL(config.publicUrl ?? url).origin;
    }
    // The manifest names the URL the phone pairs with and calls, which is the public URL when the
    // operator configured one and the listening URL otherwise. Its revision moves only when that
    // content changes, so a restart with the same settings republishes the same revision and the
    // phone keeps what it cached.
    const manifest = direct.manifest;
    log(
      `listening on ${url}: ${mcp === undefined ? "no MCP endpoint (MCP_ENABLED=false)" : `MCP at ${url}/mcp`}, ${secure || h2c ? "production phone APIs on the same origin" : `phone API at ${url}/${LiveCommandService.typeName}`}`,
    );
    if (secure) {
      log(
        "native TLS serves pairing, request, and update APIs; the legacy LiveCommandService diagnostic is not served",
      );
    }
    if (h2c) {
      log(
        "cleartext HTTP/2 (h2c) serves pairing, request, and update APIs behind a TLS HTTP/2 proxy; the legacy LiveCommandService diagnostic is not served",
      );
    }
    log(
      updateUrl === undefined
        ? "production updates are not configured"
        : `production updates are served as gRPC over HTTP/2 at ${updateUrl}`,
    );
    log(
      `the server manifest names ${config.publicUrl ?? url} as a direct server, ` +
        `protocol ${manifest.protocolVersion}, settings revision ${manifest.settingsRevision}`,
    );
    // Said at every start, because an empty list is the one setting that quietly stops a phone
    // from signing anything for this server (SEE-174). The list is in the direct reference.
    const networks =
      manifest.reference.case === "direct"
        ? manifest.reference.value.supportedNetworks
        : [];
    log(
      networks.length === 0
        ? "the manifest declares no Solana network, so an up-to-date phone signs nothing for this server; " +
            'set SAC_SUPPORTED_NETWORKS (for example "mainnet") to the networks it runs against'
        : `the manifest declares ${networks.map(solanaNetworkName).join(", ")} (SAC_SUPPORTED_NETWORKS)`,
    );

    let closing: Promise<void> | undefined;
    return {
      url,
      serverId: direct.serverId,
      updateUrl,
      close() {
        closing ??= (async () => {
          direct.beginShutdown();
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
          try {
            await mcp?.close();
          } finally {
            try {
              closeAll(server);
              if (updateServer !== undefined) closeAll(updateServer);
              for (const session of updateSessions) session.destroy();
              await Promise.all([stopped, updatesStopped]);
            } finally {
              try {
                await direct.close();
                log("stopped");
              } finally {
                await fcmSender?.close().catch(() => undefined);
                instanceLock.release();
              }
            }
          }
        })();
        return closing;
      },
    };
  } catch (error) {
    direct.beginShutdown();
    await direct.close().catch(() => undefined);
    instanceLock.release();
    throw error;
  }
}

/** Translates concrete provider failures into the SDK's stable optional-integration boundary. */
async function providerCall<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof ChainUnavailable) {
      throw new ProviderUnavailable(error.message, { cause: error });
    }
    if (error instanceof UnsupportedTransfer) {
      throw new UnsupportedPreparation(error.message);
    }
    throw error;
  }
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
  issuePairing: () => IssuedPairing,
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
    allowedHosts: [
      ...(config.mcpAllowedHosts ?? []),
      ...publicHostname(config.publicUrl),
    ],
    demoTools: config.demoTools,
    issuePairing,
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

function health(req: MainRequest, res: MainResponse, secure: boolean): void {
  const response = res as ServerResponse;
  // A native-TLS listener can be forwarded byte-for-byte to the public internet. Its readiness
  // route exists only for a probe connecting inside the process/container; public peers get the
  // same answer as an undeclared route. Cleartext deployments keep health on their private Docker
  // network/host-loopback mapping so the separately managed Caddy container can probe it.
  if (secure && !isLoopbackAddress(req.socket.remoteAddress)) {
    response.writeHead(404, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ error: "not_found" }));
    return;
  }
  if (req.method !== "GET" && req.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  response.writeHead(200, { "Content-Type": "application/json" });
  if (req.method === "GET") response.end(JSON.stringify({ status: "ok" }));
  else response.end();
}

export function isLoopbackAddress(address: string | undefined): boolean {
  if (address === undefined) return false;
  if (address === "::1") return true;
  const ipv4 = address.startsWith("::ffff:") ? address.slice(7) : address;
  return ipv4.startsWith("127.");
}

/** The advertised origin's host is always allowed on /mcp, besides MCP_ALLOWED_HOSTS. */
function publicHostname(publicUrl: string | undefined): readonly string[] {
  if (publicUrl === undefined) return [];
  let hostname: string;
  try {
    hostname = new URL(publicUrl).hostname.toLowerCase();
  } catch {
    return [];
  }
  if (hostname === "" || hostname === "127.0.0.1" || hostname === "localhost") {
    return [];
  }
  return [hostname];
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
