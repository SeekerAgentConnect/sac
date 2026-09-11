import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { create } from "@bufbuild/protobuf";
import { TimestampSchema, timestampFromMs } from "@bufbuild/protobuf/wkt";

import { LiveCommandError } from "../gen/seekervault/live/v1/live_pb.js";
import {
  LiveCommandSlot,
  MAX_COMMAND_TEXT_BYTES,
  invalidTextReason,
  isExpired,
} from "./command.ts";

const NOON = Date.UTC(2026, 8, 11, 12); // 2026-09-11T12:00:00Z
const TIMEOUT_SECONDS = 60;
const DEADLINE = NOON + TIMEOUT_SECONDS * 1000;

function started(slot: LiveCommandSlot, id: string, nowMs = NOON): void {
  const result = slot.start(id, "Hello Seeker", nowMs, TIMEOUT_SECONDS);
  assert.ok(result.ok, `expected command ${id} to start`);
}

describe("invalidTextReason", () => {
  it("accepts well-formed Unicode text as is", () => {
    for (const text of [
      "Hello Seeker",
      "Привет 👋🏽 你好 مرحبا é 👩‍💻\nSecond line",
      "  surrounding spaces are kept  ",
    ]) {
      assert.equal(invalidTextReason(text), undefined, text);
    }
  });

  it("rejects empty and whitespace-only text", () => {
    // Ideographic space and no-break space count as whitespace too.
    for (const text of ["", " ", "\n\t ", "\u3000\u00a0"]) {
      assert.equal(invalidTextReason(text), "text is empty");
    }
  });

  it("limits text to 4096 UTF-8 bytes, counting each character by its encoded size", () => {
    assert.equal(
      invalidTextReason("a".repeat(MAX_COMMAND_TEXT_BYTES)),
      undefined,
    );
    assert.equal(invalidTextReason("€".repeat(1365) + "!"), undefined); // 4095 + 1 bytes
    const tooLong = "text is 4097 UTF-8 bytes; the limit is 4096";
    assert.equal(
      invalidTextReason("a".repeat(MAX_COMMAND_TEXT_BYTES + 1)),
      tooLong,
    );
    assert.equal(invalidTextReason("😀".repeat(1024) + "a"), tooLong); // 4-byte emoji
  });

  it("rejects unpaired surrogates, which UTF-8 cannot carry", () => {
    assert.match(
      invalidTextReason("broken \ud83d text") ?? "",
      /not valid Unicode/,
    );
  });
});

describe("isExpired", () => {
  it("treats the deadline instant itself as expired", () => {
    const expiresAt = timestampFromMs(DEADLINE);
    assert.equal(isExpired(expiresAt, DEADLINE - 1), false);
    assert.equal(isExpired(expiresAt, DEADLINE), true);
    assert.equal(isExpired(expiresAt, DEADLINE + 1), true);
  });

  it("compares sub-millisecond deadlines exactly", () => {
    // The LiveCommand/deadline_nanos fixture: 2026-09-11T12:00:00.999999999Z.
    const expiresAt = create(TimestampSchema, {
      seconds: BigInt(NOON / 1000),
      nanos: 999_999_999,
    });
    assert.equal(isExpired(expiresAt, NOON + 999), false);
    assert.equal(isExpired(expiresAt, NOON + 1000), true);
    assert.equal(isExpired(expiresAt, NOON - 1), false);
  });

  it("treats a command without a deadline as expired", () => {
    assert.equal(isExpired(undefined, NOON), true);
  });
});

