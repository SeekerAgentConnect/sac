import { randomUUID } from "node:crypto";
import { once } from "node:events";
import {
  createServer,
  type IncomingMessage,
  type Server as HttpServer,
  type ServerResponse,
} from "node:http";
import type { Http2ServerRequest, Http2ServerResponse } from "node:http2";
import type { AddressInfo } from "node:net";

import { create, toBinary } from "@bufbuild/protobuf";
import { connectNodeAdapter } from "@connectrpc/connect-node";

import {
  RelayCapabilitySchema,
  UpdateCapabilitySchema,
} from "./gen/seekervault/request/v1/service_pb.js";
import {
  ActionRequestSchema,
  type ActionRequest,
} from "./gen/seekervault/request/v1/request_pb.js";
import type {
  ServerManifest,
  SolanaNetwork,
} from "./gen/seekervault/server/v1/manifest_pb.js";
import { LiveCommandBridge } from "./live/bridge.ts";
import { canonicalSupportedNetworks, publishManifest } from "./manifest.ts";
import { pairingRoutes } from "./pairing/service.ts";
import { pairingUri } from "./pairing/uri.ts";
import { phoneRoutes } from "./phone-api.ts";
import type {
  ConfirmationProvider,
  StakingProvider,
  TransferProvider,
} from "./providers.ts";
import {
  FcmInvalidationDispatcher,
  type InvalidationSender,
} from "./push/invalidation.ts";
import {
  GatewayRelaySender,
  RELAY_PROTOCOL_VERSION,
  RelayInvalidationDispatcher,
  type RelayConfiguration,
  type RelaySender,
} from "./push/relay.ts";
import { agentRequests, type AgentRequests } from "./requests/agent-api.ts";
import { ConfirmationTracker } from "./requests/confirmation.ts";
import { requestRoutes } from "./requests/phone-service.ts";
import { TransactionPreparer } from "./requests/preparation.ts";
import { openDatabase, schemaVersion } from "./storage/database.ts";
import {
  PairingStore,
  type IssuedToken,
  type PairedPhone,
  type Revocation,
} from "./storage/pairing-store.ts";
import { RequestStore } from "./storage/request-store.ts";
import {
  observeCommittedRequestUpdates,
  UpdateStore,
} from "./storage/update-store.ts";
import {
  UPDATE_PROTOCOL_VERSION,
  UpdateCoordinator,
  updateRoutes,
} from "./updates/service.ts";

const PHONE_API_MAX_REQUEST_BYTES = 64 * 1024;

export type DirectServerLogger = (message: string) => void;

export interface OpenDirectServerOptions {
  /** The durable SQLite file. No default path is chosen inside the installed package. */
  readonly databasePath: string;
  /** The externally advertised origin placed in pairing codes and the direct manifest. */
  readonly publicOrigin: string | (() => string);
  /**
   * The Solana networks this server's wallet operations are configured for, published as the
   * manifest's `direct.supported_networks` (SEE-174). There is deliberately no default: absent or
   * empty declares no network, and the phone shows the connection but signs nothing for it until
   * the server declares one. A host that reads the list from its configuration uses
   * `parseSupportedNetworks`, so every server accepts the same names.
   *
   * List what the server actually runs against — a server whose chain reads and preparation only
   * work on Mainnet says Mainnet — and never every network the protocol can name. It is separate
   * from the environment: a production server may run on Devnet. UNSPECIFIED, unknown values and
   * duplicates make opening fail; order doesn't matter, because it is published in canonical
   * order. Changing it moves the manifest's settings revision on the next start.
   */
  readonly supportedNetworks?: readonly SolanaNetwork[];
  readonly requestTtlSeconds: number;
  readonly pendingLimit: number;
  readonly pairingTokenTtlSeconds: number;
  readonly liveCommandTimeoutSeconds: number;
  readonly log: DirectServerLogger;
  readonly now?: () => number;
  readonly updatePollMs?: number;
  readonly transferProvider?: TransferProvider;
  /**
   * Preparing staking actions, for a host that serves them (SEE-146). Supplied separately from
   * `transferProvider` because serving one says nothing about serving the other, and a host that
   * supplies neither prepares nothing rather than refusing halfway through a review.
   */
  readonly stakingProvider?: StakingProvider;
  readonly confirmationProvider?: ConfirmationProvider;
  /**
   * Direct Firebase delivery: this server holds a service-account credential and sends its own
   * wake-ups. It stays supported for operator-controlled installations that already have a
   * Firebase project.
   */
  readonly invalidationSender?: InvalidationSender;
  /**
   * Gateway relay delivery: this server holds no Firebase credential, and the gateway's operator
   * wakes phones on its behalf (SEE-144).
   *
   * The configuration is always required, even when a sender is supplied, because two of its three
   * fields are not the transport's business: the relay URL and the server ID are what a paired
   * phone is told to authorize, and a relay with a way to send but nothing to advertise could
   * never be given a handle to send with.
   *
   * Configuring this and invalidationSender at once is refused rather than merged. Both would fire
   * on the same committed update, so a phone would be woken twice for one change and an operator
   * would have two places to look when it stopped happening — which is exactly the kind of
   * ambiguity a duplicate-delivery bug hides in.
   */
  readonly relay?: RelayConfiguration & { readonly sender?: RelaySender };
}

