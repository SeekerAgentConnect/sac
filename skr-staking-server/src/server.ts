/**
 * Starting and stopping this server.
 *
 * One listener carries four things: `/healthz`, `/mcp` for the agent, `/pair` for the owner's
 * browser, and the Direct Server SDK's phone API for everything else. The SDK owns pairing, the
 * pairing page, the request lifecycle, preparation, results, confirmation, the phone's live update
 * stream and the gateway relay — this file only decides which path reaches which handler, which
 * origin the phone is told to stream updates from, and hands the SDK the staking provider it
 * should prepare with.
 *
 * The one exception to "one listener" is loopback development: an HTTP/1.1 main port cannot carry
 * gRPC, and an h2c one cannot carry the phone's HTTP/1.1 unary calls, so a server a phone reaches
 * over `adb reverse` streams its updates from a second port (SKR_STAKING_UPDATE_PORT). It is the
 * general server's arrangement, for the general server's reason.
 */
import {
  createServer as createHttpServer,
  type IncomingMessage,
  type ServerResponse,
} from "node:http";
import {
  createServer as createH2cServer,
  type Http2Server,
  type ServerHttp2Session,
} from "node:http2";
import { setTimeout as delay } from "node:timers/promises";
import {
  alreadyAnswered,
  writeJson,
  type AnyRequest,
  type AnyResponse,
} from "./http.ts";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import {
  ProviderUnavailable,
  UnsupportedPreparation,
  handlePairingLink,
  isPairingLinkPath,
  openDirectServer,
  type ConfirmationProvider,
  type DirectServer,
  type StakingProvider,
} from "@seeker-vault/server-sdk";
import { Network } from "@seeker-vault/server-sdk/protocol";
import type { Config } from "./config.ts";
import { UNUSED_LIVE_COMMAND_TIMEOUT_SECONDS } from "./config.ts";
import { ChainUnavailable, SolanaRpc } from "./skr/chain.ts";
import { isApprovedTransaction } from "./skr/confirmation.ts";
import { SkrStakingProvider, UnsupportedStaking } from "./skr/provider.ts";
import { mcpEndpoint, type McpEndpoint } from "./mcp-endpoint.ts";
import { PAIRING_PAGE, qrModulePath } from "./pairing/landing-page.ts";

export const VERSION = "0.1.0";

// How long close() lets in-flight responses finish before it ends the sessions still open.
const CLOSE_GRACE_MS = 1000;

export interface StartedServer {
  /** The origin the listener actually came up on. */
  readonly url: string;
  /**
   * The gRPC/HTTP2 origin a paired phone is told to open its update stream on, or undefined when
   * this server serves no live updates — which is the case on a plain HTTP/1.1 listener with no
   * update port, where the phone has only its manual refresh.
   */
  readonly updateUrl: string | undefined;
  readonly direct: DirectServer;
  readonly provider: SkrStakingProvider;
  close(): Promise<void>;
}

export interface StartOptions {
  readonly log?: (message: string) => void;
}

/**
 * Turns this package's chain errors into the two the SDK understands.
 *
 * The distinction is the whole reason it exists: `CHAIN_UNAVAILABLE` tells an agent to try again,
 * and `INVALID_PARAMETERS` tells it that trying again will not help. Collapsing them would make a
 * position that cannot be unstaked look like an endpoint having a bad minute.
 */
async function providerCall<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    if (error instanceof ChainUnavailable) {
      throw new ProviderUnavailable(error.message, { cause: error });
    }
    if (error instanceof UnsupportedStaking) {
      throw new UnsupportedPreparation(error.message);
    }
    throw error;
  }
}

