/**
 * Pairing and the phone's credentials (docs/security.md). The operator issues a one-use pairing
 * token (`pnpm pair`), and the phone exchanges it for a connection and a credential
 * (PairingService.Pair). The sidecar keeps only SHA-256 hashes of both. One phone is active at a
 * time: a new pairing or a revocation ends the previous connection and cancels its PENDING
 * requests.
 */
import { createHash, randomBytes, randomUUID } from "node:crypto";

import { create, toBinary } from "@bufbuild/protobuf";

import {
  OutcomeSchema,
  RequestError,
  RequestState,
} from "../gen/seekervault/request/v1/request_pb.js";
import { RequestFailure } from "../requests/failure.ts";
import { transaction, type DatabaseSync, type Row } from "./database.ts";
import { invalidServerUrlReason, normalizeServerUrl } from "../pairing/uri.ts";
import {
  recordConnectionRevoked,
  recordRequestUpdate,
} from "./update-store.ts";

/** The detail a request gets when its connection's revocation cancels it. */
export const REVOKED_DETAIL = "The phone's connection was revoked.";
const REVOKED_OUTCOME = toBinary(
  OutcomeSchema,
  create(OutcomeSchema, { detail: REVOKED_DETAIL }),
);
const MAX_DEVICE_NAME_BYTES = 128;
export const MAX_FCM_TOKEN_BYTES = 4096;
const { PENDING, CANCELLED } = RequestState;

export interface PairingStoreOptions {
  /** The clock, in epoch milliseconds. Tests replace it. */
  readonly now?: () => number;
  /** New connection and server IDs. Tests replace it. */
  readonly newId?: () => string;
}

/** The paired phone: its connection, the name it gave, and when it paired. */
export interface PairedPhone {
  readonly connectionId: string;
  readonly deviceName: string;
  readonly pairedAtMs: number;
}

/** A pairing token for the operator to show, as a pairing code. */
export interface IssuedToken {
  readonly token: string;
  readonly serverUrl: string;
  readonly serverId: string;
  readonly expiresAtMs: number;
  /** The phone that pairing with this token would replace, if one is paired now. */
  readonly replaces: PairedPhone | undefined;
}

export interface Pairing {
  readonly connectionId: string;
  /** The phone's credential. The sidecar returns it this once, and keeps only its hash. */
  readonly phoneToken: string;
  readonly serverId: string;
  /** The connections this pairing revoked. */
  readonly revoked: readonly string[];
}

export interface Revocation {
  /** False if the connection was unknown or already revoked. */
  readonly revoked: boolean;
  /** How many PENDING requests the revocation cancelled. */
  readonly cancelled: number;
}

export class PairingStore {
  readonly #db: DatabaseSync;
  readonly #now: () => number;
  readonly #newId: () => string;

  constructor(db: DatabaseSync, options: PairingStoreOptions = {}) {
    this.#db = db;
    this.#now = options.now ?? (() => Date.now());
    this.#newId = options.newId ?? randomUUID;
  }

  /** The sidecar's lasting ID. The first call creates it; it survives restarts and pairings. */
  serverId(): string {
    return transaction(this.#db, () => this.#serverId());
  }

  /**
   * The settings revision to publish for manifest content that fingerprints as [fingerprint]
   * (SEE-88). It is what a phone caches by, so it changes exactly when the content does: the same
   * fingerprint gets the same revision however often the sidecar restarts, and a different one
   * gets the next number up. It never goes backwards, which is what lets a phone refuse a manifest
   * that claims a revision older than the one it already holds.
   */
  settingsRevision(fingerprint: string): number {
    return transaction(this.#db, () => {
      this.#serverId(); // the singleton row exists from here on
      const row = this.#db
        .prepare(
          "SELECT manifest_revision, manifest_fingerprint FROM server WHERE singleton = 1",
        )
        .get();
      const stored = integer(row?.manifest_revision);
      if (stored > 0 && row?.manifest_fingerprint === fingerprint)
        return stored;
      const revision = stored + 1;
      this.#db
        .prepare(
          "UPDATE server SET manifest_revision = ?, manifest_fingerprint = ? WHERE singleton = 1",
        )
        .run(revision, fingerprint);
      return revision;
    });
  }

  /** The paired phone, if there is one. */
  activeConnection(): PairedPhone | undefined {
    const row = this.#db
      .prepare(
        `SELECT connection_id, device_name, created_at_ms FROM connections
         WHERE revoked_at_ms IS NULL AND credential_hash IS NOT NULL
         ORDER BY created_at_ms DESC LIMIT 1`,
      )
      .get();
    return row === undefined
      ? undefined
      : {
          connectionId: text(row.connection_id),
          deviceName: text(row.device_name),
          pairedAtMs: integer(row.created_at_ms),
        };
  }