export interface IssuedPairing extends IssuedToken {
  readonly uri: string;
}

export interface DirectPairing {
  issue(): IssuedPairing;
  active(): PairedPhone | undefined;
  revoke(connectionId: string): Revocation;
}

export interface PhoneHandlerOptions {
  /** The Stage 1 live diagnostic credential, separate from paired-phone credentials. */
  readonly legacyPhoneToken?: string;
  /** Read lazily because a host may learn an ephemeral update port only after listening. */
  readonly updateUrl?: string | (() => string | undefined);
  /** Mount the production UpdateService on this handler. */
  readonly includeUpdates?: boolean;
  readonly readMaxBytes?: number;
}

export type PhoneApiHandler = (
  request: IncomingMessage | Http2ServerRequest,
  response: ServerResponse | Http2ServerResponse,
) => void;

export interface DirectServer {
  readonly serverId: string;
  readonly databasePath: string;
  readonly databaseSchemaVersion: number;
  readonly manifest: ServerManifest;
  readonly requests: AgentRequests;
  readonly pairing: DirectPairing;
  /** The existing MCP live diagnostic uses this facade; it has no durable request authority. */
  readonly liveCommands: LiveCommandBridge;
  phoneHandler(options?: PhoneHandlerOptions): PhoneApiHandler;
  /** Cancels in-memory work and streams, but leaves SQLite open for in-flight responses. */
  beginShutdown(): void;
  /** Idempotently drains optional invalidations and closes SQLite. */
  close(): Promise<void>;
}

/**
 * Explicitly opens one durable direct engine. Importing this package opens nothing, reads no
 * environment, registers no signal and starts no listener or background loop.
 */
/**
 * Which push transport this server has, if any. Exactly one, because both would wake a phone twice
 * for one change; the caller was already refused above if it asked for two.
 *
 * A relay configuration builds the HTTP sender here rather than in the caller, so a developer
 * configures three strings instead of constructing a transport — and a relay URL that is not an
 * origin, or a server ID that is not a UUID, is a startup failure with a named reason rather than
 * wake-ups that quietly go nowhere.
 */
function buildInvalidations(
  options: OpenDirectServerOptions,
  pairingStore: PairingStore,
): FcmInvalidationDispatcher | RelayInvalidationDispatcher | undefined {
  if (options.invalidationSender !== undefined) {
    return new FcmInvalidationDispatcher(
      pairingStore,
      options.invalidationSender,
      options.log,
    );
  }
  if (options.relay === undefined) return undefined;
  // The configuration is validated either way, so a relay URL that is not an origin or a server ID
  // that is not a UUID is a startup failure with a named reason — not wake-ups that go nowhere and
  // an advertisement no phone will accept.
  const sender = options.relay.sender ?? new GatewayRelaySender(options.relay);
  return new RelayInvalidationDispatcher(pairingStore, sender, options.log);
}

