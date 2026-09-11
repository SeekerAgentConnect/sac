/**
 * The durable request queue (docs/protocol.md): requests, the phone's accepted results, prepared
 * transactions, and idempotency records, in the sidecar's SQLite database. Each operation runs in
 * one IMMEDIATE transaction that commits before the operation returns, so no caller hears of a
 * change that isn't on disk. The rules come from action.ts, identity.ts, and lifecycle.ts, and
 * this module stores what they decide. Nothing here executes a request, and nothing runs on its
 * own after a restart.
 */
import { randomUUID } from "node:crypto";

import { clone, create, fromBinary, toBinary } from "@bufbuild/protobuf";
import { timestampFromMs } from "@bufbuild/protobuf/wkt";

import {
  ActionRequestSchema,
  ActionSchema,
  OutcomeSchema,
  PreparedTransactionSchema,
  RequestError,
  RequestState,
  type Action,
  type ActionRequest,
  type Outcome,
  type PreparedTransaction,
  type RequestRef,
} from "../gen/seekervault/request/v1/request_pb.js";
import {
  SubmitResultRequestSchema,
  type SubmitResultRequest,
} from "../gen/seekervault/request/v1/service_pb.js";
import {
  transaction,
  type DatabaseSync,
  type Row,
} from "../storage/database.ts";
import { invalidActionReason, invalidNoteReason } from "./action.ts";
import {
  actionFingerprint,
  checkRef,
  invalidIdempotencyKeyReason,
  invalidUuidReason,
  resolveIdempotency,
} from "./identity.ts";
import { canTransition, decideResult, type ActionKind } from "./lifecycle.ts";

/** The shortest and longest lifetime, in seconds, that an agent may ask for. */
export const MIN_EXPIRES_IN_SECONDS = 60;
export const MAX_EXPIRES_IN_SECONDS = 7 * 24 * 60 * 60;
/** ListPending's page size when the phone asks for 0, and the largest it may ask for. */
export const DEFAULT_PAGE_SIZE = 50;
export const MAX_PAGE_SIZE = 100;

const { PENDING, CANCELLED, EXPIRED } = RequestState;
// A PENDING request has no outcome yet, so expiry can give every one the same outcome.
const EXPIRED_OUTCOME = toBinary(
  OutcomeSchema,
  create(OutcomeSchema, {
    detail: "The request expired before the owner decided.",
  }),
);
const PAGE_TOKEN = /^(\d{1,15}):(.+)$/;

/**
 * A refused operation: the RequestError that MCP and Connect both report, and the request as it
 * is now, when there is one.
 */
export class RequestFailure extends Error {
  readonly error: RequestError;
  readonly request: ActionRequest | undefined;

  constructor(error: RequestError, message: string, request?: ActionRequest) {
    super(message);
    this.name = "RequestFailure";
    this.error = error;
    this.request = request;
  }

  /** The error's name as agents see it, for example `NOT_FOUND`. */
  get code(): string {
    return RequestError[this.error];
  }
}

export interface RequestStoreOptions {
  /** The lifetime of a request whose agent doesn't choose one (REQUEST_TTL_SECONDS). */
  readonly defaultTtlSeconds: number;
  /** The most PENDING requests one connection may have (REQUEST_PENDING_LIMIT). */
  readonly pendingLimit: number;
  /** The clock, in epoch milliseconds. Tests replace it. */
  readonly now?: () => number;
  /** New request and connection IDs. Tests replace it. */
  readonly newId?: () => string;
}

export interface NewRequest {
  readonly action: Action;
  readonly agentNote: string;
  readonly idempotencyKey: string;
  /** If omitted, the store's default lifetime applies. */
  readonly expiresInSeconds?: number;
}

export interface Created {
  readonly request: ActionRequest;
  /** False when the idempotency key returned an existing request. */
  readonly created: boolean;
}

