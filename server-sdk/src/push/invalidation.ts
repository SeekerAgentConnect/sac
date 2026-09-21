/**
 * Content-free FCM invalidations (SAW-056). A push is only a hint to run authenticated Sync: it
 * names no request, state, connection, credential, policy, message, transaction, or approval.
 */
import { CoalescingInvalidations } from "./coalescing.ts";
import type { PairingStore } from "../storage/pairing-store.ts";

/**
 * The Firebase-compatible, content-free shape an optional delivery adapter receives.
 *
 * The target is `fid`: a Firebase installation, which is what the phone's registration is and what
 * the Admin SDK sends when this field is set. It is not interchangeable with a gateway relay
 * handle — that one addresses an authorization at a gateway rather than a device, is useless
 * without that gateway's relay credential, and has a type and a field of its own (./relay.ts).
 */
export interface InvalidationMessage {
  readonly fid: string;
  readonly data: Readonly<Record<string, string>>;
  readonly notification?: never;
  readonly android: {
    readonly collapseKey: string;
    readonly priority: "high" | "normal";
    readonly ttl: number;
    readonly notification?: never;
  };
}

export interface InvalidationSender {
  send(message: InvalidationMessage): Promise<unknown>;
}

export const FCM_INVALIDATION_DATA = {
  kind: "request_invalidation",
  version: "1",
} as const;
export const FCM_INVALIDATION_COLLAPSE_KEY = "seeker-vault-request-state-v1";
export const FCM_INVALIDATION_TTL_MS = 5 * 60 * 1000;

/** The exact app-visible message. The target is an FCM routing field, never payload data. */
export function invalidationMessage(
  fid: string,
  timeSensitive: boolean,
): InvalidationMessage {
  return {
    fid,
    data: { ...FCM_INVALIDATION_DATA },
    android: {
      collapseKey: FCM_INVALIDATION_COLLAPSE_KEY,
      priority: timeSensitive ? "high" : "normal",
      ttl: FCM_INVALIDATION_TTL_MS,
    },
  };
}

/**
 * Coalesces committed changes by connection, then sends one best-effort hint to its current target.
 * A creation upgrades the whole batch to high priority; later state-only changes stay normal.
 */
export class FcmInvalidationDispatcher extends CoalescingInvalidations {
  readonly #pairing: PairingStore;
  readonly #sender: InvalidationSender;
  readonly #log: (message: string) => void;

  constructor(
    pairing: PairingStore,
    sender: InvalidationSender,
    log: (message: string) => void,
  ) {
    super();
    this.#pairing = pairing;
    this.#sender = sender;
    this.#log = log;
  }

  protected override async deliver(
    connectionId: string,
    timeSensitive: boolean,
  ): Promise<void> {
    const token = this.#pairing.fcmToken(connectionId);
    if (token === undefined) return;
    try {
      await this.#sender.send(invalidationMessage(token, timeSensitive));
    } catch (error) {
      if (invalidTargetError(error)) {
        // Rotation can race a failed send. Compare-clear only the value that Firebase rejected.
        this.#pairing.clearFcmToken(connectionId, token);
        this.#log(
          `FCM invalidation skipped for connection ${connectionId}: target is no longer valid`,
        );
      } else {
        // Firebase error messages can contain private deployment details, so classify only.
        this.#log(
          `FCM invalidation failed for connection ${connectionId}: delivery unavailable`,
        );
      }
    }
  }
}

/** Firebase's permanent registration errors; no supplied error text is ever returned or logged. */
export function invalidTargetError(error: unknown): boolean {
  if (typeof error !== "object" || error === null || !("code" in error)) {
    return false;
  }
  const code = Reflect.get(error, "code");
  return (
    code === "messaging/invalid-argument" ||
    code === "messaging/invalid-registration-token" ||
    code === "messaging/registration-token-not-registered" ||
    code === "messaging/installation-id-not-registered"
  );
}
