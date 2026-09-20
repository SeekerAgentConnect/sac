import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { once } from "node:events";
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

import { acquireInstanceLock } from "./instance-lock.ts";

const LOCK_MODULE = fileURLToPath(
  new URL("./instance-lock.ts", import.meta.url),
);

function temporaryDatabase(): string {
  return join(mkdtempSync(join(tmpdir(), "mcp-lock-")), "direct.db");
}

async function startOwner(
  database: string,
): Promise<ChildProcessWithoutNullStreams> {
  const script = `
    import { acquireInstanceLock } from ${JSON.stringify(LOCK_MODULE)};
    const owner = acquireInstanceLock(process.argv[1]);
    console.log("owned");
    setInterval(() => owner.path, 60_000);
  `;
  const child = spawn(
    process.execPath,
    ["--input-type=module", "--eval", script, database],
    { env: { PATH: process.env.PATH } },
  );
  let output = "";
  child.stderr.setEncoding("utf8").on("data", (chunk: string) => {
    output += chunk;
  });
  try {
    await bounded(
      new Promise<void>((resolve, reject) => {
        child.stdout.setEncoding("utf8").on("data", (chunk: string) => {
          output += chunk;
          if (output.includes("owned")) resolve();
        });
        child.once("exit", (code) => {
          reject(new Error(`lock owner exited with ${code}: ${output}`));
        });
      }),
      "lock owner readiness",
    );
  } catch (error) {
    await kill(child, "SIGKILL");
    throw error;
  }
  return child;
}

async function bounded<T>(promise: Promise<T>, what: string): Promise<T> {
  return Promise.race([
    promise,
    delay(5000).then(() => {
      throw new Error(`timed out waiting for ${what}`);
    }),
  ]);
}

async function kill(
  child: ChildProcessWithoutNullStreams,
  signal: NodeJS.Signals,
): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const exited = once(child, "exit");
  child.kill(signal);
  await exited;
}

interface Contender {
  readonly child: ChildProcessWithoutNullStreams;
  readonly ready: Promise<void>;
  readonly outcome: Promise<"owned" | "busy">;
}

function startContender(database: string, gate: string): Contender {
  const script = `
    import { existsSync } from "node:fs";
    import { setTimeout as delay } from "node:timers/promises";
    import { acquireInstanceLock } from ${JSON.stringify(LOCK_MODULE)};
    console.log("ready");
    while (!existsSync(process.argv[2])) await delay(1);
    try {
      const owner = acquireInstanceLock(process.argv[1]);
      console.log("owned");
      setInterval(() => owner.path, 60_000);
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes("already in use")) throw error;
      console.log("busy");
    }
  `;
  const child = spawn(
    process.execPath,
    ["--input-type=module", "--eval", script, database, gate],
    { env: { PATH: process.env.PATH } },
  );
  let output = "";
  let ready!: () => void;
  let outcome!: (value: "owned" | "busy") => void;
  let rejectReady!: (error: Error) => void;
  let rejectOutcome!: (error: Error) => void;
  const readyPromise = new Promise<void>((resolve, reject) => {
    ready = resolve;
    rejectReady = reject;
  });
  const outcomePromise = new Promise<"owned" | "busy">((resolve, reject) => {
    outcome = resolve;
    rejectOutcome = reject;
  });
  const collect = (chunk: string): void => {
    output += chunk;
    if (output.includes("ready")) ready();
    if (output.includes("owned")) outcome("owned");
    if (output.includes("busy")) outcome("busy");
  };
  child.stdout.setEncoding("utf8").on("data", collect);
  child.stderr.setEncoding("utf8").on("data", collect);
  child.once("exit", (code) => {
    if (output.includes("busy") && code === 0) return;
    const error = new Error(`lock contender exited with ${code}: ${output}`);
    rejectReady(error);
    rejectOutcome(error);
  });
  return { child, ready: readyPromise, outcome: outcomePromise };
}

describe("MCP server instance lock", () => {
  it("refuses a second owner and releases idempotently", () => {
    const database = temporaryDatabase();
    const first = acquireInstanceLock(database);
    assert.throws(
      () => acquireInstanceLock(database),
      /already in use by another MCP server/,
    );
    first.release();
    first.release();

    const second = acquireInstanceLock(database);
    assert.equal(existsSync(second.path ?? ""), true);
    assert.ok(readFileSync(second.path ?? "").byteLength > 0);
    second.release();
  });

  it("recovers after an owning process is killed without deleting a lock file", async () => {
    const database = temporaryDatabase();
    const child = await startOwner(database);
    try {
      assert.throws(
        () => acquireInstanceLock(database),
        /already in use by another MCP server/,
      );
    } finally {
      await kill(child, "SIGKILL");
    }

    const recovered = acquireInstanceLock(database);
    recovered.release();
  });

  it("elects exactly one owner when processes simultaneously recover a legacy lock", async () => {
    const database = temporaryDatabase();
    const legacy = `${database}.mcp-server.lock`;
    const gate = `${database}.start`;
    writeFileSync(
      legacy,
      `${JSON.stringify({ pid: process.pid, nonce: "crashed-owner" })}\n`,
    );
    const contenders = Array.from({ length: 4 }, () =>
      startContender(database, gate),
    );
    try {
      await bounded(
        Promise.all(contenders.map((contender) => contender.ready)),
        "lock contenders to become ready",
      );
      writeFileSync(gate, "start\n");
      const outcomes = await bounded(
        Promise.all(contenders.map((contender) => contender.outcome)),
        "lock contender outcomes",
      );
      assert.equal(outcomes.filter((outcome) => outcome === "owned").length, 1);
      assert.equal(outcomes.filter((outcome) => outcome === "busy").length, 3);
    } finally {
      await Promise.all(
        contenders.map((contender) => kill(contender.child, "SIGKILL")),
      );
    }
  });

  it("replaces the recognized PID lock with a rollback guard even after PID reuse", () => {
    const database = temporaryDatabase();
    const legacy = `${database}.mcp-server.lock`;
    writeFileSync(
      legacy,
      `${JSON.stringify({ pid: process.pid, nonce: "old-container" })}\n`,
    );

    const lock = acquireInstanceLock(database);
    assert.deepEqual(JSON.parse(readFileSync(legacy, "utf8")), {
      format: "sqlite-owner-v1",
    });
    lock.release();

    const restarted = acquireInstanceLock(database);
    restarted.release();
  });

  it("keeps and refuses an unrecognized legacy ownership file", () => {
    const database = temporaryDatabase();
    const legacy = `${database}.mcp-server.lock`;
    writeFileSync(legacy, "operator data\n");

    assert.throws(
      () => acquireInstanceLock(database),
      /is not a recognized PID lock; inspect it before retrying/,
    );
    assert.equal(readFileSync(legacy, "utf8"), "operator data\n");
  });

  it("does not lock independent in-memory test stores", () => {
    const first = acquireInstanceLock(":memory:");
    const second = acquireInstanceLock(":memory:");
    first.release();
    second.release();
  });
});