export interface Submitted {
  readonly request: ActionRequest;
  /** True when the same result had already been accepted, so nothing changed. */
  readonly duplicate: boolean;
}

export interface PendingQuery {
  readonly connectionId: string;
  readonly pageSize: number;
  readonly pageToken: string;
}

export interface PendingPage {
  readonly requests: ActionRequest[];
  /** Empty on the last page. */
  readonly nextPageToken: string;
}

export class RequestStore {
  readonly #db: DatabaseSync;
  readonly #defaultTtlSeconds: number;
  readonly #pendingLimit: number;
  readonly #now: () => number;
  readonly #newId: () => string;

  constructor(db: DatabaseSync, options: RequestStoreOptions) {
    this.#db = db;
    this.#defaultTtlSeconds = options.defaultTtlSeconds;
    this.#pendingLimit = options.pendingLimit;
    this.#now = options.now ?? (() => Date.now());
    this.#newId = options.newId ?? randomUUID;
  }

  /**
   * The paired phone's connection, which new requests are bound to. There's at most one, because
   * pairing a phone revokes the previous one (pairing/store.ts).
   */
  activeConnection(): string | undefined {
    const row = this.#db
      .prepare(
        `SELECT connection_id FROM connections
         WHERE revoked_at_ms IS NULL AND credential_hash IS NOT NULL
         ORDER BY created_at_ms DESC LIMIT 1`,
      )
      .get();
    return row === undefined ? undefined : text(row.connection_id);
  }

