/**
 * Stage 1 live-command rules (docs/protocol.md), shared by the sidecar's Connect API
 * and MCP tool. Pure state: callers supply the clock and own the timers, streams, and
 * MCP calls.
 */
import { create } from "@bufbuild/protobuf";
import { timestampFromMs, type Timestamp } from "@bufbuild/protobuf/wkt";

import {
  LiveCommandError,
  LiveCommandSchema,
  type LiveCommand,
} from "../gen/seekervault/live/v1/live_pb.js";

/** Largest accepted command text, in UTF-8 bytes (its size on the wire). */
export const MAX_COMMAND_TEXT_BYTES = 4096;

/** Says why `text` cannot be sent as a live command, or returns undefined if it can. */
export function invalidTextReason(text: string): string | undefined {
  if (!text.isWellFormed()) {
    return "text is not valid Unicode (it contains an unpaired surrogate)";
  }
  if (text.trim() === "") return "text is empty";
  const bytes = Buffer.byteLength(text, "utf8");
  if (bytes > MAX_COMMAND_TEXT_BYTES) {
    return `text is ${bytes} UTF-8 bytes; the limit is ${MAX_COMMAND_TEXT_BYTES}`;
  }
  return undefined;
}

/**
 * Whether a command with this deadline has timed out at `nowMs` (epoch milliseconds).
 * A command is live strictly before `expiresAt` and timed out from that instant on;
 * one without a deadline is malformed and treated as timed out.
 */
export function isExpired(
  expiresAt: Timestamp | undefined,
  nowMs: number,
): boolean {
  if (expiresAt === undefined) return true;
  const nowSeconds = Math.floor(nowMs / 1000);
  if (BigInt(nowSeconds) !== expiresAt.seconds) {
    return BigInt(nowSeconds) > expiresAt.seconds;
  }
  return (nowMs - nowSeconds * 1000) * 1_000_000 >= expiresAt.nanos;
}

export type StartResult =
  | { readonly ok: true; readonly command: LiveCommand }
  | {
      readonly ok: false;
      readonly error: LiveCommandError.BUSY | LiveCommandError.INVALID_TEXT;
      readonly message: string;
    };

export type AcknowledgeResult =
  | { readonly ok: true; readonly duplicate: boolean }
  | {
      readonly ok: false;
      readonly error:
        | LiveCommandError.TIMEOUT
        | LiveCommandError.CANCELLED
        | LiveCommandError.UNKNOWN_COMMAND;
    };

type Outcome =
  "acknowledged" | LiveCommandError.TIMEOUT | LiveCommandError.CANCELLED;

/**
 * The sidecar's single in-flight live command, plus the outcome of the most recent
 * finished one so that a late or repeated acknowledgement gets a precise answer.
 * Nothing else is kept: no backlog, no replay, and a restart forgets both.
 */
export class LiveCommandSlot {
  #active: LiveCommand | undefined;
  #last: { readonly id: string; readonly outcome: Outcome } | undefined;

  /** The in-flight command, if any. It may be past its deadline until `expire()` runs. */
  get active(): LiveCommand | undefined {
    return this.#active;
  }

  /** Starts command `id`, due `timeoutSeconds` after `nowMs`, unless one is in flight. */
  start(
    id: string,
    text: string,
    nowMs: number,
    timeoutSeconds: number,
  ): StartResult {
    const reason = invalidTextReason(text);
    if (reason !== undefined) {
      return {
        ok: false,
        error: LiveCommandError.INVALID_TEXT,
        message: reason,
      };
    }
    this.expire(nowMs);
    if (this.#active !== undefined) {
      return {
        ok: false,
        error: LiveCommandError.BUSY,
        message: "another live command is in flight",
      };
    }
    const command = create(LiveCommandSchema, {
      id,
      text,
      expiresAt: timestampFromMs(nowMs + timeoutSeconds * 1000),
    });
    this.#active = command;
    return { ok: true, command };
  }

  /** Applies the phone's acknowledgement of command `id`, received at `nowMs`. */
  acknowledge(id: string, nowMs: number): AcknowledgeResult {
    if (this.#active?.id === id) {
      if (isExpired(this.#active.expiresAt, nowMs)) {
        this.#finish(LiveCommandError.TIMEOUT);
        return { ok: false, error: LiveCommandError.TIMEOUT };
      }
      this.#finish("acknowledged");
      return { ok: true, duplicate: false };
    }
    if (this.#last?.id === id) {
      const outcome = this.#last.outcome;
      return outcome === "acknowledged"
        ? { ok: true, duplicate: true }
        : { ok: false, error: outcome };
    }
    return { ok: false, error: LiveCommandError.UNKNOWN_COMMAND };
  }

  /** Ends in-flight command `id` early: the agent cancelled, the phone left, or shutdown. */
  cancel(id: string): boolean {
    if (this.#active?.id !== id) return false;
    this.#finish(LiveCommandError.CANCELLED);
    return true;
  }

  /** Times out the in-flight command if its deadline has passed, and returns it. */
  expire(nowMs: number): LiveCommand | undefined {
    const command = this.#active;
    if (command === undefined || !isExpired(command.expiresAt, nowMs))
      return undefined;
    this.#finish(LiveCommandError.TIMEOUT);
    return command;
  }

  #finish(outcome: Outcome): void {
    if (this.#active === undefined) return;
    this.#last = { id: this.#active.id, outcome };
    this.#active = undefined;
  }
}