export async function startStakingServer(
  config: Config,
  options: StartOptions = {},
): Promise<StartedServer> {
  const log =
    options.log ?? ((message) => console.log(`[skr-staking] ${message}`));

  mkdirSync(dirname(config.databasePath), { recursive: true });

  const chain = new SolanaRpc(config.rpcUrl, {
    timeoutMs: config.rpcTimeoutMs,
  });
  const provider = new SkrStakingProvider(chain, {
    ...(config.guardian === undefined ? {} : { guardian: config.guardian }),
  });

  // Refuse to come up against the wrong cluster. A staking server pointed at devnet would read
  // every account as absent and report "you have nothing staked", which is indistinguishable from
  // the truth and is the one wrong answer worth refusing to start over.
  await providerCall(() => provider.assertNetwork(Network.MAINNET));
  log(
    `reading mainnet-beta; staking with guardian pool ${provider.addresses.guardianPool.toBase58()}`,
  );

  const stakingProvider: StakingProvider = {
    checkStaking: (action) => providerCall(() => provider.check(action)),
    buildStaking: (action, at) =>
      providerCall(() => provider.build(action, at)),
  };

  /**
   * How a submitted staking transaction stops being submitted.
   *
   * Without this the SDK builds no `ConfirmationTracker`, and a request the wallet has sent stays
   * SUBMITTED for good: the agent polls and reads the same unchanged request, and the owner's own
   * `CheckStatus` on the phone is answered `CHAIN_UNAVAILABLE` because there is nothing to ask.
   * For staking that is worse than an unfinished transfer — an unstake nobody can confirm is a
   * 48-hour cooldown the owner cannot tell has started.
   *
   * It reads the same endpoint everything else here reads, and it can do nothing but read: there
   * is no submit call in this interface, and a transaction that does not match the approved bytes
   * settles nothing rather than being reported either way.
   */
  const confirmationProvider: ConfirmationProvider = {
    endpointUrl: config.rpcUrl,
    assertNetwork: (network) =>
      providerCall(() => provider.assertNetwork(network)),
    signatureStatus: (signature, searchHistory) =>
      providerCall(() => chain.signatureStatus(signature, searchHistory)),
    confirmedTransaction: (signature) =>
      providerCall(() => chain.confirmedTransaction(signature)),
    blockHeight: () => providerCall(() => chain.blockHeight()),
    matchesApprovedTransaction: isApprovedTransaction,
  };

  let listeningUrl: string | undefined;
  // Undefined until a listener that can carry gRPC is up, and read on every Pair and every
  // GetConnectionCapabilities rather than captured: the SDK advertises an update capability only
  // when this returns an origin, and a phone that was never told one never opens a stream — it
  // sits on its last sync until the owner pulls to refresh. That is the whole of SEE-150's bug.
  let updateUrl: string | undefined;
  const direct = openDirectServer({
    databasePath: config.databasePath,
    publicOrigin: () => config.publicUrl ?? listeningUrl ?? "",
    requestTtlSeconds: config.requestTtlSeconds,
    pendingLimit: config.pendingLimit,
    pairingTokenTtlSeconds: config.pairingTokenTtlSeconds,
    liveCommandTimeoutSeconds: UNUSED_LIVE_COMMAND_TIMEOUT_SECONDS,
    log,
    stakingProvider,
    confirmationProvider,
    // The gateway relay (SEE-144): the one way this server wakes a phone that is not looking. It
    // holds no Firebase credential of its own, so the relay is the only push path it has, and the
    // SDK builds the sender from these three strings.
    ...(config.relay === undefined ? {} : { relay: config.relay }),
  });
  // The gateway and this server's identity there are not secrets — the gateway's origin is what a
  // phone compares, and the server ID is in every manifest. The relay credential is, and it is not
  // in this line or any other.
  log(
    config.relay === undefined
      ? "gateway push relay is off; SKR_STAKING_RELAY_URL is not configured"
      : `gateway push relay is configured: ${config.relay.relayUrl} as server ${config.relay.serverId}`,
  );

  let mcp: McpEndpoint | undefined;
  try {
    mcp = mcpEndpoint({
      core: direct.requests,
      provider,
      issuePairing: () => direct.pairing.issue(),
      mcpToken: config.mcpToken,
      allowedHosts: config.allowedHosts,
      version: VERSION,
      log,
    });
    // No `legacyPhoneToken`: that credential exists for the Stage 1 live diagnostic, which this
    // server does not serve. A phone here authenticates with the credential it was issued at
    // pairing, and nothing else opens the phone API.
    //
    // The update routes go wherever gRPC can actually reach them: on the main listener when it is
    // h2c, and on the update port otherwise. Serving them on an HTTP/1.1 listener would be a route
    // no phone could ever complete a stream on.
    const routes = (includeUpdates: boolean) =>
      direct.phoneHandler({ updateUrl: () => updateUrl, includeUpdates });
    const phone = routes(config.h2c);

    const handler = (request: AnyRequest, response: AnyResponse): void => {
      const path = (request.url ?? "/").split("?")[0] ?? "/";
      if (path === "/healthz") {
        writeJson(response, 200, { status: "ok" });
        return;
      }
      if (path === "/mcp") {
        void mcp?.handle(request, response).catch((error: unknown) => {
          log(`/mcp failed: ${messageOf(error)}`);
          if (!alreadyAnswered(response)) {
            writeJson(response, 500, { error: "internal" });
          }
        });
        return;
      }
      // The public pairing page. It is served from the configured origin rather than a forwarded
      // Host header, it issues nothing, and the pairing code it shows arrives in the URL fragment,
      // which this process never receives.
      if (isPairingLinkPath(path)) {
        handlePairingLink(
          request as IncomingMessage,
          response as ServerResponse,
          {
            publicOrigin: config.publicUrl ?? listeningUrl ?? "",
            identity: PAIRING_PAGE,
            qrModulePath,
          },
        );
        return;
      }
      phone(request, response);
    };

    const server = config.h2c
      ? createH2cServer(handler as never)
      : createHttpServer(handler as never);
    // An update stream is long-lived by design, and an HTTP/2 client keeps its session open after
    // the stream ends. Both are tracked so shutdown can end them rather than wait on a phone.
    const updateSessions = new Set<ServerHttp2Session>();
    if (config.h2c) trackSessions(server as Http2Server, updateSessions);

    await new Promise<void>((resolve, reject) => {
      server.once("error", reject);
      server.listen(config.port, config.host, () => {
        server.removeListener("error", reject);
        resolve();
      });
    });
    const address = server.address();
    const port =
      address !== null && typeof address === "object"
        ? address.port
        : config.port;
    // Cleartext either way: h2c is still http, and TLS is terminated in front of this server.
    listeningUrl =
      config.publicUrl ?? `http://${displayHost(config.host)}:${port}`;
    log(
      `listening on ${listeningUrl}; MCP at /mcp, pairing page at /pair, phone API on the same listener`,
    );

    let updateServer: Http2Server | undefined;
    if (config.updatePort !== undefined) {
      // The phone API again, updates included, on a port of its own. Nothing but the phone API is
      // served here: no /mcp, no /pair, no health check — it is a development convenience, not a
      // second front door.
      const listener = createH2cServer(routes(true) as never);
      trackSessions(listener, updateSessions);
      await new Promise<void>((resolve, reject) => {
        listener.once("error", reject);
        listener.listen(config.updatePort, config.host, () => {
          listener.removeListener("error", reject);
          resolve();
        });
      }).catch(async (error: unknown) => {
        // The main listener is already up; it must not outlive a start that failed.
        await stopServer(server);
        throw error;
      });
      updateServer = listener;
      const updateAddress = listener.address();
      const updatePort =
        updateAddress !== null && typeof updateAddress === "object"
          ? updateAddress.port
          : config.updatePort;
      updateUrl = `http://${displayHost(config.host)}:${updatePort}`;
    } else if (config.h2c) {
      // Behind a TLS-terminating HTTP/2 proxy the phone streams from the origin it paired with: the
      // proxy carries HTTP/2 to this listener, and the public origin is the only one it answers on.
      // An origin and nothing more, as the general server advertises it — a path would be refused.
      updateUrl = new URL(config.publicUrl ?? listeningUrl).origin;
    }
    log(
      updateUrl === undefined
        ? "live updates are not served: set SKR_STAKING_H2C behind an HTTP/2 proxy, or SKR_STAKING_UPDATE_PORT on loopback; the phone refreshes only by hand"
        : `live updates are served as gRPC over HTTP/2 at ${updateUrl}`,
    );

    const endpoint = mcp;
    const updates = updateServer;
    let closing: Promise<void> | undefined;
    return {
      url: listeningUrl,
      updateUrl,
      direct,
      provider,
      close: () => {
        closing ??= (async () => {
          // Ends every update stream first, so the listeners below have nothing long-lived to
          // wait for; then the sessions a phone kept open after its stream ended, which would
          // otherwise hold `close` until the phone let go.
          direct.beginShutdown();
          await endpoint.close();
          const stopped = Promise.all([
            stopServer(server),
            updates === undefined ? Promise.resolve() : stopServer(updates),
          ]);
          await Promise.race([
            stopped,
            delay(CLOSE_GRACE_MS, undefined, { ref: false }),
          ]);
          for (const session of updateSessions) session.destroy();
          await stopped;
          await direct.close();
        })();
        return closing;
      },
    };
  } catch (error) {
    // Whatever failed, the database must not be left locked by a server that never came up.
    await mcp?.close();
    direct.beginShutdown();
    await direct.close();
    throw error;
  }
}

function stopServer(server: {
  close(callback: () => void): unknown;
}): Promise<void> {
  return new Promise((resolve) => server.close(() => resolve()));
}

function trackSessions(
  server: Http2Server,
  sessions: Set<ServerHttp2Session>,
): void {
  server.on("session", (session) => {
    sessions.add(session);
    session.once("close", () => sessions.delete(session));
  });
}

/** A wildcard bind has no address worth printing, so say the loopback one a person can use. */
function displayHost(host: string): string {
  if (host === "0.0.0.0" || host === "::") return "127.0.0.1";
  return host === "::1" ? "[::1]" : host;
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