  /**
   * Issues a one-use pairing token for `serverUrl`, valid for `ttlSeconds`. Unused tokens issued
   * before it stop working, so only the newest pairing code can pair.
   */
  issue(serverUrl: string, ttlSeconds: number): IssuedToken {
    const reason = invalidServerUrlReason(serverUrl);
    if (reason !== undefined) {
      throw new RequestFailure(RequestError.INVALID_PARAMETERS, reason);
    }
    const url = normalizeServerUrl(serverUrl);
    const token = newSecret();
    return transaction(this.#db, () => {
      const now = this.#now();
      this.#db
        .prepare(
          "UPDATE pairing_tokens SET expires_at_ms = ? WHERE used_at_ms IS NULL AND expires_at_ms > ?",
        )
        .run(now, now);
      const expiresAtMs = now + ttlSeconds * 1000;
      this.#db
        .prepare(
          "INSERT INTO pairing_tokens (token_hash, server_url, created_at_ms, expires_at_ms) VALUES (?, ?, ?, ?)",
        )
        .run(hash(token), url, now, expiresAtMs);
      return {
        token,
        serverUrl: url,
        serverId: this.#serverId(),
        expiresAtMs,
        replaces: this.activeConnection(),
      };
    });
  }

  /**
   * Exchanges a pairing token for a new connection and its credential. The token must be unused,
   * strictly before its expiry, and presented for the URL it was issued for. A new connection is
   * always created, and an existing one is never changed: the previous phone's connection is
   * revoked, and its PENDING requests are cancelled.
   *
   * An unknown, expired, or used token gets UNAUTHENTICATED, without saying which. A URL mismatch
   * gets INVALID_PARAMETERS and leaves the token usable.
   */
  pair(
    pairingToken: string | undefined,
    serverUrl: string,
    deviceName: string,
  ): Pairing {
    const reason = invalidDeviceNameReason(deviceName);
    if (reason !== undefined) {
      throw new RequestFailure(RequestError.INVALID_PARAMETERS, reason);
    }
    return transaction(this.#db, () => {
      const now = this.#now();
      const token =
        pairingToken === undefined
          ? undefined
          : this.#db
              .prepare(
                "SELECT server_url, expires_at_ms, used_at_ms FROM pairing_tokens WHERE token_hash = ?",
              )
              .get(hash(pairingToken));
      if (
        pairingToken === undefined ||
        token === undefined ||
        token.used_at_ms !== null ||
        integer(token.expires_at_ms) <= now
      ) {
        throw new RequestFailure(
          RequestError.UNAUTHENTICATED,
          "the pairing token is unknown, expired, or already used; show a new pairing code with pnpm pair",
        );
      }
      if (
        invalidServerUrlReason(serverUrl) !== undefined ||
        normalizeServerUrl(serverUrl) !== text(token.server_url)
      ) {
        throw new RequestFailure(
          RequestError.INVALID_PARAMETERS,
          "server_url isn't the URL this pairing code was issued for",
        );
      }
      const revoked = this.#activeConnectionIds().filter(
        (connectionId) => this.#revoke(connectionId, now).revoked,
      );
      const connectionId = this.#newId();
      const phoneToken = newSecret();
      this.#db
        .prepare(
          "INSERT INTO connections (connection_id, created_at_ms, credential_hash, device_name) VALUES (?, ?, ?, ?)",
        )
        .run(connectionId, now, hash(phoneToken), deviceName);
      this.#db
        .prepare(
          "UPDATE pairing_tokens SET used_at_ms = ?, connection_id = ? WHERE token_hash = ?",
        )
        .run(now, connectionId, hash(pairingToken));
      return {
        connectionId,
        phoneToken,
        serverId: this.#serverId(),
        revoked,
      };
    });
  }

  /** The connection whose live credential this is, or undefined for an unknown or revoked one. */
  authenticate(phoneToken: string | undefined): string | undefined {
    if (phoneToken === undefined || phoneToken === "") return undefined;
    const row = this.#db
      .prepare(
        "SELECT connection_id FROM connections WHERE credential_hash = ? AND revoked_at_ms IS NULL",
      )
      .get(hash(phoneToken));
    return row === undefined ? undefined : text(row.connection_id);
  }

  /** The active connection's current private FCM target, for the later push dispatcher. */
  fcmToken(connectionId: string): string | undefined {
    const row = this.#db
      .prepare(
        `SELECT fcm_token FROM connections
         WHERE connection_id = ? AND revoked_at_ms IS NULL`,
      )
      .get(connectionId);
    return row === undefined || row.fcm_token === null
      ? undefined
      : text(row.fcm_token);
  }

  /** Registers or atomically rotates one active connection's opaque FCM target. */
  setFcmToken(connectionId: string, token: string): boolean {
    if (invalidFcmTokenReason(token) !== undefined) {
      throw new RequestFailure(
        RequestError.INVALID_PARAMETERS,
        "fcm_token must be 1 to 4096 visible ASCII bytes",
      );
    }
    return transaction(this.#db, () => {
      const row = this.#db
        .prepare(
          `SELECT fcm_token FROM connections
           WHERE connection_id = ? AND revoked_at_ms IS NULL`,
        )
        .get(connectionId);
      if (row === undefined) {
        throw new RequestFailure(RequestError.NOT_FOUND, "no such connection");
      }
      if (row.fcm_token === token) return false;
      this.#db
        .prepare("UPDATE connections SET fcm_token = ? WHERE connection_id = ?")
        .run(token, connectionId);
      return true;
    });
  }

  /**
   * Clears a target only if it is still current. A late invalid-target result for an older send
   * therefore cannot erase a target the phone rotated to meanwhile.
   */
  clearFcmToken(connectionId: string, expectedToken: string): boolean {
    if (invalidFcmTokenReason(expectedToken) !== undefined) {
      throw new RequestFailure(
        RequestError.INVALID_PARAMETERS,
        "fcm_token must be 1 to 4096 visible ASCII bytes",
      );
    }
    return transaction(this.#db, () => {
      const { changes } = this.#db
        .prepare(
          `UPDATE connections SET fcm_token = NULL
           WHERE connection_id = ? AND revoked_at_ms IS NULL AND fcm_token = ?`,
        )
        .run(connectionId, expectedToken);
      return Number(changes) === 1;
    });
  }

  /**
   * Revokes a connection. Its credential stops working at once, and its PENDING requests are
   * cancelled. Requests the owner already approved stay as they are, and agents can still read
   * every request.
   */
  revoke(connectionId: string): Revocation {
    return transaction(this.#db, () => this.#revoke(connectionId, this.#now()));
  }

  #revoke(connectionId: string, now: number): Revocation {
    const { changes } = this.#db
      .prepare(
        `UPDATE connections SET revoked_at_ms = ?, fcm_token = NULL
         WHERE connection_id = ? AND revoked_at_ms IS NULL`,
      )
      .run(now, connectionId);
    if (Number(changes) === 0) return { revoked: false, cancelled: 0 };
    // Requests already past their deadline are left for the next operation to expire, since
    // expiry comes first (docs/protocol.md).
    const pending = this.#db
      .prepare(
        `SELECT request_id FROM requests
         WHERE connection_id = ? AND state = ? AND expires_at_ms > ? ORDER BY request_id`,
      )
      .all(connectionId, PENDING, now);
    const cancel = this.#db.prepare(
      "UPDATE requests SET state = ?, outcome = ?, updated_at_ms = ? WHERE request_id = ? AND state = ?",
    );
    let cancelled = 0;
    for (const row of pending) {
      const requestId = text(row.request_id);
      const result = cancel.run(
        CANCELLED,
        REVOKED_OUTCOME,
        now,
        requestId,
        PENDING,
      );
      if (Number(result.changes) !== 1) continue;
      recordRequestUpdate(this.#db, requestId, now);
      cancelled += 1;
    }
    recordConnectionRevoked(this.#db, connectionId, now);
    return { revoked: true, cancelled };
  }

  #activeConnectionIds(): string[] {
    return this.#db
      .prepare(
        "SELECT connection_id FROM connections WHERE revoked_at_ms IS NULL",
      )
      .all()
      .map((row) => text(row.connection_id));
  }

  #serverId(): string {
    const row = this.#db
      .prepare("SELECT server_id FROM server WHERE singleton = 1")
      .get();
    if (row !== undefined) return text(row.server_id);
    const serverId = this.#newId();
    this.#db
      .prepare(
        "INSERT INTO server (singleton, server_id, created_at_ms) VALUES (1, ?, ?)",
      )
      .run(serverId, this.#now());
    return serverId;
  }
}

