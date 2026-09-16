/**
 * Durable production-update state (SAW-049). Request mutations and their serialized events share
 * one SQLite transaction; subscriptions are only readers and wakeups over this log. Frozen Sync
 * snapshots also live here so pagination is stable without retaining a large request set in RAM.
 */
import { randomUUID } from "node:crypto";

import { create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { timestampFromMs } from "@bufbuild/protobuf/wkt";

import {
  ActionRequestSchema,
  ActionSchema,
  OutcomeSchema,
  RequestRefSchema,
  RequestState,
  type ActionRequest,
  type RequestRef,
} from "../gen/seekervault/request/v1/request_pb.js";
import { transaction, type DatabaseSync, type Row } from "./database.ts";

export const DEFAULT_UPDATE_RETAINED_EVENTS = 512;
/** Active pagination renews this inactivity lease after every valid page. */
export const SNAPSHOT_TTL_MS = 2 * 60 * 1000;
const MAX_SNAPSHOTS_PER_CONNECTION = 4;
const SNAPSHOT_REQUEST = 1;
const SNAPSHOT_REMOVED = 2;
const wakeups = new WeakMap<DatabaseSync, Map<string, Set<() => void>>>();
const requestUpdateListeners = new WeakMap<
  DatabaseSync,
  Set<(update: CommittedRequestUpdate) => void>
>();

/** A content-free signal emitted only after its corresponding request event is durable. */
export interface CommittedRequestUpdate {
  readonly connectionId: string;
  /** Only a newly created PENDING request is eligible for high-priority delivery. */
  readonly timeSensitive: boolean;
}

export interface UpdateStoreOptions {
  /** Random for this sidecar process; cursors and snapshot tokens never survive a restart. */
  readonly serverInstanceId: string;
  readonly now?: () => number;
  readonly newId?: () => string;
}

export interface StoredUpdateEvent {
  readonly sequence: number;
  readonly kind: "request" | "revoked";
  readonly request?: ActionRequest;
  readonly revision?: bigint;
  readonly revokedAtMs?: number;
}

export type ReplayResult =
  | { readonly kind: "events"; readonly events: readonly StoredUpdateEvent[] }
  | { readonly kind: "gap" };

export interface SnapshotItem {
  readonly kind: "request" | "removed";
  readonly request?: ActionRequest;
  readonly ref?: RequestRef;
  readonly revision: bigint;
}

/** The update protocol's KnownRequest reduced to the storage fields the snapshot needs. */
export interface KnownStoredRequest {
  readonly ref: RequestRef;
  readonly revision: bigint;
}

export interface FrozenSnapshot {
  readonly id: string;
  readonly connectionId: string;
  readonly sequence: number;
  readonly total: number;
  readonly deferred: readonly RequestRef[];
}

export interface SnapshotPage {
  readonly snapshot: FrozenSnapshot;
  readonly items: readonly SnapshotItem[];
}

/** Appends the request's complete current form and increments its revision. Call inside a write transaction. */
export function recordRequestUpdate(
  db: DatabaseSync,
  requestId: string,
  now: number,
  retainedEvents = DEFAULT_UPDATE_RETAINED_EVENTS,
): number {
  const changed = db
    .prepare(
      "UPDATE requests SET update_revision = update_revision + 1 WHERE request_id = ? RETURNING connection_id, update_revision",
    )
    .get(requestId);
  if (changed === undefined)
    throw new Error(`no request ${requestId} to publish`);
  const connectionId = text(changed.connection_id);
  const revision = integer(changed.update_revision);
  const sequence = nextSequence(db, connectionId);
  const row = db
    .prepare("SELECT * FROM requests WHERE request_id = ?")
    .get(requestId);
  if (row === undefined)
    throw new Error(`request ${requestId} disappeared while publishing`);
  const request = requestFromRow(row);
  db.prepare(
    `INSERT INTO update_events
       (connection_id, sequence, kind, request_id, revision, payload, created_at_ms)
     VALUES (?, ?, 'request', ?, ?, ?, ?)`,
  ).run(
    connectionId,
    sequence,
    requestId,
    revision,
    toBinary(ActionRequestSchema, request),
    now,
  );
  prune(db, connectionId, sequence, retainedEvents);
  wakeAfterCommit(db, connectionId);
  notifyRequestAfterCommit(db, {
    connectionId,
    sequence,
    revision,
    timeSensitive: revision === 1 && request.state === RequestState.PENDING,
  });
  return revision;
}

/**
 * Observes committed request invalidations without exposing their request payloads. The callback
 * receives only routing urgency and the connection whose authoritative state changed.
 */
export function observeCommittedRequestUpdates(
  db: DatabaseSync,
  listener: (update: CommittedRequestUpdate) => void,
): () => void {
  const listeners = requestUpdateListeners.get(db) ?? new Set();
  listeners.add(listener);
  requestUpdateListeners.set(db, listeners);
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) requestUpdateListeners.delete(db);
  };
}

