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

import { UpdateCapabilitySchema } from "./gen/seekervault/request/v1/service_pb.js";
import {
  ActionRequestSchema,
  type ActionRequest,
} from "./gen/seekervault/request/v1/request_pb.js";
import type { ServerManifest } from "./gen/seekervault/server/v1/manifest_pb.js";
import { LiveCommandBridge } from "./live/bridge.ts";
import { publishManifest } from "./manifest.ts";
import { pairingRoutes } from "./pairing/service.ts";
import { pairingUri } from "./pairing/uri.ts";
import { phoneRoutes } from "./phone-api.ts";
import type { ConfirmationProvider, TransferProvider } from "./providers.ts";
import {
  FcmInvalidationDispatcher,
  type InvalidationSender,
} from "./push/invalidation.ts";
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
  readonly requestTtlSeconds: number;
  readonly pendingLimit: number;
  readonly pairingTokenTtlSeconds: number;
  readonly liveCommandTimeoutSeconds: number;
  readonly log: DirectServerLogger;
  readonly now?: () => number;
  readonly updatePollMs?: number;
  readonly transferProvider?: TransferProvider;
  readonly confirmationProvider?: ConfirmationProvider;
  readonly invalidationSender?: InvalidationSender;
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
export function openDirectServer(
  options: OpenDirectServerOptions,
): DirectServer {
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
      options.transferProvider === undefined
        ? undefined
        : new TransactionPreparer(
            requests,
            options.transferProvider,
            options.now,
          );
    const tracker =
      options.confirmationProvider === undefined
        ? undefined
        : new ConfirmationTracker(requests, options.confirmationProvider, {
            now: options.now,
          });
    const invalidations =
      options.invalidationSender === undefined
        ? undefined
        : new FcmInvalidationDispatcher(
            pairingStore,
            options.invalidationSender,
            options.log,
          );
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