/** Says why a phone's name can't be stored: it may be empty, but must be valid Unicode of at most 128 UTF-8 bytes. */
export function invalidDeviceNameReason(name: string): string | undefined {
  if (!name.isWellFormed()) {
    return "device_name is not valid Unicode (it contains an unpaired surrogate)";
  }
  const bytes = Buffer.byteLength(name, "utf8");
  return bytes > MAX_DEVICE_NAME_BYTES
    ? `device_name is ${bytes} UTF-8 bytes; the limit is ${MAX_DEVICE_NAME_BYTES}`
    : undefined;
}

/** FCM targets are opaque, bounded header-safe values. The reason never repeats the target. */
export function invalidFcmTokenReason(token: string): string | undefined {
  const bytes = Buffer.byteLength(token, "utf8");
  if (bytes < 1 || bytes > MAX_FCM_TOKEN_BYTES) return "invalid FCM target";
  for (let index = 0; index < token.length; index += 1) {
    const code = token.charCodeAt(index);
    if (code < 0x21 || code > 0x7e) return "invalid FCM target";
  }
  return undefined;
}

/** 32 random bytes in base64url: a pairing token or a phone credential. */
function newSecret(): string {
  return randomBytes(32).toString("base64url");
}

function hash(secret: string): Uint8Array {
  return createHash("sha256").update(secret, "utf8").digest();
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