/** Appends the terminal connection event after revocation and its request cancellations commit together. */
export function recordConnectionRevoked(
  db: DatabaseSync,
  connectionId: string,
  now: number,
  retainedEvents = DEFAULT_UPDATE_RETAINED_EVENTS,
): void {
  const sequence = nextSequence(db, connectionId);
  db.prepare(
    `INSERT INTO update_events
       (connection_id, sequence, kind, created_at_ms)
     VALUES (?, ?, 'revoked', ?)`,
  ).run(connectionId, sequence, now);
  prune(db, connectionId, sequence, retainedEvents);
  wakeAfterCommit(db, connectionId);
}

export class UpdateStore {
  readonly #db: DatabaseSync;
  readonly #serverInstanceId: string;
  readonly #now: () => number;
  readonly #newId: () => string;

  constructor(db: DatabaseSync, options: UpdateStoreOptions) {
    this.#db = db;
    this.#serverInstanceId = options.serverInstanceId;
    this.#now = options.now ?? (() => Date.now());
    this.#newId = options.newId ?? randomUUID;
  }

  latestSequence(connectionId: string): number {
    return state(this.#db, connectionId).next;
  }

  cursor(
    connectionId: string,
    sequence = this.latestSequence(connectionId),
  ): string {
    return `${this.#serverInstanceId}:${connectionId}:${sequence}`;
  }

  parseCursor(connectionId: string, cursor: string): number {
    const instancePrefix = `${this.#serverInstanceId}:`;
    if (!cursor.startsWith(instancePrefix))
      throw new CursorForAnotherInstance();
    const prefix = `${instancePrefix}${connectionId}:`;
    if (!cursor.startsWith(prefix)) throw new CursorForAnotherConnection();
    const raw = cursor.slice(prefix.length);
    if (!/^(0|[1-9]\d{0,15})$/.test(raw)) throw new InvalidCursor();
    const sequence = Number(raw);
    if (!Number.isSafeInteger(sequence)) throw new InvalidCursor();
    return sequence;
  }

  replay(connectionId: string, after: number, through?: number): ReplayResult {
    const current = state(this.#db, connectionId);
    const end = through ?? current.next;
    if (after < current.pruned || after > current.next || end > current.next) {
      return { kind: "gap" };
    }
    const rows = this.#db
      .prepare(
        `SELECT * FROM update_events
         WHERE connection_id = ? AND sequence > ? AND sequence <= ?
         ORDER BY sequence`,
      )
      .all(connectionId, after, end);
    return { kind: "events", events: rows.map(eventFromRow) };
  }

  /**
   * Sleeps only while the durable sequence is unchanged. A committed mutation wakes the stream
   * immediately; the timeout exists for heartbeat, expiry, and liveness housekeeping, not update
   * discovery. JavaScript cannot interleave another synchronous SQLite commit between the latest
   * sequence check and waiter registration below.
   */
  waitForChange(
    connectionId: string,
    after: number,
    timeoutMs: number,
    signals: readonly AbortSignal[],
  ): Promise<void> {
    if (this.latestSequence(connectionId) > after) return Promise.resolve();
    const byConnection = wakeupMap(this.#db);
    const waiting = byConnection.get(connectionId) ?? new Set<() => void>();
    byConnection.set(connectionId, waiting);
    return new Promise((resolve) => {
      const finish = () => {
        clearTimeout(timer);
        waiting.delete(finish);
        if (waiting.size === 0) byConnection.delete(connectionId);
        for (const signal of signals) {
          signal.removeEventListener("abort", finish);
        }
        resolve();
      };
      const timer = setTimeout(finish, timeoutMs);
      timer.unref();
      waiting.add(finish);
      for (const signal of signals) {
        signal.addEventListener("abort", finish, { once: true });
      }
    });
  }

  /** Rotates a bounded confirmation window through the eligible records supplied by the phone. */
  chooseConfirmations<T>(
    connectionId: string,
    eligible: readonly T[],
    limit: number,
  ): {
    readonly selected: readonly T[];
    readonly deferred: readonly T[];
  } {
    if (eligible.length <= limit) return { selected: eligible, deferred: [] };
    return transaction(this.#db, () => {
      ensureState(this.#db, connectionId);
      const current = state(this.#db, connectionId);
      const start = current.confirmationOffset % eligible.length;
      const rotated = [...eligible.slice(start), ...eligible.slice(0, start)];
      const selected = rotated.slice(0, limit);
      const deferred = rotated.slice(limit);
      this.#db
        .prepare(
          "UPDATE update_state SET confirmation_offset = ? WHERE connection_id = ?",
        )
        .run((start + selected.length) % eligible.length, connectionId);
      return { selected, deferred };
    });
  }

  createSnapshot(
    connectionId: string,
    serverInstanceId: string,
    known: readonly KnownStoredRequest[],
    deferred: readonly RequestRef[],
  ): FrozenSnapshot {
    return transaction(this.#db, () => {
      const now = this.#now();
      this.#cleanSnapshots(now, serverInstanceId);
      const snapshotId = this.#newId();
      const sequence = state(this.#db, connectionId).next;
      this.#db
        .prepare(
          `INSERT INTO update_snapshots
             (snapshot_id, connection_id, server_instance_id, snapshot_sequence, expires_at_ms, created_at_ms)
           VALUES (?, ?, ?, ?, ?, ?)`,
        )
        .run(
          snapshotId,
          connectionId,
          serverInstanceId,
          sequence,
          now + SNAPSHOT_TTL_MS,
          now,
        );

      const knownById = new Map(
        known.map((item) => [item.ref?.requestId ?? "", item] as const),
      );
      const rows = this.#db
        .prepare(
          `SELECT * FROM requests
           WHERE connection_id = ? AND (state = ? OR request_id IN (${placeholders(knownById.size)}))
           ORDER BY created_at_ms, request_id`,
        )
        .all(connectionId, RequestState.PENDING, ...knownById.keys());
      let ordinal = 0;
      const found = new Set<string>();
      for (const row of rows) {
        const requestId = text(row.request_id);
        found.add(requestId);
        const revision = integer(row.update_revision);
        this.#insertSnapshotItem(
          snapshotId,
          ordinal++,
          SNAPSHOT_REQUEST,
          revision,
          toBinary(ActionRequestSchema, requestFromRow(row)),
        );
      }
      for (const [requestId, item] of knownById) {
        if (found.has(requestId)) continue;
        this.#insertSnapshotItem(
          snapshotId,
          ordinal++,
          SNAPSHOT_REMOVED,
          Number(item.revision + 1n),
          toBinary(RequestRefSchema, item.ref),
        );
      }
      const insertDeferred = this.#db.prepare(
        `INSERT INTO update_snapshot_deferred
           (snapshot_id, ordinal, connection_id, request_id) VALUES (?, ?, ?, ?)`,
      );
      deferred.forEach((ref, index) =>
        insertDeferred.run(snapshotId, index, ref.connectionId, ref.requestId),
      );
      this.#boundSnapshots(connectionId);
      return {
        id: snapshotId,
        connectionId,
        sequence,
        total: ordinal,
        deferred,
      };
    });
  }

  snapshotPage(
    snapshotId: string,
    connectionId: string,
    serverInstanceId: string,
    offset: number,
    limit: number,
  ): SnapshotPage {
    return transaction(this.#db, () => {
      const now = this.#now();
      this.#cleanSnapshots(now, serverInstanceId);
      const row = this.#db
        .prepare("SELECT * FROM update_snapshots WHERE snapshot_id = ?")
        .get(snapshotId);
      if (row === undefined) throw new InvalidSnapshot();
      if (text(row.connection_id) !== connectionId)
        throw new SnapshotForAnotherConnection();
      if (
        text(row.server_instance_id) !== serverInstanceId ||
        integer(row.expires_at_ms) <= now
      ) {
        throw new InvalidSnapshot();
      }
      const total = integer(
        this.#db
          .prepare(
            "SELECT count(*) AS n FROM update_snapshot_items WHERE snapshot_id = ?",
          )
          .get(snapshotId)?.n,
      );
      if (offset < 0 || offset > total) throw new InvalidSnapshot();
      this.#db
        .prepare(
          "UPDATE update_snapshots SET expires_at_ms = ? WHERE snapshot_id = ?",
        )
        .run(now + SNAPSHOT_TTL_MS, snapshotId);
      const items = this.#db
        .prepare(
          `SELECT kind, revision, payload FROM update_snapshot_items
           WHERE snapshot_id = ? AND ordinal >= ? ORDER BY ordinal LIMIT ?`,
        )
        .all(snapshotId, offset, limit)
        .map(snapshotItemFromRow);
      const deferred = this.#db
        .prepare(
          `SELECT connection_id, request_id FROM update_snapshot_deferred
           WHERE snapshot_id = ? ORDER BY ordinal`,
        )
        .all(snapshotId)
        .map((item) =>
          create(RequestRefSchema, {
            connectionId: text(item.connection_id),
            requestId: text(item.request_id),
          }),
        );
      return {
        snapshot: {
          id: snapshotId,
          connectionId,
          sequence: integer(row.snapshot_sequence),
          total,
          deferred,
        },
        items,
      };
    });
  }

