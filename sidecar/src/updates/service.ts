/**
 * Production request updates (SAW-049). Subscribe reads the durable mutation log and keeps no
 * request queue in memory; Sync freezes reconciliation pages in SQLite. Both authenticate the
 * paired phone and scope every cursor, token, request, and response to that connection.
 */
import { create, toBinary } from "@bufbuild/protobuf";
import { timestampFromMs } from "@bufbuild/protobuf/wkt";
import {
  Code,
  ConnectError,
  type ConnectRouter,
  type HandlerContext,
} from "@connectrpc/connect";

import { bearerToken } from "../auth.ts";
import {
  RequestState,
  type ActionRequest,
  type RequestRef,
} from "../gen/seekervault/request/v1/request_pb.js";
import {
  ConnectionRevokedSchema,
  RemovalReason,
  ReplayCompleteSchema,
  RequestChangedSchema,
  ResumeDisposition,
  ServerHeartbeatSchema,
  ServerReadySchema,
  SyncRequiredReason,
  SyncRequiredSchema,
  SyncResponseSchema,
  UpdateError,
  UpdateErrorDetailSchema,
  UpdateService,
  type KnownRequest,
  type SubscribeRequest,
  type SubscribeResponse,
  type SyncRequest,
  type SyncResponse,
} from "../gen/seekervault/update/v1/update_pb.js";
import { invalidUuidReason } from "../requests/identity.ts";
import type { ConfirmationTracker } from "../requests/confirmation.ts";
import type { PairingStore } from "../storage/pairing-store.ts";
import type { RequestStore } from "../storage/request-store.ts";
import {
  CursorForAnotherConnection,
  CursorForAnotherInstance,
  InvalidCursor,
  InvalidSnapshot,
  SnapshotForAnotherConnection,
  type StoredUpdateEvent,
  type UpdateStore,
} from "../storage/update-store.ts";

export const UPDATE_PROTOCOL_VERSION = 1;
export const UPDATE_MAX_MESSAGE_BYTES = 65_536;
export const UPDATE_DEFAULT_PAGE_SIZE = 50;
export const UPDATE_MAX_PAGE_SIZE = 100;
export const UPDATE_MAX_KNOWN_REQUESTS = 100;
export const UPDATE_CONFIRMATION_LIMIT = 4;
export const UPDATE_HEARTBEAT_SECONDS = 30;
const UPDATE_POLL_MS = 100;
const EXPIRY_SWEEP_MS = 1000;
const OPAQUE_MAX_BYTES = 256;

export interface UpdateRoutesOptions {
  readonly tracker?: ConfirmationTracker;
  readonly heartbeatSeconds?: number;
  readonly pollMs?: number;
}

/** Owns the one live stream generation per connection and every stream during shutdown. */
export class UpdateCoordinator {
  readonly #streams = new Map<string, AbortController>();
  #shutdown = false;