export function openDirectServer(
  options: OpenDirectServerOptions,
): DirectServer {
  // Checked before anything is opened, so a bad list is a startup failure with a named reason and
  // never a manifest the phone has to refuse.
  const supportedNetworks = canonicalSupportedNetworks(
    options.supportedNetworks ?? [],
  );
  const db = openDatabase(options.databasePath);
  try {
    const requests = new RequestStore(db, {
      defaultTtlSeconds: options.requestTtlSeconds,
      pendingLimit: options.pendingLimit,
      now: options.now,
    });
    const pairingStore = new PairingStore(db, { now: options.now });
    const serverId = pairingStore.serverId();
    const publicOrigin = (): string =>
      typeof options.publicOrigin === "function"
        ? options.publicOrigin()
        : options.publicOrigin;
    const serverInstanceId = randomUUID();
    const updates = new UpdateStore(db, {
      serverInstanceId,
      now: options.now,
    });
    const updateCoordinator = new UpdateCoordinator();
    const liveCommands = new LiveCommandBridge({
      timeoutSeconds: options.liveCommandTimeoutSeconds,
      log: options.log,
    });
    const preparer =
      options.transferProvider === undefined &&
      options.stakingProvider === undefined
        ? undefined
        : new TransactionPreparer(
            requests,
            {
              transfers: options.transferProvider,
              staking: options.stakingProvider,
            },
            options.now,
          );
    const tracker =
      options.confirmationProvider === undefined
        ? undefined
        : new ConfirmationTracker(requests, options.confirmationProvider, {
            now: options.now,
          });
    if (
      options.invalidationSender !== undefined &&
      options.relay !== undefined
    ) {
      throw new Error(
        "configure either invalidationSender or relay, not both: " +
          "both send on the same committed update, so a phone would be woken twice",
      );
    }
    const invalidations = buildInvalidations(options, pairingStore);
    const stopInvalidations =
      invalidations === undefined
        ? undefined
        : observeCommittedRequestUpdates(db, (update) =>
            invalidations.invalidate(update),
          );

    const observerStops = new Set<() => void>();
    const observe: AgentRequests["observe"] = (requestId, listener) => {
      let subscribed = true;
      let lastValue: string | undefined;
      const emit = (): void => {
        if (!subscribed) return;
        let request: ActionRequest;
        try {
          request = requests.get(requestId);
        } catch {
          return;
        }
        const value = Buffer.from(
          toBinary(ActionRequestSchema, request),
        ).toString("base64");
        if (value === lastValue) return;
        lastValue = value;
        try {
          listener(request);
        } catch (error) {
          options.log(
            `request ${requestId}: observer failed: ${error instanceof Error ? error.message : String(error)}`,
          );
        }
      };
      queueMicrotask(emit);
      const stop = observeCommittedRequestUpdates(db, emit);
      const unsubscribe = () => {
        if (!subscribed) return;
        subscribed = false;
        stop();
        observerStops.delete(unsubscribe);
      };
      observerStops.add(unsubscribe);
      return unsubscribe;
    };
    const core = agentRequests(requests, { preparer, tracker, observe });
    let shuttingDown = false;
    let closing: Promise<void> | undefined;

    const direct: DirectServer = {
      serverId,
      databasePath: options.databasePath,
      databaseSchemaVersion: schemaVersion(db),
      get manifest() {
        return publishManifest(pairingStore, {
          serverId,
          url: publicOrigin(),
          supportedNetworks,
        });
      },
      requests: core,
      pairing: {
        issue() {
          const issued = pairingStore.issue(
            publicOrigin(),
            options.pairingTokenTtlSeconds,
          );
          return { ...issued, uri: pairingUri(issued) };
        },
        active: () => pairingStore.activeConnection(),
        revoke: (connectionId) => pairingStore.revoke(connectionId),
      },
      liveCommands,
      phoneHandler(handlerOptions = {}) {
        const updateUrl = (): string | undefined =>
          typeof handlerOptions.updateUrl === "function"
            ? handlerOptions.updateUrl()
            : handlerOptions.updateUrl;
        return connectNodeAdapter({
          routes: (router) => {
            if (handlerOptions.legacyPhoneToken !== undefined) {
              phoneRoutes(
                liveCommands,
                handlerOptions.legacyPhoneToken,
                options.log,
              )(router);
            }
            requestRoutes(
              requests,
              pairingStore,
              options.log,
              preparer,
              tracker,
              supportedNetworks,
            )(router);
            pairingRoutes(
              pairingStore,
              options.log,
              () =>
                updateUrl() === undefined
                  ? undefined
                  : create(UpdateCapabilitySchema, {
                      protocolVersion: UPDATE_PROTOCOL_VERSION,
                      grpcUrl: updateUrl(),
                    }),
              () => direct.manifest,
              () =>
                options.relay === undefined
                  ? undefined
                  : create(RelayCapabilitySchema, {
                      protocolVersion: RELAY_PROTOCOL_VERSION,
                      relayUrl: options.relay.relayUrl,
                      serverId: options.relay.serverId,
                    }),
            )(router);
            if (handlerOptions.includeUpdates === true) {
              updateRoutes(
                pairingStore,
                requests,
                updates,
                serverInstanceId,
                updateCoordinator,
                options.log,
                { tracker, pollMs: options.updatePollMs },
              )(router);
            }
          },
          readMaxBytes:
            handlerOptions.readMaxBytes ?? PHONE_API_MAX_REQUEST_BYTES,
        });
      },
      beginShutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        liveCommands.shutdown();
        updateCoordinator.shutdown();
        stopInvalidations?.();
        for (const unsubscribe of [...observerStops]) unsubscribe();
      },
      close() {
        closing ??= (async () => {
          direct.beginShutdown();
          await invalidations?.close();
          db.close();
        })();
        return closing;
      },
    };
    return direct;
  } catch (error) {
    db.close();
    throw error;
  }
}

export interface StartPhoneApiOptions {
  readonly host: string;
  readonly port: number;
  readonly legacyPhoneToken?: string;
}

export interface StartedPhoneApi {
  readonly url: string;
  close(): Promise<void>;
}

/** Starts a small HTTP phone listener for an embedded backend. Product ingress remains host-owned. */
export async function startPhoneApi(
  direct: DirectServer,
  options: StartPhoneApiOptions,
): Promise<StartedPhoneApi> {
  const handler = direct.phoneHandler({
    ...(options.legacyPhoneToken === undefined
      ? {}
      : { legacyPhoneToken: options.legacyPhoneToken }),
  });
  const server = createServer(handler);
  server.listen(options.port, options.host);
  try {
    await once(server, "listening");
  } catch (error) {
    server.close();
    throw error;
  }
  const { port } = server.address() as AddressInfo;
  const host = options.host.includes(":") ? `[${options.host}]` : options.host;
  let closing: Promise<void> | undefined;
  return {
    url: `http://${host}:${port}`,
    close() {
      closing ??= stopServer(server);
      return closing;
    },
  };
}

function stopServer(server: HttpServer): Promise<void> {
  return new Promise<void>((resolve) => {
    server.close(() => resolve());
    server.closeIdleConnections();
  }).finally(() => server.closeAllConnections());
}