  #insertSnapshotItem(
    snapshotId: string,
    ordinal: number,
    kind: number,
    revision: number,
    payload: Uint8Array,
  ): void {
    this.#db
      .prepare(
        "INSERT INTO update_snapshot_items (snapshot_id, ordinal, kind, revision, payload) VALUES (?, ?, ?, ?, ?)",
      )
      .run(snapshotId, ordinal, kind, revision, payload);
  }

  #cleanSnapshots(now: number, serverInstanceId: string): void {
    this.#db
      .prepare(
        "DELETE FROM update_snapshots WHERE expires_at_ms <= ? OR server_instance_id != ?",
      )
      .run(now, serverInstanceId);
  }

  #boundSnapshots(connectionId: string): void {
    this.#db
      .prepare(
        `DELETE FROM update_snapshots WHERE snapshot_id IN (
           SELECT snapshot_id FROM update_snapshots WHERE connection_id = ?
           ORDER BY created_at_ms DESC, snapshot_id DESC LIMIT -1 OFFSET ?
         )`,
      )
      .run(connectionId, MAX_SNAPSHOTS_PER_CONNECTION);
  }
}

export class InvalidCursor extends Error {}
export class CursorForAnotherInstance extends Error {}
export class CursorForAnotherConnection extends Error {}
export class InvalidSnapshot extends Error {}
export class SnapshotForAnotherConnection extends Error {}

