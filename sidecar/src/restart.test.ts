import assert from "node:assert/strict";
import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { once } from "node:events";
import { createServer, type AddressInfo } from "node:net";
import { describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";
import { fileURLToPath } from "node:url";

import { Code, ConnectError } from "@connectrpc/connect";

import {
  connectAgent,
  connectPhone,
  display,
  errorCode,
} from "./testing/clients.ts";
import { temporaryDatabasePath } from "./testing/process.ts";

const MCP_TOKEN = "m".repeat(64);
const PHONE_TOKEN = "p".repeat(64);
const MAIN = fileURLToPath(new URL("./main.ts", import.meta.url));
const DATABASE_PATH = temporaryDatabasePath();

async function freePort(): Promise<number> {
  const server = createServer().listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address() as AddressInfo;
  server.close();
  await once(server, "close");
  return port;
}

/** Runs `node src/main.ts` as a real process and waits until it listens. */
async function startProcess(
  port: number,
): Promise<ChildProcessWithoutNullStreams> {
  const child = spawn(process.execPath, [MAIN], {
    env: {
      PATH: process.env.PATH,
      SIDECAR_HOST: "127.0.0.1",
      SIDECAR_PORT: String(port),
      MCP_TOKEN,
      PHONE_TOKEN,
      LIVE_COMMAND_TIMEOUT_SECONDS: "30",
      // A throwaway database, never the developer's.
      DATABASE_PATH: DATABASE_PATH,
    },
  });
  let output = "";
  child.stderr
    .setEncoding("utf8")
    .on("data", (chunk: string) => (output += chunk));
  await new Promise<void>((resolve, reject) => {
    child.stdout.setEncoding("utf8").on("data", (chunk: string) => {
      output += chunk;
      if (output.includes("listening on")) resolve();
    });
    child.once("exit", (code) => {
      reject(new Error(`sidecar exited with ${code}: ${output}`));
    });
  });
  return child;
}

async function stop(
  child: ChildProcessWithoutNullStreams,
  signal: NodeJS.Signals,
): Promise<void> {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const exited = once(child, "exit");
  child.kill(signal);
  await exited;
}

/** After a restart the phone gets `ready` only, and the old command ID is unknown. */
async function assertNothingReplayed(
  url: string,
  oldCommandId: string,
): Promise<void> {
  const phone = await connectPhone(url, PHONE_TOKEN);
  const agent = await connectAgent(url, MCP_TOKEN);
  try {
    await assert.rejects(
      phone.acknowledge(oldCommandId),
      (error) => error instanceof ConnectError && error.code === Code.NotFound,
    );
    const nextEvent = phone.next();
    assert.equal(
      await Promise.race([
        nextEvent.then(() => "event"),
        delay(300).then(() => "quiet"),
      ]),
      "quiet",
    );
    const call = display(agent, "Hello after the restart");
    const next = await nextEvent;
    assert.ok(!next.done && next.value.event.case === "command");
    assert.equal(next.value.event.value.text, "Hello after the restart");
    await phone.acknowledge(next.value.event.value.id);
    assert.equal(errorCode(await call), undefined);
  } finally {
    phone.disconnect();
    await agent.close();
  }
}

describe("sidecar restart during a command", () => {
  for (const signal of ["SIGTERM", "SIGKILL"] as const) {
    it(`fails the original call on ${signal} and replays nothing after the restart`, async () => {
      const port = await freePort();
      const url = `http://127.0.0.1:${port}`;
      const first = await startProcess(port);
      let second: ChildProcessWithoutNullStreams | undefined;
      const phone = await connectPhone(url, PHONE_TOKEN);
      const agent = await connectAgent(url, MCP_TOKEN);
      try {
        const call = display(agent, "Hello before the restart", {
          timeoutMs: 3000,
        });
        const command = await phone.nextCommand();
        await stop(first, signal);
        if (signal === "SIGTERM") {
          assert.equal(errorCode(await call), "CANCELLED"); // a graceful stop answers the agent
        } else {
          await assert.rejects(call); // a crash leaves the agent with a failed request
        }
        await assert.rejects(phone.next());

        second = await startProcess(port);
        await assertNothingReplayed(url, command.id);
      } finally {
        phone.disconnect();
        await agent.close().catch(() => undefined);
        await stop(first, "SIGKILL");
        if (second !== undefined) await stop(second, "SIGTERM");
      }
    });
  }
});
