/**
 * Content-free FCM invalidations (SAW-056). A push is only a hint to run authenticated Sync: it
 * names no request, state, connection, credential, policy, message, transaction, or approval.
 */
import type { Message } from "firebase-admin/messaging";

import type { FcmSender } from "./fcm.ts";
import type { PairingStore } from "../storage/pairing-store.ts";
import type { CommittedRequestUpdate } from "../storage/update-store.ts";

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
): Message {
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
export class FcmInvalidationDispatcher {
  readonly #pairing: PairingStore;
  readonly #sender: Pick<FcmSender, "send">;
  readonly #log: (message: string) => void;
  readonly #pending = new Map<string, boolean>();
  #draining: Promise<void> | undefined;
  #closed = false;

  constructor(
    pairing: PairingStore,
    sender: Pick<FcmSender, "send">,
    log: (message: string) => void,
  ) {
    this.#pairing = pairing;
    this.#sender = sender;
    this.#log = log;
  }

  invalidate(update: CommittedRequestUpdate): void {
    if (this.#closed) return;
    this.#pending.set(
      update.connectionId,
      (this.#pending.get(update.connectionId) ?? false) || update.timeSensitive,
    );
    this.#schedule();
  }

  /** Stops accepting work and lets already-queued sends settle before Firebase shuts down. */
  async close(): Promise<void> {
    this.#closed = true;
    await this.#draining;
  }

  #schedule(): void {
    if (this.#draining !== undefined) return;
    this.#draining = Promise.resolve()
      .then(() => this.#drain())
      .finally(() => {
        this.#draining = undefined;
        if (!this.#closed && this.#pending.size > 0) this.#schedule();
      });
  }

  async #drain(): Promise<void> {
    while (this.#pending.size > 0) {
      const batch = [...this.#pending];
      this.#pending.clear();
      await Promise.all(
        batch.map(([connectionId, timeSensitive]) =>
          this.#send(connectionId, timeSensitive),
        ),
      );
    }
  }

  async #send(connectionId: string, timeSensitive: boolean): Promise<void> {
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