function ensureState(db: DatabaseSync, connectionId: string): void {
  db.prepare(
    "INSERT OR IGNORE INTO update_state (connection_id) VALUES (?)",
  ).run(connectionId);
}

function state(
  db: DatabaseSync,
  connectionId: string,
): {
  readonly next: number;
  readonly pruned: number;
  readonly confirmationOffset: number;
} {
  ensureState(db, connectionId);
  const row = db
    .prepare(
      "SELECT next_sequence, pruned_through, confirmation_offset FROM update_state WHERE connection_id = ?",
    )
    .get(connectionId);
  if (row === undefined) throw new Error(`no update state for ${connectionId}`);
  return {
    next: integer(row.next_sequence),
    pruned: integer(row.pruned_through),
    confirmationOffset: integer(row.confirmation_offset),
  };
}

function nextSequence(db: DatabaseSync, connectionId: string): number {
  ensureState(db, connectionId);
  const row = db
    .prepare(
      "UPDATE update_state SET next_sequence = next_sequence + 1 WHERE connection_id = ? RETURNING next_sequence",
    )
    .get(connectionId);
  return integer(row?.next_sequence);
}

function prune(
  db: DatabaseSync,
  connectionId: string,
  latest: number,
  retainedEvents: number,
): void {
  if (!Number.isInteger(retainedEvents) || retainedEvents < 1) {
    throw new Error("retained update events must be a positive integer");
  }
  const through = latest - retainedEvents;
  if (through <= 0) return;
  db.prepare(
    "DELETE FROM update_events WHERE connection_id = ? AND sequence <= ?",
  ).run(connectionId, through);
  db.prepare(
    `UPDATE update_state SET pruned_through = max(pruned_through, ?)
     WHERE connection_id = ?`,
  ).run(through, connectionId);
}

