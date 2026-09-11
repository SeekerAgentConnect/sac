/**
 * The in-memory bridge between an agent's MCP call and the phone's live-test screen
 * (docs/protocol.md). It holds at most one phone stream and one in-flight command,
 * waits for the acknowledgement, and forgets everything when the process stops.
 */
import { randomUUID } from "node:crypto";

import { timestampMs } from "@bufbuild/protobuf/wkt";

import {
  LiveCommandError,
  type LiveCommand,
} from "../gen/seekervault/live/v1/live_pb.js";
import {
  LiveCommandSlot,
  invalidTextReason,
  type AcknowledgeResult,
} from "./command.ts";

/** A failed live command, named with the `LiveCommandError` reasons agents see. */
export class LiveCommandFailure extends Error {
  readonly reason: LiveCommandError;

  constructor(reason: LiveCommandError, message: string) {
    super(message);
    this.name = "LiveCommandFailure";
    this.reason = reason;
  }

  /** The reason's name as agents see it, for example `BUSY`. */
  get code(): string {
    return LiveCommandError[this.reason];
  }
}

/** The result of an acknowledged command, returned to the agent. */
export interface Acknowledgement {
  readonly id: string;
  readonly result: "OK";
}

/** Why a phone stream ended. */
export type WatchEnd = "replaced" | "disconnected" | "shutdown";

/** One phone stream. The bridge pushes commands in; the stream handler pulls them out. */
export class LiveWatch {
  readonly #queue: LiveCommand[] = [];
  #wake: (() => void) | undefined;
  #end: WatchEnd | undefined;

