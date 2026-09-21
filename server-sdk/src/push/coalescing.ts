/**
 * The part of a push that is the same whichever way it is sent (SEE-144).
 *
 * A committed update is a fact about one connection — something changed, go and read it — and two
 * of them about the same connection are one thing for the phone to do. So the batching, the single
 * in-flight drain, the priority merge and the shutdown are here, once, and what differs between a
 * server's own Firebase project and the gateway relay is only where the wake-up goes.
 *
 * Extracted from FcmInvalidationDispatcher rather than copied. Two coalescers would be two answers
 * to "how often may a phone be woken", and the first time they disagreed the difference would show
 * up as a device being woken twice for one change.
 */
import type { CommittedRequestUpdate } from "../storage/update-store.ts";

export abstract class CoalescingInvalidations {
  readonly #pending = new Map<string, boolean>();
  #draining: Promise<void> | undefined;
  #closed = false;

  /**
   * Notes that a connection changed. A creation upgrades the whole batch to high priority; later
   * state-only changes stay normal, because a request the owner has already seen is not worth
   * waking a sleeping phone for.
   */
  invalidate(update: CommittedRequestUpdate): void {
    if (this.#closed) return;
    this.#pending.set(
      update.connectionId,
      (this.#pending.get(update.connectionId) ?? false) || update.timeSensitive,
    );
    this.#schedule();
  }

  /** Stops accepting work and lets already-queued sends settle before the transport shuts down. */
  async close(): Promise<void> {
    this.#closed = true;
    await this.#draining;
  }

  /**
   * Sends one wake-up for one connection. It must never throw: a push is a hint, and a request
   * that was committed and answered is not undone by a phone not hearing about it a moment sooner.
   */
  protected abstract deliver(
    connectionId: string,
    timeSensitive: boolean,
  ): Promise<void>;

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
          this.deliver(connectionId, timeSensitive),
        ),
      );
    }
  }
}