function wakeupMap(db: DatabaseSync): Map<string, Set<() => void>> {
  const existing = wakeups.get(db);
  if (existing !== undefined) return existing;
  const created = new Map<string, Set<() => void>>();
  wakeups.set(db, created);
  return created;
}

/**
 * Every caller writes in a synchronous SQLite transaction. A microtask therefore runs only after
 * COMMIT (or after rollback, where it becomes a harmless wake with no new durable sequence).
 */
function wakeAfterCommit(db: DatabaseSync, connectionId: string): void {
  queueMicrotask(() => {
    const waiting = wakeups.get(db)?.get(connectionId);
    if (waiting === undefined) return;
    for (const wake of [...waiting]) wake();
  });
}

/** A rolled-back event has no matching row, so its queued callback emits nothing. */
function notifyRequestAfterCommit(
  db: DatabaseSync,
  update: CommittedRequestUpdate & {
    readonly sequence: number;
    readonly revision: number;
  },
): void {
  queueMicrotask(() => {
    let committed: boolean;
    try {
      committed =
        db
          .prepare(
            `SELECT 1 AS found FROM update_events
             WHERE connection_id = ? AND sequence = ? AND revision = ? AND kind = 'request'`,
          )
          .get(update.connectionId, update.sequence, update.revision) !==
        undefined;
    } catch {
      // Closing a sidecar can close SQLite before an already-queued best-effort notification.
      return;
    }
    if (!committed) return;
    const listeners = requestUpdateListeners.get(db);
    if (listeners === undefined) return;
    const notification: CommittedRequestUpdate = {
      connectionId: update.connectionId,
      timeSensitive: update.timeSensitive,
    };
    for (const listener of [...listeners]) listener(notification);
  });
}

function eventFromRow(row: Row): StoredUpdateEvent {
  const kind = text(row.kind);
  if (kind === "revoked") {
    return {
      sequence: integer(row.sequence),
      kind,
      revokedAtMs: integer(row.created_at_ms),
    };
  }
  if (kind !== "request") throw new Error(`unknown update event kind ${kind}`);
  return {
    sequence: integer(row.sequence),
    kind,
    request: fromBinary(ActionRequestSchema, blob(row.payload)),
    revision: BigInt(integer(row.revision)),
  };
}

function snapshotItemFromRow(row: Row): SnapshotItem {
  const kind = integer(row.kind);
  if (kind === SNAPSHOT_REQUEST) {
    return {
      kind: "request",
      request: fromBinary(ActionRequestSchema, blob(row.payload)),
      revision: BigInt(integer(row.revision)),
    };
  }
  if (kind === SNAPSHOT_REMOVED) {
    return {
      kind: "removed",
      ref: fromBinary(RequestRefSchema, blob(row.payload)),
      revision: BigInt(integer(row.revision)),
    };
  }
  throw new Error(`unknown snapshot item kind ${kind}`);
}

function requestFromRow(row: Row): ActionRequest {
  return create(ActionRequestSchema, {
    ref: {
      connectionId: text(row.connection_id),
      requestId: text(row.request_id),
    },
    action: fromBinary(ActionSchema, blob(row.action)),
    agentNote: text(row.agent_note),
    state: integer(row.state),
    createdAt: timestampFromMs(integer(row.created_at_ms)),
    expiresAt: timestampFromMs(integer(row.expires_at_ms)),
    updatedAt: timestampFromMs(integer(row.updated_at_ms)),
    outcome:
      row.outcome === null
        ? undefined
        : fromBinary(OutcomeSchema, blob(row.outcome)),
  });
}

function placeholders(size: number): string {
  return size === 0
    ? "NULL"
    : Array.from({ length: size }, () => "?").join(", ");
}

function text(value: Row[string] | undefined): string {
  if (typeof value !== "string") throw new Error("expected a text column");
  return value;
}

function integer(value: Row[string] | undefined): number {
  if (typeof value === "number") return value;
  if (typeof value === "bigint") return Number(value);
  throw new Error("expected an integer column");
}

function blob(value: Row[string] | undefined): Uint8Array {
  if (!(value instanceof Uint8Array)) throw new Error("expected a blob column");
  return value;
}
