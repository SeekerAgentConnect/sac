/**
 * Request identity (docs/protocol.md). A reference is scoped by connection, and an idempotency
 * key names one attempt at creating a request. Pure code; SAW-010 stores what these rules decide.
 */
import { createHash } from "node:crypto";

import { toBinary } from "@bufbuild/protobuf";

import {
  ActionSchema,
  RequestError,
  type Action,
  type RequestRef,
} from "../gen/seekervault/request/v1/request_pb.js";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const IDEMPOTENCY_KEY = /^[A-Za-z0-9._:-]{1,128}$/;

/** A request's two IDs: a RequestRef, or any object with the same fields. */
type RefIds = Pick<RequestRef, "connectionId" | "requestId">;

/** Says why `ref` is malformed. Both IDs are required, as lowercase UUIDs. */
export function invalidRefReason(ref: RefIds | undefined): string | undefined {
  if (ref === undefined) return "ref is missing";
  return (
    invalidUuidReason("ref.connection_id", ref.connectionId) ??
    invalidUuidReason("ref.request_id", ref.requestId)
  );
}

export type RefCheck =
  | { readonly ok: true }
  | {
      readonly ok: false;
      readonly error: RequestError.INVALID_PARAMETERS | RequestError.NOT_FOUND;
      readonly message: string;
    };

/**
 * Checks a reference from the phone against `connectionId`, the connection its credential
 * belongs to. A reference that names another connection gets NOT_FOUND, the same answer as for a
 * request that doesn't exist, even when this connection has a request with the same ID.
 */
export function checkRef(
  ref: RefIds | undefined,
  connectionId: string,
): RefCheck {
  const reason = invalidRefReason(ref);
  if (reason !== undefined) {
    return {
      ok: false,
      error: RequestError.INVALID_PARAMETERS,
      message: reason,
    };
  }
  return ref?.connectionId === connectionId
    ? { ok: true }
    : { ok: false, error: RequestError.NOT_FOUND, message: "no such request" };
}

/**
 * Says why `key` can't be an idempotency key. It must be 1 to 128 characters from A-Z, a-z,
 * 0-9, ".", "_", ":", and "-".
 */
export function invalidIdempotencyKeyReason(key: string): string | undefined {
  if (key === "") return "idempotency_key is missing";
  return IDEMPOTENCY_KEY.test(key)
    ? undefined
    : `idempotency_key must be 1 to 128 characters from A-Z, a-z, 0-9, ".", "_", ":", and "-"`;
}

/**
 * Fingerprints an action's parameters: the SHA-256, in hex, of its deterministic binary encoding
 * (fields in number order, unknown fields dropped). Two actions share a fingerprint exactly when
 * every parameter is equal, down to each byte of a message and each digit of an amount. The
 * agent's note and the request's lifetime aren't parameters, so they aren't included.
 */
export function actionFingerprint(action: Action): string {
  const bytes = toBinary(ActionSchema, action, { writeUnknownFields: false });
  return createHash("sha256").update(bytes).digest("hex");
}

/** What an idempotency key already names: the request it created, and its action's fingerprint. */
export interface IdempotencyRecord {
  readonly requestId: string;
  readonly fingerprint: string;
}

export type IdempotencyDecision =
  | { readonly kind: "create" }
  | { readonly kind: "replay"; readonly requestId: string }
  | { readonly kind: "conflict"; readonly requestId: string };

/**
 * Decides what a creation call does with its idempotency key. `existing` is the key's record,
 * looked up across the whole sidecar (the agent's scope), so that a retry after the phone re-paired
 * still finds the original.
 *
 * - A new key creates a request.
 * - The same key with the same parameters replays the original request, whatever its state now.
 * - The same key with different parameters is a conflict, and nothing is created.
 */
export function resolveIdempotency(
  existing: IdempotencyRecord | undefined,
  fingerprint: string,
): IdempotencyDecision {
  if (existing === undefined) return { kind: "create" };
  return existing.fingerprint === fingerprint
    ? { kind: "replay", requestId: existing.requestId }
    : { kind: "conflict", requestId: existing.requestId };
}

/** Says why `value`, the field named `field`, isn't a lowercase UUID. */
export function invalidUuidReason(
  field: string,
  value: string,
): string | undefined {
  if (value === "") return `${field} is missing`;
  return UUID.test(value) ? undefined : `${field} is not a lowercase UUID`;
}