  /**
   * Stores a new request for the active connection, or answers a retry. The same idempotency key
   * with the same action returns the original request as it is now. With a different action, it
   * fails with IDEMPOTENCY_CONFLICT. Storing a request isn't approval: the owner decides later,
   * on the phone.
   */
  create(request: NewRequest): Created {
    const reason =
      invalidIdempotencyKeyReason(request.idempotencyKey) ??
      invalidActionReason(request.action) ??
      invalidNoteReason("note", request.agentNote) ??
      invalidLifetimeReason(request.expiresInSeconds);
    if (reason !== undefined) {
      throw new RequestFailure(RequestError.INVALID_PARAMETERS, reason);
    }
    const kind = request.action.kind.case;
    if (kind !== "ack") {
      throw new RequestFailure(
        RequestError.WALLET_MISMATCH,
        `${snakeCase(kind ?? "")} requests need a connected wallet, and wallets connect from Stage 3`,
      );
    }
    const fingerprint = actionFingerprint(request.action);
    return transaction(this.#db, () => {
      const now = this.#now();
      this.#expireOverdue(now);
      const record = this.#db
        .prepare(
          "SELECT request_id, fingerprint FROM idempotency_keys WHERE idempotency_key = ?",
        )
        .get(request.idempotencyKey);
      const decision = resolveIdempotency(
        record === undefined
          ? undefined
          : {
              requestId: text(record.request_id),
              fingerprint: text(record.fingerprint),
            },
        fingerprint,
      );
      if (decision.kind === "replay") {
        return { request: this.#find(decision.requestId), created: false };
      }
      if (decision.kind === "conflict") {
        throw new RequestFailure(
          RequestError.IDEMPOTENCY_CONFLICT,
          `idempotency_key "${request.idempotencyKey}" was already used for request ${decision.requestId} with different parameters`,
        );
      }
      const connectionId = this.activeConnection();
      if (connectionId === undefined) {
        throw new RequestFailure(
          RequestError.NOT_PAIRED,
          "no phone is paired with this sidecar",
        );
      }
      const pending = integer(
        this.#db
          .prepare(
            "SELECT count(*) AS n FROM requests WHERE connection_id = ? AND state = ?",
          )
          .get(connectionId, PENDING)?.n,
      );
      if (pending >= this.#pendingLimit) {
        throw new RequestFailure(
          RequestError.PENDING_LIMIT,
          `the phone already has ${pending} pending requests, which is the limit; wait for the owner to decide some, or cancel ones you no longer need`,
        );
      }
      const requestId = this.#newId();
      const lifetime = request.expiresInSeconds ?? this.#defaultTtlSeconds;
      this.#db
        .prepare(
          `INSERT INTO requests (request_id, connection_id, kind, action, agent_note, state,
             created_at_ms, expires_at_ms, updated_at_ms)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        )
        .run(
          requestId,
          connectionId,
          kind,
          toBinary(ActionSchema, request.action, { writeUnknownFields: false }),
          request.agentNote,
          PENDING,
          now,
          now + lifetime * 1000,
          now,
        );
      this.#db
        .prepare(
          "INSERT INTO idempotency_keys (idempotency_key, fingerprint, request_id, created_at_ms) VALUES (?, ?, ?, ?)",
        )
        .run(request.idempotencyKey, fingerprint, requestId, now);
      return { request: this.#find(requestId), created: true };
    });
  }

  /** The request as it is now, for the agent (vault_get_request). */
  get(requestId: string): ActionRequest {
    requireUuid("request_id", requestId);
    return transaction(this.#db, () => {
      this.#expireOverdue(this.#now());
      return this.#find(requestId);
    });
  }

  /**
   * Withdraws a PENDING request for the agent (vault_cancel_request). A request that is already
   * cancelled comes back unchanged. A request in any other state fails with INVALID_STATE.
   */
  cancel(requestId: string): ActionRequest {
    requireUuid("request_id", requestId);
    return transaction(this.#db, () => {
      const now = this.#now();
      this.#expireOverdue(now);
      const current = this.#find(requestId);
      if (current.state === CANCELLED) return current;
      if (!canTransition(kindOf(current), current.state, CANCELLED, "agent")) {
        throw new RequestFailure(
          RequestError.INVALID_STATE,
          `the request is ${RequestState[current.state]}, so it can no longer be cancelled`,
          current,
        );
      }
      this.#move(
        requestId,
        current.state,
        CANCELLED,
        now,
        withDetail(current.outcome, "The agent cancelled the request."),
      );
      return this.#find(requestId);
    });
  }

  /** One of the connection's requests, in any state (GetRequest). */
  getForConnection(
    connectionId: string,
    ref: RequestRef | undefined,
  ): ActionRequest {
    const requestId = requireRef(connectionId, ref);
    return transaction(this.#db, () => {
      this.#expireOverdue(this.#now());
      return this.#find(requestId, connectionId);
    });
  }

  /**
   * The connection's PENDING requests, oldest first (by created_at, then request_id). The page
   * token names the last request returned, so paging never repeats a request, and never skips one
   * that stays PENDING throughout.
   */
  listPending(connectionId: string, query: PendingQuery): PendingPage {
    requireUuid("connection_id", query.connectionId);
    if (query.connectionId !== connectionId) {
      throw new RequestFailure(RequestError.NOT_FOUND, "no such connection");
    }
    if (query.pageSize > MAX_PAGE_SIZE) {
      throw new RequestFailure(
        RequestError.INVALID_PARAMETERS,
        `page_size must be 1 to ${MAX_PAGE_SIZE}, or 0 for the default of ${DEFAULT_PAGE_SIZE}`,
      );
    }
    const size = query.pageSize === 0 ? DEFAULT_PAGE_SIZE : query.pageSize;
    const after =
      query.pageToken === "" ? undefined : parsePageToken(query.pageToken);
    return transaction(this.#db, () => {
      this.#expireOverdue(this.#now());
      const rows =
        after === undefined
          ? this.#db
              .prepare(
                "SELECT * FROM requests WHERE connection_id = ? AND state = ? ORDER BY created_at_ms, request_id LIMIT ?",
              )
              .all(connectionId, PENDING, size + 1)
          : this.#db
              .prepare(
                `SELECT * FROM requests WHERE connection_id = ? AND state = ?
                   AND (created_at_ms > ? OR (created_at_ms = ? AND request_id > ?))
                 ORDER BY created_at_ms, request_id LIMIT ?`,
              )
              .all(
                connectionId,
                PENDING,
                after.createdAtMs,
                after.createdAtMs,
                after.requestId,
                size + 1,
              );
      const page = rows.slice(0, size);
      const last = rows.length > size ? page.at(-1) : undefined;
      return {
        requests: page.map(toRequest),
        nextPageToken:
          last === undefined
            ? ""
            : pageToken(integer(last.created_at_ms), text(last.request_id)),
      };
    });
  }

  /**
   * Applies a result from the phone (SubmitResult), and returns the request as it is afterwards.
   * A result identical to one already accepted changes nothing. Any other result must be one the
   * lifecycle allows from the current state, so a result that conflicts with how the request
   * ended fails with INVALID_STATE.
   */
  submit(connectionId: string, submission: SubmitResultRequest): Submitted {
    const requestId = requireRef(connectionId, submission.ref);
    const result = toBinary(
      SubmitResultRequestSchema,
      create(SubmitResultRequestSchema, { result: submission.result }),
      { writeUnknownFields: false },
    );
    return transaction(this.#db, () => {
      const now = this.#now();
      this.#expireOverdue(now);
      const current = this.#find(requestId, connectionId);
      const repeat = this.#db
        .prepare(
          "SELECT 1 AS found FROM results WHERE request_id = ? AND result = ?",
        )
        .get(requestId, result);
      if (repeat !== undefined) return { request: current, duplicate: true };
      const decision = decideResult(
        current,
        submission,
        this.#latestPrepared(requestId),
      );
      if (!decision.ok) {
        throw new RequestFailure(decision.error, decision.message, current);
      }
      this.#move(
        requestId,
        current.state,
        decision.to,
        now,
        outcomeAfter(current, submission),
      );
      this.#db
        .prepare(
          `INSERT INTO results (request_id, seq, result, from_state, to_state, created_at_ms)
           VALUES (?, (SELECT coalesce(max(seq), 0) + 1 FROM results WHERE request_id = ?), ?, ?, ?, ?)`,
        )
        .run(requestId, requestId, result, current.state, decision.to, now);
      return { request: this.#find(requestId), duplicate: false };
    });
  }

  /** Moves every PENDING request whose deadline has passed to EXPIRED. Every operation runs it first. */
  #expireOverdue(now: number): void {
    this.#db
      .prepare(
        "UPDATE requests SET state = ?, outcome = ?, updated_at_ms = ? WHERE state = ? AND expires_at_ms <= ?",
      )
      .run(EXPIRED, EXPIRED_OUTCOME, now, PENDING, now);
  }

  #move(
    requestId: string,
    from: RequestState,
    to: RequestState,
    now: number,
    outcome: Outcome | undefined,
  ): void {
    const { changes } = this.#db
      .prepare(
        "UPDATE requests SET state = ?, outcome = ?, updated_at_ms = ? WHERE request_id = ? AND state = ?",
      )
      .run(
        to,
        outcome === undefined ? null : toBinary(OutcomeSchema, outcome),
        now,
        requestId,
        from,
      );
    // The transaction holds the write lock, so the state read a moment ago can't have changed.
    if (Number(changes) !== 1) {
      throw new Error(
        `request ${requestId} left ${RequestState[from]} inside its own transaction`,
      );
    }
  }

  /** The stored request, or NOT_FOUND. With `connectionId`, only that connection's requests count. */
  #find(requestId: string, connectionId?: string): ActionRequest {
    const row =
      connectionId === undefined
        ? this.#db
            .prepare("SELECT * FROM requests WHERE request_id = ?")
            .get(requestId)
        : this.#db
            .prepare(
              "SELECT * FROM requests WHERE request_id = ? AND connection_id = ?",
            )
            .get(requestId, connectionId);
    if (row === undefined) {
      throw new RequestFailure(RequestError.NOT_FOUND, "no such request");
    }
    return toRequest(row);
  }

  #latestPrepared(requestId: string): PreparedTransaction | undefined {
    const row = this.#db
      .prepare(
        "SELECT prepared FROM prepared_transactions WHERE request_id = ? ORDER BY version DESC LIMIT 1",
      )
      .get(requestId);
    return row === undefined
      ? undefined
      : fromBinary(PreparedTransactionSchema, blob(row.prepared));
  }
}

function toRequest(row: Row): ActionRequest {
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

/** The outcome after an accepted result: its fields are added to, and never cleared. */
function outcomeAfter(
  current: ActionRequest,
  submission: SubmitResultRequest,
): Outcome | undefined {
  const { result } = submission;
  switch (result.case) {
    case "approval": {
      const outcome = copy(current.outcome);
      outcome.approval = result.value;
      return outcome;
    }
    case "messageSignature":
    case "transactionSubmission": {
      const outcome = copy(current.outcome);
      outcome.signature = result.value.signature;
      return outcome;
    }
    case "executionFailure":
    case "unknownOutcome":
      return withDetail(current.outcome, result.value.detail);
    case "rejection":
      return withDetail(
        current.outcome,
        current.state === PENDING
          ? "The owner rejected the request."
          : "The owner declined in the wallet.",
      );
    default:
      return current.outcome;
  }
}

function withDetail(outcome: Outcome | undefined, detail: string): Outcome {
  const updated = copy(outcome);
  updated.detail = detail;
  return updated;
}

function copy(outcome: Outcome | undefined): Outcome {
  return outcome === undefined
    ? create(OutcomeSchema)
    : clone(OutcomeSchema, outcome);
}

function kindOf(request: ActionRequest): ActionKind {
  const kind = request.action?.kind.case;
  if (kind === undefined)
    throw new Error("a stored request always has an action");
  return kind;
}

function invalidLifetimeReason(
  seconds: number | undefined,
): string | undefined {
  if (seconds === undefined) return undefined;
  return Number.isInteger(seconds) &&
    seconds >= MIN_EXPIRES_IN_SECONDS &&
    seconds <= MAX_EXPIRES_IN_SECONDS
    ? undefined
    : `expires_in_seconds must be a whole number from ${MIN_EXPIRES_IN_SECONDS} to ${MAX_EXPIRES_IN_SECONDS}`;
}

function requireUuid(field: string, value: string): void {
  const reason = invalidUuidReason(field, value);
  if (reason !== undefined) {
    throw new RequestFailure(RequestError.INVALID_PARAMETERS, reason);
  }
}

/** Checks the phone's reference against its connection, and returns the request ID. */
function requireRef(connectionId: string, ref: RequestRef | undefined): string {
  const check = checkRef(ref, connectionId);
  if (!check.ok) throw new RequestFailure(check.error, check.message);
  return ref?.requestId ?? "";
}

function pageToken(createdAtMs: number, requestId: string): string {
  return Buffer.from(`${createdAtMs}:${requestId}`).toString("base64url");
}

function parsePageToken(token: string): {
  readonly createdAtMs: number;
  readonly requestId: string;
} {
  const match = PAGE_TOKEN.exec(
    Buffer.from(token, "base64url").toString("utf8"),
  );
  const createdAtMs = Number(match?.[1]);
  const requestId = match?.[2] ?? "";
  if (
    invalidUuidReason("page_token", requestId) !== undefined ||
    pageToken(createdAtMs, requestId) !== token
  ) {
    throw new RequestFailure(
      RequestError.INVALID_PARAMETERS,
      "page_token isn't one this sidecar issued",
    );
  }
  return { createdAtMs, requestId };
}

function snakeCase(name: string): string {
  return name.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
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