describe("LiveCommandSlot", () => {
  it("starts a command that expires the configured timeout after now", () => {
    const slot = new LiveCommandSlot();
    const result = slot.start("c1", "Hello Seeker", NOON, TIMEOUT_SECONDS);
    assert.ok(result.ok);
    assert.equal(result.command.id, "c1");
    assert.equal(result.command.text, "Hello Seeker");
    assert.deepEqual(result.command.expiresAt, timestampFromMs(DEADLINE));
    assert.equal(slot.active, result.command);
  });

  it("holds one command at a time and answers BUSY for another", () => {
    const slot = new LiveCommandSlot();
    started(slot, "c1");
    assert.deepEqual(slot.start("c2", "Second", NOON + 1, TIMEOUT_SECONDS), {
      ok: false,
      error: LiveCommandError.BUSY,
      message: "another live command is in flight",
    });
    assert.equal(slot.active?.id, "c1");
  });

  it("rejects invalid text without occupying the slot", () => {
    const slot = new LiveCommandSlot();
    const result = slot.start("c1", "   ", NOON, TIMEOUT_SECONDS);
    assert.deepEqual(result, {
      ok: false,
      error: LiveCommandError.INVALID_TEXT,
      message: "text is empty",
    });
    assert.equal(slot.active, undefined);
  });

  it("acknowledges the in-flight command and frees the slot", () => {
    const slot = new LiveCommandSlot();
    started(slot, "c1");
    assert.deepEqual(slot.acknowledge("c1", DEADLINE - 1), {
      ok: true,
      duplicate: false,
    });
    assert.equal(slot.active, undefined);
    started(slot, "c2", DEADLINE);
  });

  it("answers a repeated acknowledgement with success and no second effect", () => {
    const slot = new LiveCommandSlot();
    started(slot, "c1");
    slot.acknowledge("c1", NOON + 1);
    assert.deepEqual(slot.acknowledge("c1", NOON + 2), {
      ok: true,
      duplicate: true,
    });
    assert.deepEqual(slot.acknowledge("c1", DEADLINE + 60_000), {
      ok: true,
      duplicate: true,
    });
  });

  it("rejects acknowledgements for unknown or older IDs", () => {
    const slot = new LiveCommandSlot();
    const unknown = { ok: false, error: LiveCommandError.UNKNOWN_COMMAND };
    assert.deepEqual(slot.acknowledge("c1", NOON), unknown);
    started(slot, "c1");
    assert.deepEqual(slot.acknowledge("not-c1", NOON), unknown);
    assert.deepEqual(slot.acknowledge("", NOON), unknown);
    slot.acknowledge("c1", NOON);
    started(slot, "c2");
    slot.acknowledge("c2", NOON);
    assert.deepEqual(slot.acknowledge("c1", NOON), unknown); // only the latest is remembered
  });

  it("times out an acknowledgement that arrives at the deadline", () => {
    const slot = new LiveCommandSlot();
    started(slot, "c1");
    const timeout = { ok: false, error: LiveCommandError.TIMEOUT };
    assert.deepEqual(slot.acknowledge("c1", DEADLINE), timeout);
    assert.equal(slot.active, undefined);
    assert.deepEqual(slot.acknowledge("c1", DEADLINE - 1), timeout); // the outcome is final
  });

  it("reports cancellation to a late acknowledgement", () => {
    const slot = new LiveCommandSlot();
    started(slot, "c1");
    assert.equal(slot.cancel("c2"), false);
    assert.equal(slot.cancel("c1"), true);
    assert.equal(slot.cancel("c1"), false);
    assert.deepEqual(slot.acknowledge("c1", NOON + 1), {
      ok: false,
      error: LiveCommandError.CANCELLED,
    });
  });

  it("frees the slot once the deadline passes, before any timer fires", () => {
    const slot = new LiveCommandSlot();
    started(slot, "c1");
    assert.equal(slot.expire(DEADLINE - 1), undefined);
    started(slot, "c2", DEADLINE); // c1 times out as c2 starts
    assert.deepEqual(slot.acknowledge("c1", DEADLINE), {
      ok: false,
      error: LiveCommandError.TIMEOUT,
    });
    assert.equal(slot.active?.id, "c2");
    assert.equal(slot.expire(DEADLINE + TIMEOUT_SECONDS * 1000)?.id, "c2");
    assert.equal(slot.active, undefined);
  });
});