  claim(connectionId: string): {
    readonly signal: AbortSignal;
    release(): void;
  } {
    if (this.#shutdown)
      throw new ConnectError("the sidecar is stopping", Code.Unavailable);
    this.#streams
      .get(connectionId)
      ?.abort(
        new ConnectError(
          "a newer subscription replaced this one",
          Code.Canceled,
        ),
      );
    const controller = new AbortController();
    this.#streams.set(connectionId, controller);
    return {
      signal: controller.signal,
      release: () => {
        if (this.#streams.get(connectionId) === controller) {
          this.#streams.delete(connectionId);
        }
      },
    };
  }

  shutdown(): void {
    this.#shutdown = true;
    for (const stream of this.#streams.values()) {
      stream.abort(
        new ConnectError("the sidecar is stopping", Code.Unavailable),
      );
    }
    this.#streams.clear();
  }

  get activeCount(): number {
    return this.#streams.size;
  }
}

export function updateRoutes(
  pairing: PairingStore,
  requests: RequestStore,
  updates: UpdateStore,
  serverInstanceId: string,
  coordinator: UpdateCoordinator,
  log: (message: string) => void,
  options: UpdateRoutesOptions = {},
): (router: ConnectRouter) => void {
  const heartbeatSeconds = options.heartbeatSeconds ?? UPDATE_HEARTBEAT_SECONDS;
  if (heartbeatSeconds < 15 || heartbeatSeconds > 60) {
    throw new Error("the update heartbeat must be from 15 to 60 seconds");
  }
  const pollMs = options.pollMs ?? UPDATE_POLL_MS;

  return (router) =>
    router.service(UpdateService, {
      subscribe: (messages, context) =>
        subscribe(
          messages,
          context,
          pairing,
          requests,
          updates,
          serverInstanceId,
          coordinator,
          log,
          heartbeatSeconds,
          pollMs,
        ),
      sync: (request, context) =>
        sync(
          request,
          context,
          pairing,
          requests,
          updates,
          serverInstanceId,
          options.tracker,
        ),
    });
}

async function* subscribe(
  messages: AsyncIterable<SubscribeRequest>,
  context: HandlerContext,
  pairing: PairingStore,
  requests: RequestStore,
  updates: UpdateStore,
  serverInstanceId: string,
  coordinator: UpdateCoordinator,
  log: (message: string) => void,
  heartbeatSeconds: number,
  pollMs: number,
): AsyncIterable<SubscribeResponse> {
  const token = bearerToken(context.requestHeader.get("authorization"));
  const authenticated = pairing.authenticate(token);
  if (authenticated === undefined) throw unauthenticated();

  const iterator = messages[Symbol.asyncIterator]();
  const first = await iterator.next();
  if (first.done || first.value.message.case !== "subscribe") {
    throw new ConnectError(
      "the first stream message must be subscribe",
      Code.InvalidArgument,
    );
  }
  requireConnection(authenticated, first.value.connectionId);
  const opening = first.value.message.value;
  requireProtocol(opening.protocolVersion);
  requireOpaque("resume_cursor", opening.resumeCursor);
  requireOpaque("server_instance_id", opening.serverInstanceId);
  if ((opening.resumeCursor === "") !== (opening.serverInstanceId === "")) {
    throw new ConnectError(
      "resume_cursor and server_instance_id must both be empty or both be set",
      Code.InvalidArgument,
    );
  }

  const lease = coordinator.claim(authenticated);
  let clientHeartbeat = 0n;
  let clientSeenAt = Date.now();
  let readerError: unknown;
  let readerDone = false;
  const reader = readHeartbeats(
    iterator,
    authenticated,
    updates,
    () => clientHeartbeat,
    (sequence) => {
      clientHeartbeat = sequence;
      clientSeenAt = Date.now();
    },
  )
    .catch((error: unknown) => {
      readerError = error;
    })
    .finally(() => {
      readerDone = true;
    });

  try {
    const barrier = updates.latestSequence(authenticated);
    const resume = resumeFrom(
      opening.resumeCursor,
      opening.serverInstanceId,
      authenticated,
      barrier,
      updates,
      serverInstanceId,
    );
    yield response(authenticated, serverInstanceId, barrier, updates, {
      case: "ready",
      value: create(ServerReadySchema, {
        protocolVersion: UPDATE_PROTOCOL_VERSION,
        resume: resume.disposition,
        heartbeatIntervalSeconds: heartbeatSeconds,
        maxMessageBytes: UPDATE_MAX_MESSAGE_BYTES,
        maxPageSize: UPDATE_MAX_PAGE_SIZE,
      }),
    });

    let delivered = resume.after;
    if (resume.reason !== undefined) {
      yield response(authenticated, serverInstanceId, barrier, updates, {
        case: "syncRequired",
        value: create(SyncRequiredSchema, { reason: resume.reason }),
      });
      delivered = barrier;
    } else {
      const replay = updates.replay(authenticated, delivered, barrier);
      if (replay.kind === "gap") {
        yield response(authenticated, serverInstanceId, barrier, updates, {
          case: "syncRequired",
          value: create(SyncRequiredSchema, {
            reason: SyncRequiredReason.HISTORY_GAP,
          }),
        });
        delivered = barrier;
      } else {
        for (const event of replay.events) {
          yield eventResponse(authenticated, serverInstanceId, event, updates);
          delivered = event.sequence;
          if (event.kind === "revoked") return;
        }
        yield response(authenticated, serverInstanceId, barrier, updates, {
          case: "replayComplete",
          value: create(ReplayCompleteSchema, {
            throughCursor: updates.cursor(authenticated, barrier),
          }),
        });
      }
    }

    log(`update stream opened for connection ${authenticated}`);
    let lastServerMessageAt = Date.now();
    let lastExpirySweepAt = 0;
    const heartbeatMs = heartbeatSeconds * 1000;
    while (true) {
      throwIfAborted(context.signal);
      throwIfAborted(lease.signal);
      if (readerError !== undefined) {
        throw readerError instanceof Error
          ? readerError
          : new Error("the update request stream failed", {
              cause: readerError,
            });
      }
      if (readerDone) {
        throw new ConnectError(
          "the phone closed its update send stream",
          Code.Canceled,
        );
      }
      const wallNow = Date.now();
      if (wallNow - clientSeenAt >= heartbeatMs * 3) {
        throw new ConnectError(
          "the phone stopped answering update heartbeats",
          Code.DeadlineExceeded,
        );
      }
      if (wallNow - lastExpirySweepAt >= EXPIRY_SWEEP_MS) {
        requests.expireOverdue();
        lastExpirySweepAt = wallNow;
      }

      const latest = updates.latestSequence(authenticated);
      if (latest > delivered) {
        const live = updates.replay(authenticated, delivered, latest);
        if (live.kind === "gap") {
          yield response(authenticated, serverInstanceId, latest, updates, {
            case: "syncRequired",
            value: create(SyncRequiredSchema, {
              reason: SyncRequiredReason.BUFFER_OVERFLOW,
            }),
          });
          delivered = latest;
        } else {
          for (const event of live.events) {
            yield eventResponse(
              authenticated,
              serverInstanceId,
              event,
              updates,
            );
            delivered = event.sequence;
            lastServerMessageAt = Date.now();
            if (event.kind === "revoked") return;
          }
        }
        continue;
      }
      if (wallNow - lastServerMessageAt >= heartbeatMs) {
        yield response(authenticated, serverInstanceId, delivered, updates, {
          case: "heartbeat",
          value: create(ServerHeartbeatSchema, {
            acknowledgedSequence: clientHeartbeat,
            sentAt: timestampFromMs(wallNow),
          }),
        });
        lastServerMessageAt = wallNow;
      }
      await updates.waitForChange(authenticated, delivered, pollMs, [
        context.signal,
        lease.signal,
      ]);
    }
  } finally {
    lease.release();
    // Do not wait for a peer that stopped writing before closing our response. Ending the handler
    // cancels the transport input; the reader has its own catch and then releases those resources.
    void iterator.return?.();
    void reader;
    log(`update stream closed for connection ${authenticated}`);
  }
}

async function sync(
  request: SyncRequest,
  context: HandlerContext,
  pairing: PairingStore,
  requests: RequestStore,
  updates: UpdateStore,
  serverInstanceId: string,
  tracker: ConfirmationTracker | undefined,
): Promise<SyncResponse> {
  const authenticated = pairing.authenticate(
    bearerToken(context.requestHeader.get("authorization")),
  );
  if (authenticated === undefined) throw unauthenticated();
  requireConnection(authenticated, request.connectionId);
  requireProtocol(request.protocolVersion);
  requireOpaque("page_token", request.pageToken);
  requireOpaque("subscription_cursor", request.subscriptionCursor);
  if (request.pageSize > UPDATE_MAX_PAGE_SIZE) {
    throw new ConnectError(
      `page_size must be 1 to ${UPDATE_MAX_PAGE_SIZE}, or zero for ${UPDATE_DEFAULT_PAGE_SIZE}`,
      Code.InvalidArgument,
    );
  }
  const pageSize =
    request.pageSize === 0 ? UPDATE_DEFAULT_PAGE_SIZE : request.pageSize;

  let snapshotId: string;
  let offset: number;
  if (request.pageToken === "") {
    const known = validateKnown(authenticated, request.knownNonterminal);
    if (request.subscriptionCursor !== "") {
      try {
        const sequence = updates.parseCursor(
          authenticated,
          request.subscriptionCursor,
        );
        if (sequence > updates.latestSequence(authenticated))
          throw new InvalidCursor();
      } catch (error) {
        if (isCursorError(error)) {
          throw updateFailedPrecondition(
            "subscription_cursor is not valid for this sidecar and connection",
            UpdateError.SNAPSHOT_INVALID,
          );
        }
        throw error;
      }
    }

    const current = requests.syncCandidates(
      authenticated,
      known.map((item) => item.ref.requestId),
    );
    const eligible = current.filter(checkableBySync);
    const choice = updates.chooseConfirmations(
      authenticated,
      eligible,
      tracker === undefined ? 0 : UPDATE_CONFIRMATION_LIMIT,
    );
    if (tracker !== undefined) {
      await Promise.all(
        choice.selected.map((candidate) => tracker.settle(candidate)),
      );
    }
    const deferred = choice.deferred.map(requiredRef);
    const snapshot = updates.createSnapshot(
      authenticated,
      serverInstanceId,
      known,
      deferred,
    );
    snapshotId = snapshot.id;
    offset = 0;
  } else {
    if (
      request.subscriptionCursor !== "" ||
      request.knownNonterminal.length > 0
    ) {
      throw new ConnectError(
        "subscription_cursor and known_nonterminal are only valid on the first page",
        Code.InvalidArgument,
      );
    }
    ({ snapshotId, offset } = decodePageToken(
      request.pageToken,
      serverInstanceId,
    ));
  }

  let page;
  try {
    page = updates.snapshotPage(
      snapshotId,
      authenticated,
      serverInstanceId,
      offset,
      pageSize,
    );
  } catch (error) {
    if (
      error instanceof InvalidSnapshot ||
      error instanceof SnapshotForAnotherConnection
    ) {
      throw updateFailedPrecondition(
        "the sync snapshot is invalid or expired; restart from page one",
        UpdateError.SNAPSHOT_INVALID,
      );
    }
    throw error;
  }

  const items = [...page.items];
  let result = makeSyncResponse(
    authenticated,
    serverInstanceId,
    page.snapshot,
    items,
    offset,
  );
  while (
    toBinary(SyncResponseSchema, result).byteLength >
      UPDATE_MAX_MESSAGE_BYTES &&
    items.length > 0
  ) {
    items.pop();
    result = makeSyncResponse(
      authenticated,
      serverInstanceId,
      page.snapshot,
      items,
      offset,
    );
  }
  if (items.length === 0 && offset < page.snapshot.total) {
    throw new ConnectError(
      "one stored request exceeds the update message limit",
      Code.ResourceExhausted,
    );
  }
  return result;
}

function makeSyncResponse(
  connectionId: string,
  serverInstanceId: string,
  snapshot: {
    readonly id: string;
    readonly connectionId: string;
    readonly sequence: number;
    readonly total: number;
    readonly deferred: readonly RequestRef[];
  },
  items: readonly {
    readonly kind: "request" | "removed";
    readonly request?: ActionRequest;
    readonly ref?: RequestRef;
    readonly revision: bigint;
  }[],
  offset: number,
): SyncResponse {
  const nextOffset = offset + items.length;
  return create(SyncResponseSchema, {
    connectionId,
    serverInstanceId,
    snapshotCursor: `${serverInstanceId}:${connectionId}:${snapshot.sequence}`,
    requests: items
      .filter((item) => item.kind === "request")
      .map((item) => ({ request: item.request, revision: item.revision })),
    removed: items
      .filter((item) => item.kind === "removed")
      .map((item) => ({
        ref: item.ref,
        revision: item.revision,
        reason: RemovalReason.NOT_FOUND,
      })),
    nextPageToken:
      nextOffset < snapshot.total
        ? encodePageToken(serverInstanceId, snapshot.id, nextOffset)
        : "",
    confirmationDeferred: [...snapshot.deferred],
  });
}

async function readHeartbeats(
  iterator: AsyncIterator<SubscribeRequest>,
  connectionId: string,
  updates: UpdateStore,
  previous: () => bigint,
  observed: (sequence: bigint) => void,
): Promise<void> {
  while (true) {
    const next = await iterator.next();
    if (next.done) return;
    requireConnection(connectionId, next.value.connectionId);
    if (next.value.message.case !== "heartbeat") {
      throw new ConnectError(
        "only heartbeats may follow subscribe",
        Code.InvalidArgument,
      );
    }
    const heartbeat = next.value.message.value;
    if (heartbeat.sequence < previous()) {
      throw new ConnectError(
        "heartbeat sequence decreased",
        Code.InvalidArgument,
      );
    }
    requireOpaque("applied_cursor", heartbeat.appliedCursor);
    if (heartbeat.appliedCursor !== "") {
      try {
        const applied = updates.parseCursor(
          connectionId,
          heartbeat.appliedCursor,
        );
        if (applied > updates.latestSequence(connectionId))
          throw new InvalidCursor();
      } catch (error) {
        if (isCursorError(error)) {
          throw new ConnectError(
            "applied_cursor is not valid for this connection",
            Code.InvalidArgument,
          );
        }
        throw error;
      }
    }
    observed(heartbeat.sequence);
  }
}

function resumeFrom(
  cursor: string,
  instance: string,
  connectionId: string,
  barrier: number,
  updates: UpdateStore,
  serverInstanceId: string,
): {
  readonly disposition: ResumeDisposition;
  readonly reason?: SyncRequiredReason;
  readonly after: number;
} {
  if (cursor === "") {
    return {
      disposition: ResumeDisposition.FULL_SYNC_REQUIRED,
      reason: SyncRequiredReason.INITIAL_SNAPSHOT,
      after: barrier,
    };
  }
  if (instance !== serverInstanceId) {
    return {
      disposition: ResumeDisposition.FULL_SYNC_REQUIRED,
      reason: SyncRequiredReason.CURSOR_INVALID,
      after: barrier,
    };
  }
  try {
    const after = updates.parseCursor(connectionId, cursor);
    if (updates.replay(connectionId, after, barrier).kind === "gap") {
      return {
        disposition: ResumeDisposition.FULL_SYNC_REQUIRED,
        reason: SyncRequiredReason.HISTORY_GAP,
        after: barrier,
      };
    }
    return { disposition: ResumeDisposition.REPLAYING, after };
  } catch (error) {
    if (!isCursorError(error)) throw error;
    return {
      disposition: ResumeDisposition.FULL_SYNC_REQUIRED,
      reason: SyncRequiredReason.CURSOR_INVALID,
      after: barrier,
    };
  }
}

function eventResponse(
  connectionId: string,
  serverInstanceId: string,
  event: StoredUpdateEvent,
  updates: UpdateStore,
): SubscribeResponse {
  if (event.kind === "revoked") {
    return response(connectionId, serverInstanceId, event.sequence, updates, {
      case: "revoked",
      value: create(ConnectionRevokedSchema, {
        revokedAt: timestampFromMs(event.revokedAtMs ?? Date.now()),
      }),
    });
  }
  return response(connectionId, serverInstanceId, event.sequence, updates, {
    case: "requestChanged",
    value: create(RequestChangedSchema, {
      request: event.request,
      revision: event.revision,
    }),
  });
}

function response(
  connectionId: string,
  serverInstanceId: string,
  sequence: number,
  updates: UpdateStore,
  event: SubscribeResponse["event"],
): SubscribeResponse {
  return {
    $typeName: "seekervault.update.v1.SubscribeResponse",
    connectionId,
    serverInstanceId,
    cursor: updates.cursor(connectionId, sequence),
    event,
  };
}

function validateKnown(
  connectionId: string,
  known: readonly KnownRequest[],
): { readonly ref: RequestRef; readonly revision: bigint }[] {
  if (known.length > UPDATE_MAX_KNOWN_REQUESTS) {
    throw new ConnectError(
      `known_nonterminal has more than ${UPDATE_MAX_KNOWN_REQUESTS} entries`,
      Code.InvalidArgument,
    );
  }
  const seen = new Set<string>();
  return known.map((item) => {
    const ref = item.ref;
    if (
      ref === undefined ||
      ref.connectionId !== connectionId ||
      invalidUuidReason("request_id", ref.requestId) !== undefined
    ) {
      throw new ConnectError(
        "known_nonterminal contains a request outside this connection",
        Code.NotFound,
      );
    }
    if (seen.has(ref.requestId)) {
      throw new ConnectError(
        "known_nonterminal contains a duplicate request",
        Code.InvalidArgument,
      );
    }
    seen.add(ref.requestId);
    if (item.revision > BigInt(Number.MAX_SAFE_INTEGER)) {
      throw new ConnectError(
        "known_nonterminal revision is too large",
        Code.InvalidArgument,
      );
    }
    if (isTerminal(item.state)) {
      throw new ConnectError(
        "known_nonterminal contains a terminal request",
        Code.InvalidArgument,
      );
    }
    return { ref, revision: item.revision };
  });
}

function checkableBySync(request: ActionRequest): boolean {
  return (
    request.action?.kind.case === "transfer" &&
    (request.state === RequestState.SUBMITTED ||
      request.state === RequestState.UNKNOWN)
  );
}

function requiredRef(request: ActionRequest): RequestRef {
  if (request.ref === undefined)
    throw new Error("a stored request has no reference");
  return request.ref;
}

function isTerminal(state: RequestState): boolean {
  return [
    RequestState.CANCELLED,
    RequestState.EXPIRED,
    RequestState.COMPLETED,
    RequestState.REJECTED,
    RequestState.CONFIRMED,
    RequestState.FAILED,
  ].includes(state);
}

function encodePageToken(
  instance: string,
  snapshotId: string,
  offset: number,
): string {
  return Buffer.from(`${instance}|${snapshotId}|${offset}`, "utf8").toString(
    "base64url",
  );
}

function decodePageToken(
  token: string,
  serverInstanceId: string,
): { readonly snapshotId: string; readonly offset: number } {
  const raw = Buffer.from(token, "base64url").toString("utf8");
  const [instance, snapshotId, offsetText, extra] = raw.split("|");
  const offset = Number(offsetText);
  if (
    extra !== undefined ||
    instance !== serverInstanceId ||
    invalidUuidReason("snapshot_id", snapshotId ?? "") !== undefined ||
    !Number.isSafeInteger(offset) ||
    offset < 0 ||
    encodePageToken(instance ?? "", snapshotId ?? "", offset) !== token
  ) {
    throw updateFailedPrecondition(
      "the sync page token is invalid or belongs to another sidecar process",
      UpdateError.SNAPSHOT_INVALID,
    );
  }
  return { snapshotId: snapshotId as string, offset };
}

function requireConnection(authenticated: string, claimed: string): void {
  if (claimed !== authenticated)
    throw new ConnectError("no such connection", Code.NotFound);
}

function requireProtocol(version: number): void {
  if (version !== UPDATE_PROTOCOL_VERSION) {
    throw updateFailedPrecondition(
      `update protocol ${version} is not supported; this sidecar serves version ${UPDATE_PROTOCOL_VERSION}`,
      UpdateError.PROTOCOL_UNSUPPORTED,
      UPDATE_PROTOCOL_VERSION,
    );
  }
}

function updateFailedPrecondition(
  message: string,
  error: UpdateError,
  supportedProtocolVersion = 0,
): ConnectError {
  return new ConnectError(message, Code.FailedPrecondition, undefined, [
    {
      desc: UpdateErrorDetailSchema,
      value: { error, supportedProtocolVersion },
    },
  ]);
}

function requireOpaque(name: string, value: string): void {
  if (
    !value.isWellFormed() ||
    Buffer.byteLength(value, "utf8") > OPAQUE_MAX_BYTES
  ) {
    throw new ConnectError(
      `${name} exceeds its ${OPAQUE_MAX_BYTES}-byte limit`,
      Code.InvalidArgument,
    );
  }
}

function unauthenticated(): ConnectError {
  return new ConnectError(
    "a valid phone credential is required",
    Code.Unauthenticated,
  );
}

function isCursorError(error: unknown): boolean {
  return (
    error instanceof InvalidCursor ||
    error instanceof CursorForAnotherConnection ||
    error instanceof CursorForAnotherInstance
  );
}

function throwIfAborted(signal: AbortSignal): void {
  if (!signal.aborted) return;
  throw signal.reason instanceof Error
    ? signal.reason
    : new ConnectError("the update stream was canceled", Code.Canceled);
}