  /** Yields commands as they are sent, then returns why the stream ended. */
  async *commands(): AsyncGenerator<LiveCommand, WatchEnd> {
    for (;;) {
      const command = this.#queue.shift();
      if (command !== undefined) {
        yield command;
        continue;
      }
      if (this.#end !== undefined) return this.#end;
      await new Promise<void>((resolve) => {
        this.#wake = resolve;
      });
    }
  }

  deliver(command: LiveCommand): void {
    if (this.#end !== undefined) return;
    this.#queue.push(command);
    this.#wakeUp();
  }

  end(reason: WatchEnd): void {
    if (this.#end !== undefined) return;
    this.#end = reason;
    this.#queue.length = 0;
    this.#wakeUp();
  }

  #wakeUp(): void {
    const wake = this.#wake;
    this.#wake = undefined;
    wake?.();
  }
}

export interface LiveCommandBridgeOptions {
  /** Seconds each command waits for its acknowledgement (LIVE_COMMAND_TIMEOUT_SECONDS). */
  readonly timeoutSeconds: number;
  /** Receives one line per event. Lines carry command IDs and sizes, never text or tokens. */
  readonly log?: (message: string) => void;
  readonly newId?: () => string;
}

interface Pending {
  readonly command: LiveCommand;
  readonly watch: LiveWatch;
  timer: NodeJS.Timeout;
  readonly settle: (outcome: Acknowledgement | LiveCommandFailure) => void;
}

export class LiveCommandBridge {
  readonly #slot = new LiveCommandSlot();
  readonly #timeoutSeconds: number;
  readonly #log: (message: string) => void;
  readonly #newId: () => string;
  #watch: LiveWatch | undefined;
  #pending: Pending | undefined;
  #shutDown = false;

  constructor(options: LiveCommandBridgeOptions) {
    this.#timeoutSeconds = options.timeoutSeconds;
    this.#log = options.log ?? (() => undefined);
    this.#newId = options.newId ?? randomUUID;
  }

  /** Whether a phone stream is registered. */
  get watching(): boolean {
    return this.#watch !== undefined;
  }

  /**
   * Registers a phone stream. A newer stream replaces the current one: the older stream
   * ends as `replaced`, and a command delivered to it is cancelled.
   */
  watch(): LiveWatch {
    const watch = new LiveWatch();
    if (this.#shutDown) {
      watch.end("shutdown");
      return watch;
    }
    const previous = this.#watch;
    this.#watch = watch;
    if (previous === undefined) {
      this.#log("phone connected");
    } else {
      this.#cancelDeliveredTo(
        previous,
        "the phone connected again on a newer stream",
      );
      previous.end("replaced");
      this.#log("phone stream replaced by a newer one");
    }
    return watch;
  }

  /** Unregisters a phone stream that ended; a command delivered to it is cancelled. */
  unwatch(watch: LiveWatch): void {
    if (this.#watch === watch) {
      this.#watch = undefined;
      this.#cancelDeliveredTo(watch, "the phone disconnected");
      this.#log("phone disconnected");
    }
    watch.end("disconnected");
  }

  /**
   * Sends `text` to the phone and resolves once the user taps OK. Rejects with a
   * LiveCommandFailure: INVALID_TEXT, OFFLINE, or BUSY at once; later TIMEOUT or
   * CANCELLED (when `signal` aborts, the phone leaves, or the sidecar shuts down).
   */
  display(text: string, signal?: AbortSignal): Promise<Acknowledgement> {
    const invalid = invalidTextReason(text);
    if (invalid !== undefined)
      return failed(LiveCommandError.INVALID_TEXT, invalid);
    if (signal?.aborted)
      return failed(LiveCommandError.CANCELLED, "the agent cancelled the call");
    const watch = this.#watch;
    if (watch === undefined) {
      return failed(
        LiveCommandError.OFFLINE,
        "no phone is watching; open the live-test screen and connect",
      );
    }
    const started = this.#slot.start(
      this.#newId(),
      text,
      Date.now(),
      this.#timeoutSeconds,
    );
    if (!started.ok) return failed(started.error, started.message);

    const { command } = started;
    return new Promise((resolve, reject) => {
      const onAbort = (): void => {
        this.#cancel(command.id, "the agent cancelled the call");
      };
      this.#pending = {
        command,
        watch,
        timer: setTimeout(
          () => this.#onDeadline(command.id),
          msUntilDeadline(command),
        ),
        settle: (outcome) => {
          const pending = this.#pending;
          if (pending !== undefined) clearTimeout(pending.timer);
          signal?.removeEventListener("abort", onAbort);
          this.#pending = undefined;
          if (outcome instanceof LiveCommandFailure) reject(outcome);
          else resolve(outcome);
        },
      };
      signal?.addEventListener("abort", onAbort, { once: true });
      watch.deliver(command);
      this.#log(
        `command ${command.id} sent (${Buffer.byteLength(text, "utf8")} bytes)`,
      );
    });
  }

  /** Applies the phone's acknowledgement of command `id`; see LiveCommandSlot.acknowledge. */
  acknowledge(id: string): AcknowledgeResult {
    const result = this.#slot.acknowledge(id, Date.now());
    const pending = this.#pending;
    if (pending?.command.id === id) {
      if (result.ok) {
        pending.settle({ id, result: "OK" });
        this.#log(`command ${id} acknowledged`);
      } else if (result.error === LiveCommandError.TIMEOUT) {
        pending.settle(this.#timeoutFailure());
        this.#log(`command ${id} timed out`);
      }
    }
    return result;
  }

  /** Cancels the in-flight command and ends the phone stream; later calls fail at once. */
  shutdown(): void {
    this.#shutDown = true;
    const pending = this.#pending;
    if (pending !== undefined)
      this.#cancel(pending.command.id, "the sidecar is shutting down");
    this.#watch?.end("shutdown");
    this.#watch = undefined;
  }

  #onDeadline(id: string): void {
    const pending = this.#pending;
    if (pending?.command.id !== id) return;
    if (this.#slot.expire(Date.now()) === undefined) {
      // The wall clock is behind the timer's monotonic clock; wait for the deadline.
      pending.timer = setTimeout(
        () => this.#onDeadline(id),
        Math.max(1, msUntilDeadline(pending.command)),
      );
      return;
    }
    pending.settle(this.#timeoutFailure());
    this.#log(`command ${id} timed out`);
  }

  #cancel(id: string, why: string): void {
    const pending = this.#pending;
    if (pending?.command.id !== id || !this.#slot.cancel(id)) return;
    pending.settle(
      new LiveCommandFailure(
        LiveCommandError.CANCELLED,
        `the command was cancelled: ${why}`,
      ),
    );
    this.#log(`command ${id} cancelled: ${why}`);
  }

  #cancelDeliveredTo(watch: LiveWatch, why: string): void {
    const pending = this.#pending;
    if (pending?.watch === watch) this.#cancel(pending.command.id, why);
  }

  #timeoutFailure(): LiveCommandFailure {
    return new LiveCommandFailure(
      LiveCommandError.TIMEOUT,
      `no acknowledgement within ${this.#timeoutSeconds} seconds`,
    );
  }
}

function failed(reason: LiveCommandError, message: string): Promise<never> {
  return Promise.reject(new LiveCommandFailure(reason, message));
}

function msUntilDeadline(command: LiveCommand): number {
  return command.expiresAt === undefined
    ? 0
    : Math.max(0, timestampMs(command.expiresAt) - Date.now());
}
