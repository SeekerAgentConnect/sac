/**
 * The Stage 1 acceptance cases (SAW-008) on the whole local path: the real CLI (`pnpm agent`) as
 * the agent, the sidecar as a real process, and the sidecar's Connect test client standing in for
 * the phone. It is a simulated-device check and never replaces the physical Seeker check
 * (docs/testing/stage-1.md). Run it with `pnpm test:hello`.
 */
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { after, describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

import {
  Code,
  ConnectError,
  connectPhone,
  type Phone,
} from "../../sidecar/src/testing/clients.ts";
import {
  freePort,
  startSidecarProcess,
  type SidecarProcess,
} from "../../sidecar/src/testing/process.ts";

const MAIN = fileURLToPath(new URL("./main.ts", import.meta.url));
const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);

interface Run {
  readonly code: number | null;
  readonly stdout: string;
  readonly stderr: string;
  readonly milliseconds: number;
}

const outputs: string[] = [];
const sidecars: SidecarProcess[] = [];

after(async () => {
  await Promise.all(sidecars.map((sidecar) => sidecar.stop("SIGKILL")));
});

async function sidecar(
  liveCommandTimeoutSeconds = 30,
  port?: number,
): Promise<SidecarProcess> {
  const started = await startSidecarProcess({
    port: port ?? (await freePort()),
    mcpToken: MCP_TOKEN,
    phoneToken: PHONE_TOKEN,
    liveCommandTimeoutSeconds,
  });
  sidecars.push(started);
  return started;
}

/** Runs `pnpm agent` as a real process against `target`, with only the given environment. */
function agent(
  target: SidecarProcess,
  args: readonly string[],
  liveCommandTimeoutSeconds = 30,
): Promise<Run> {
  const started = Date.now();
  const child = spawn(process.execPath, [MAIN, ...args], {
    env: {
      PATH: process.env.PATH ?? "",
      MCP_URL: `${target.url}/mcp`,
      MCP_TOKEN,
      PHONE_TOKEN, // present in a developer's .env; the CLI must not print it either
      LIVE_COMMAND_TIMEOUT_SECONDS: String(liveCommandTimeoutSeconds),
    },
  });
  let stdout = "";
  let stderr = "";
  child.stdout
    .setEncoding("utf8")
    .on("data", (chunk: string) => (stdout += chunk));
  child.stderr
    .setEncoding("utf8")
    .on("data", (chunk: string) => (stderr += chunk));
  return new Promise((resolve) => {
    child.on("close", (code) => {
      outputs.push(stdout, stderr);
      resolve({ code, stdout, stderr, milliseconds: Date.now() - started });
    });
  });
}

/** "quiet" when the phone receives nothing within `ms`. */
function quiet(phone: Phone, ms = 300): Promise<string> {
  return Promise.race([
    phone.next().then(
      () => "event",
      () => "ended",
    ),
    delay(ms).then(() => "quiet"),
  ]);
}

function acknowledgements(target: SidecarProcess, id: string): number {
  return target
    .output()
    .split("\n")
    .filter((line) => line.endsWith(`command ${id} acknowledged`)).length;
}

function isConnectError(code: Code): (error: unknown) => boolean {
  return (error) => error instanceof ConnectError && error.code === code;
}

