import assert from "node:assert/strict";
import { afterEach, beforeEach, describe, it, mock } from "node:test";

import { timestampFromMs } from "@bufbuild/protobuf/wkt";

import {
  LiveCommandError,
  type LiveCommand,
} from "../gen/seekervault/live/v1/live_pb.js";
import {
  LiveCommandBridge,
  LiveCommandFailure,
  type LiveWatch,
} from "./bridge.ts";

const NOON = Date.UTC(2026, 8, 11, 12);
const TIMEOUT_SECONDS = 60;

function bridge(log?: (message: string) => void): LiveCommandBridge {
  let next = 0;
  return new LiveCommandBridge({
    timeoutSeconds: TIMEOUT_SECONDS,
    newId: () => `c${++next}`,
    log,
  });
}

async function nextCommand(watch: LiveWatch): Promise<LiveCommand> {
  const next = await watch.commands().next();
  assert.equal(next.done, false, "expected a command");
  return next.value;
}

async function rejectsWith(
  promise: Promise<unknown>,
  reason: LiveCommandError,
): Promise<void> {
  await assert.rejects(promise, (error: unknown) => {
    assert.ok(error instanceof LiveCommandFailure);
    assert.equal(error.code, LiveCommandError[reason]);
    return true;
  });
}

describe("LiveCommandBridge", () => {
  beforeEach(() => {
    mock.timers.enable({ apis: ["setTimeout", "Date"], now: NOON });
  });
  afterEach(() => {
    mock.timers.reset();
  });

  it("delivers the command and resolves with its ID once acknowledged", async () => {
    const live = bridge();
    const watch = live.watch();
    const result = live.display("Hello Seeker");
    const command = await nextCommand(watch);
    assert.equal(command.id, "c1");
    assert.equal(command.text, "Hello Seeker");
    assert.deepEqual(
      command.expiresAt,
      timestampFromMs(NOON + TIMEOUT_SECONDS * 1000),
    );
    assert.deepEqual(live.acknowledge("c1"), { ok: true, duplicate: false });
    assert.deepEqual(await result, { id: "c1", result: "OK" });
  });

  it("fails at once with OFFLINE when no phone is watching", async () => {
    await rejectsWith(
      bridge().display("Hello Seeker"),
      LiveCommandError.OFFLINE,
    );
  });

  it("fails at once with INVALID_TEXT before looking for a phone", async () => {
    const live = bridge();
    live.watch();
    await rejectsWith(live.display(" "), LiveCommandError.INVALID_TEXT);
    await rejectsWith(
      live.display("a".repeat(4097)),
      LiveCommandError.INVALID_TEXT,
    );
  });

  it("answers BUSY while a command is in flight, then accepts the next one", async () => {
    const live = bridge();
    const watch = live.watch();
    const first = live.display("first");
    await rejectsWith(live.display("second"), LiveCommandError.BUSY);
    await nextCommand(watch);
    live.acknowledge("c1");
    await first;
    const third = live.display("third");
    const command = await nextCommand(watch);
    assert.equal(command.text, "third");
    live.acknowledge(command.id);
    assert.deepEqual(await third, { id: command.id, result: "OK" });
  });

  it("times out at the deadline and rejects a late acknowledgement", async () => {
    const live = bridge();
    const watch = live.watch();
    const result = live.display("Hello Seeker");
    await nextCommand(watch);
    mock.timers.tick(TIMEOUT_SECONDS * 1000 - 1);
    assert.equal(live.acknowledge("unknown").ok, false); // still waiting
    mock.timers.tick(1);
    await rejectsWith(result, LiveCommandError.TIMEOUT);
    assert.deepEqual(live.acknowledge("c1"), {
      ok: false,
      error: LiveCommandError.TIMEOUT,
    });
  });

  it("treats an acknowledgement at the deadline as too late, even before the timer fires", async () => {
    const live = bridge();
    const watch = live.watch();
    const result = live.display("Hello Seeker");
    await nextCommand(watch);
    mock.timers.setTime(NOON + TIMEOUT_SECONDS * 1000); // moves Date only
    assert.deepEqual(live.acknowledge("c1"), {
      ok: false,
      error: LiveCommandError.TIMEOUT,
    });
    await rejectsWith(result, LiveCommandError.TIMEOUT);
  });

  it("cancels when the agent aborts, and the phone's acknowledgement then fails", async () => {
    const live = bridge();
    const watch = live.watch();
    const abort = new AbortController();
    const result = live.display("Hello Seeker", abort.signal);
    await nextCommand(watch);
    abort.abort();
    await rejectsWith(result, LiveCommandError.CANCELLED);
    assert.deepEqual(live.acknowledge("c1"), {
      ok: false,
      error: LiveCommandError.CANCELLED,
    });
    await rejectsWith(
      live.display("already aborted", abort.signal),
      LiveCommandError.CANCELLED,
    );
  });

  it("cancels the command when the phone disconnects, and later calls fail as OFFLINE", async () => {
    const live = bridge();
    const watch = live.watch();
    const result = live.display("Hello Seeker");
    await nextCommand(watch);
    live.unwatch(watch);
    await rejectsWith(result, LiveCommandError.CANCELLED);
    assert.equal(live.watching, false);
    await rejectsWith(live.display("again"), LiveCommandError.OFFLINE);
  });

  it("never redelivers a command to a phone that reconnects", async () => {
    const live = bridge();
    const first = live.watch();
    const result = live.display("Hello Seeker");
    await nextCommand(first);
    const second = live.watch(); // a reconnect replaces the first stream
    await rejectsWith(result, LiveCommandError.CANCELLED);
    assert.deepEqual(await first.commands().next(), {
      done: true,
      value: "replaced",
    });
    live.unwatch(second);
    assert.deepEqual(await second.commands().next(), {
      done: true,
      value: "disconnected",
    });
  });

  it("does not expire a command early when the wall clock lags the timer", async () => {
    const live = bridge();
    const watch = live.watch();
    const result = live.display("Hello Seeker");
    await nextCommand(watch);
    mock.timers.tick(TIMEOUT_SECONDS * 1000 - 5);
    mock.timers.setTime(NOON); // the wall clock jumps back 60 s
    mock.timers.tick(5); // the original timer fires; Date says the deadline is 60 s away
    assert.deepEqual(live.acknowledge("c1"), { ok: true, duplicate: false });
    assert.deepEqual(await result, { id: "c1", result: "OK" });
  });

  it("cancels the in-flight command and ends the stream on shutdown", async () => {
    const live = bridge();
    const watch = live.watch();
    const result = live.display("Hello Seeker");
    await nextCommand(watch);
    live.shutdown();
    await rejectsWith(result, LiveCommandError.CANCELLED);
    assert.deepEqual(await watch.commands().next(), {
      done: true,
      value: "shutdown",
    });
    assert.deepEqual(await live.watch().commands().next(), {
      done: true,
      value: "shutdown",
    });
  });

  it("logs command IDs and sizes, never the text", async () => {
    const lines: string[] = [];
    const live = bridge((line) => lines.push(line));
    const watch = live.watch();
    const result = live.display("secret agent text");
    await nextCommand(watch);
    live.acknowledge("c1");
    await result;
    assert.deepEqual(lines, [
      "phone connected",
      "command c1 sent (17 bytes)",
      "command c1 acknowledged",
    ]);
  });
});