describe("Stage 1 acceptance (simulated device)", () => {
  it("happy path: the phone shows the exact text, and the agent prints the OK of that command", async () => {
    const target = await sidecar();
    const phone = await connectPhone(target.url, PHONE_TOKEN);
    try {
      const text = "Hello Seeker — stage 1 ✓ 👋";
      const run = agent(target, ["hello", text]);
      const command = await phone.nextCommand();
      assert.equal(command.text, text);
      await phone.acknowledge(command.id);
      const { code, stdout, stderr } = await run;
      assert.equal(code, 0, stderr);
      assert.deepEqual(JSON.parse(stdout), { id: command.id, result: "OK" });
      assert.equal(acknowledgements(target, command.id), 1);
    } finally {
      phone.disconnect();
    }
  });

  it("app offline: OFFLINE at once, and a phone that connects later receives nothing", async () => {
    const target = await sidecar();
    const { code, stdout, stderr } = await agent(target, [
      "hello",
      "Nobody is watching",
    ]);
    assert.equal(code, 4);
    assert.equal(stdout, "");
    assert.match(stderr, /^OFFLINE: /m);
    const phone = await connectPhone(target.url, PHONE_TOKEN);
    try {
      assert.equal(await quiet(phone), "quiet");
    } finally {
      phone.disconnect();
    }
  });

  it("second simultaneous command: BUSY, and the first command still completes", async () => {
    const target = await sidecar();
    const phone = await connectPhone(target.url, PHONE_TOKEN);
    try {
      const first = agent(target, ["hello", "first"]);
      const command = await phone.nextCommand();
      const second = await agent(target, ["hello", "second"]);
      assert.equal(second.code, 5);
      assert.match(second.stderr, /^BUSY: /m);
      await phone.acknowledge(command.id);
      const { code, stdout } = await first;
      assert.equal(code, 0);
      assert.equal((JSON.parse(stdout) as { id: string }).id, command.id);
      assert.equal(await quiet(phone), "quiet"); // "second" was never delivered
    } finally {
      phone.disconnect();
    }
  });

  it("timeout: TIMEOUT at the sidecar's deadline, and a late OK is refused", async () => {
    const target = await sidecar(1);
    const phone = await connectPhone(target.url, PHONE_TOKEN);
    try {
      const run = agent(target, ["hello", "Nobody taps OK"], 1);
      const command = await phone.nextCommand();
      const { code, stderr } = await run;
      assert.equal(code, 6);
      assert.match(stderr, /^TIMEOUT: no acknowledgement within 1 seconds/m);
      await assert.rejects(
        phone.acknowledge(command.id),
        isConnectError(Code.DeadlineExceeded),
      );
    } finally {
      phone.disconnect();
    }
  });

  it("double tap: two OKs for one command make one acknowledgement", async () => {
    const target = await sidecar();
    const phone = await connectPhone(target.url, PHONE_TOKEN);
    try {
      const run = agent(target, ["hello", "Tap twice"]);
      const command = await phone.nextCommand();
      // Both taps reach the sidecar; the second one succeeds without a second effect.
      await Promise.all([
        phone.acknowledge(command.id),
        phone.acknowledge(command.id),
      ]);
      const { code, stdout } = await run;
      assert.equal(code, 0);
      assert.deepEqual(JSON.parse(stdout), { id: command.id, result: "OK" });
      assert.equal(acknowledgements(target, command.id), 1);
    } finally {
      phone.disconnect();
    }
  });

  for (const [signal, exitCode, message] of [
    ["SIGTERM", 7, /^CANCELLED: /m],
    ["SIGKILL", 3, /lost the connection to the sidecar/],
  ] as const) {
    it(`sidecar restart (${signal}): the waiting call fails at once, and nothing is replayed`, async () => {
      const port = await freePort();
      const first = await sidecar(30, port);
      const phone = await connectPhone(first.url, PHONE_TOKEN);
      const run = agent(first, [
        "hello",
        "Before the restart",
        "--timeout",
        "20",
      ]);
      const command = await phone.nextCommand();
      await first.stop(signal);
      const { code, stderr, milliseconds } = await run;
      phone.disconnect();
      assert.equal(code, exitCode, stderr);
      assert.match(stderr, message);
      assert.ok(milliseconds < 10_000, `took ${milliseconds} ms`); // not the 20 s client timeout

      const second = await sidecar(30, port);
      const reopened = await connectPhone(second.url, PHONE_TOKEN);
      try {
        await assert.rejects(
          reopened.acknowledge(command.id),
          isConnectError(Code.NotFound),
        );
        assert.equal(await quiet(reopened), "quiet");
      } finally {
        reopened.disconnect();
      }
    });
  }

  it("app restart: the waiting call is CANCELLED, and the reopened app receives nothing", async () => {
    const target = await sidecar();
    const phone = await connectPhone(target.url, PHONE_TOKEN);
    const run = agent(target, ["hello", "Before the app restarts"]);
    const command = await phone.nextCommand();
    phone.disconnect(); // the app's process dies, and its stream closes
    const { code, stderr } = await run;
    assert.equal(code, 7, stderr);
    assert.match(stderr, /^CANCELLED: /m);
    const reopened = await connectPhone(target.url, PHONE_TOKEN);
    try {
      await assert.rejects(
        reopened.acknowledge(command.id),
        isConnectError(Code.Canceled),
      );
      assert.equal(await quiet(reopened), "quiet");
    } finally {
      reopened.disconnect();
    }
  });

  it("never prints a token", () => {
    assert.ok(outputs.length > 0);
    for (const output of outputs) {
      for (const secret of [MCP_TOKEN, PHONE_TOKEN]) {
        assert.ok(!output.includes(secret), output);
      }
    }
  });
});
